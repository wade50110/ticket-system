# 需求書:監控(Prometheus + Grafana)

> 狀態:**已完成**(草稿 → 已定稿 → 開發中 → 已完成)。2026-09-24 定稿、開發、全部 AC 於本機 Docker Desktop k8s 實測通過(見 §11)。現況以 [`../../monitoring.md`](../../monitoring.md) 為準。
> 版本:v0.6
> 建立日期:2026-09-24
> Todo:monitoring-todo.md(開發開始後補上)
> 相依:v0.5 [rate-limit.md](../v0.5/rate-limit.md)(排除清單需含 `/actuator/prometheus`,見 §10)

## 1. 目標與背景

搶票系統是高併發系統,尖峰時最需要「看得到」:請求量、延遲、結帳成功/失敗率、超賣防護是否觸發、HPA 擴縮動態。目前只有 metrics-server(給 HPA 當前 CPU,無歷史、無儀表板)。本功能把 **Prometheus(抓取 + 存時序)+ Grafana(儀表板)** 以**精簡版手寫 manifests** 部署進本機 k8s,並讓後端用 Micrometer 暴露應用與自訂業務指標。

定位:**可觀測性層,盡量與防超賣核心解耦**。但誠實面對現實——`CheckoutService.checkout()` 的分散式鎖臨界區涵蓋「Lua 扣減 + 建單 + 付款」,且外層有 `catch(RuntimeException)→rollback→拋 CheckoutException`。因此業務指標埋點**無法完全避開臨界區**,必須遵守 §2「埋點硬約束」,否則「監控 bug」會變成「結帳失敗 + 誤觸回滾」。**只觀察不改變業務行為**是紅線(對齊 CLAUDE.md)。

## 2. 功能需求

### 2.1 後端指標暴露
- **F-1**:引入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`,提供 `GET /actuator/prometheus`(Prometheus 文字格式)。
- **F-2**:自動指標涵蓋 JVM(記憶體/GC/執行緒)與 HTTP 伺服端 `http_server_requests`。**必須啟用 server-side 直方圖**(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`),否則多 pod 無法用 `histogram_quantile` 正確聚合 p99(client-side percentile 不可跨 pod 相加)。
- **F-3**:自訂業務指標(Micrometer),至少:
  - `ticket_checkout_total{result="success|fail", reason="sold_out|lock_failed|quota_exceeded|other"}`(Counter):結帳結果;`reason` 只在 `result=fail` 有意義。
  - `ticket_oversell_guard_total{type="stock_insufficient|rollback"}`(Counter):超賣防護觸發次數。
  - `ticket_checkout_duration`(Timer,`publishPercentileHistogram()`):結帳耗時,供 `histogram_quantile` 算 p50/p95/p99。
  - `ticket_refund_total{result="success|fail"}`(Counter):退票結果。
- **F-4**:actuator **只暴露 `prometheus`**(`management.endpoints.web.exposure.include=prometheus`)。**不暴露 `/actuator/health`**——k8s probe 用既有 `/api/health`(HealthController)、Prometheus 抓 `/actuator/prometheus`,兩者都不需 actuator health,少一個匿名可探依賴健康的面。

### 2.2 埋點硬約束(防超賣紅線,必守)
- **F-5**:**exception-safe**——任何 `MeterRegistry` 呼叫都不得中斷結帳/退票流程。埋點要嘛保證純記憶體不拋例外,要嘛自包 try/catch;**絕不可**讓指標例外被 `checkout()` 的 `catch(RuntimeException)` 捕捉而觸發 rollback。
- **F-6**:**熱路徑純記憶體 O(1)**——臨界區內的埋點只做記憶體累加,**禁止任何 Redis/DB I/O**;Counter/Timer 必須**啟動時預先註冊並持有參照**,不在鎖內用動態 tag 每次查/建 meter。
- **F-7**:**埋點位置最小化臨界區**——
  - `ticket_checkout_total`(成功/失敗計數)與 `ticket_checkout_duration` 在 **controller 層**(鎖外)量:controller 包住 `checkoutService.checkout()`,正常回成功、接到 `CheckoutException` 記 fail 並讀其 reason。
  - 僅 `ticket_oversell_guard_total`(需知道 Lua 扣減結果)留在 `CheckoutService` 臨界區,遵守 F-5/F-6。
  - `ticket_refund_total` 放在 `RefundService` 的 `markRefunded` 狀態翻轉**成功之後**。
- **F-8**:**允許為埋點對 `CheckoutException` 增加 typed `reason` 欄位**(enum)——這是不影響控制流程的加法修改;各 throw 點帶上 reason,controller 據此打標籤。未列舉情況收斂為 `other`。
- **F-9**:**基數(cardinality)護欄**——標籤值只用有界列舉(如上);**禁止**把 userId/orderId/ticketId/ticketName 等高基數識別碼當標籤;`http_server_requests` 的 `uri` 靠 Spring 路由樣板化(`/api/orders/{id}`)。

### 2.3 監控元件(k8s,精簡版手寫,`monitoring` namespace)
- **F-10**:Prometheus 以 Deployment 跑(**emptyDir 不持久**),`scrape_interval: 15s`、`--storage.tsdb.retention.time=3h`、設 resources request/limit(避免單機 OOM)。抓取:①後端 `/actuator/prometheus`、②kubelet/cAdvisor(每 pod CPU/mem)、③kube-state-metrics(物件狀態,含 HPA 現值與 Deployment 副本數)、④node-exporter(節點/VM OS 資源)。
- **F-11**:node-exporter 以 DaemonSet 跑(單節點=1 份);kube-state-metrics 以 Deployment 跑(1 份)。
- **F-12**:**RBAC 分兩組**——
  - Prometheus 的 ServiceAccount + ClusterRole:`nodes`、`nodes/proxy`、`nodes/metrics`(get)、`services`/`endpoints`/`pods`(get/list/watch),供 kubernetes_sd 與 kubelet/cAdvisor 抓取(缺 `nodes/proxy` 會導致 cadvisor target 403/DOWN)。
  - kube-state-metrics 的**自己一組** ServiceAccount + ClusterRole:對 `deployments`/`replicasets`/`pods`/`nodes`/`horizontalpodautoscalers` 等 list/watch。
- **F-13**:Grafana 以 Deployment 跑,自動 provision:①Prometheus 資料源、②一份「搶票總覽」儀表板(JSON 以 ConfigMap 掛入)。
- **F-14**:後端 `k8s/backend.yaml` 的 pod template 加 scrape 註解(`prometheus.io/scrape: "true"`、`prometheus.io/path: /actuator/prometheus`、`prometheus.io/port: "8099"`);Prometheus 用 kubernetes_sd 依註解自動發現後端 pod(HPA 擴縮時新 pod 自動被抓)。
- **F-15**:抓 kubelet/cAdvisor 經 apiserver proxy,TLS 用 pod 內 SA 掛載的叢集 CA(`ca_file`)正常驗證;**不**用 `insecure_skip_verify`(實作時修正,見決策 #21;原稿誤以為需比照 metrics-server「直連 kubelet 自簽」的情境)。

### 2.4 對外存取
- **F-16**:Grafana 用 Service `type: LoadBalancer` → `localhost:3000`(瀏覽器開 http://localhost:3000),dev 帳密 `admin/admin`(放 Secret)。
- **F-17**:Prometheus 用 `ClusterIP`(叢集內);要看 Prometheus 自身 UI 用 `kubectl port-forward`(不常態對外)。

## 3. API 規格 / 安全設定

| Method | Path | 權限 | 說明 |
|--------|------|------|------|
| GET | `/actuator/prometheus` | 公開(匿名可存取) | Prometheus 文字格式指標;供叢集內 Prometheus 抓取 |

- SecurityConfig(現況為單一 chain + `.anyRequest().authenticated()`)放行寫法用 **`EndpointRequest.to(PrometheusScrapeEndpoint.class).permitAll()`**(比字串 path 穩、會跟隨 management base-path),置於 `.anyRequest().authenticated()` 之前;其餘 `/actuator/**` 不 permitAll、且因 exposure 未暴露而不可得。
- 此功能不新增業務 API、無新錯誤碼;actuator 端點非 JSON、不走 GlobalExceptionHandler。

Response 範例(節錄):
```
# HELP ticket_checkout_total
# TYPE ticket_checkout_total counter
ticket_checkout_total{result="success"} 128.0
ticket_checkout_total{result="fail",reason="sold_out"} 37.0
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket{uri="/api/checkout",le="0.1"} 140.0
```

## 4. 資料模型變更

**無。** 不新增/修改任何資料表或欄位。監控資料存 Prometheus 時序 DB(emptyDir,pod 重啟即清)。程式面唯一的資料結構加法:`CheckoutException` 增加 typed `reason` 欄位(見 F-8),不涉及 DB。

## 5. 邊界情況與錯誤處理

| 情況 | 系統怎麼反應 |
|------|--------------|
| **指標埋點拋例外** | 依 F-5,埋點自包保護,不中斷結帳/退票、不誤觸 rollback;結帳照常成功(此為本功能最關鍵的迴歸點,見 T-2) |
| Prometheus pod 重啟 | 監控歷史清空(emptyDir,符合預期);不影響後端與其他元件;重啟後重新累積 |
| 後端 pod 尚未 Ready | Prometheus target 顯示 DOWN;Ready 後自動 UP |
| **後端 graceful shutdown 期間** | preStop sleep 5s / terminationGracePeriod 60s 內,scrape 可能短暫連線被拒→target 暫 DOWN,屬預期,勿誤判 |
| 後端擴縮(HPA 2↔N) | 依 pod 註解 + k8s SD 自動增減 target,新 pod 上線即被抓 |
| Grafana 首次啟動 | 自動載入 provision 的資料源與儀表板;無數據時面板顯示 No data |
| **`/actuator/prometheus` 被 v0.5 rate-limit 限流** | 見 §10:必須把此路徑加入 rate-limit 排除清單,否則匿名 per-IP 計數可能對 scrape 回 429、造成 target 抖動與資料斷點 |
| `/actuator/prometheus` 被外部匿名存取 | 可讀(僅有界列舉指標,無業務敏感資料);其餘 actuator 端點未暴露 |
| node-exporter 量到的 OS 資源 | 是 k8s 節點(Docker Desktop 的 LinuxKit VM)資源,**非** Windows 主機資源;儀表板標題註明避免誤解 |
| kubelet/cAdvisor 自簽 TLS | Prometheus `insecure_skip_verify`(F-15) |
| 監控元件掛掉 | 不影響搶票主流程(監控與業務解耦);只是暫時看不到儀表板 |

## 6. 非目標(範圍外)

- 不做 Alertmanager / 告警規則。
- 不做 prometheus-adapter,也不改 HPA(維持 CPU-based)。
- 不做外部 MySQL/Redis 的 exporter。
- 不做 Prometheus 資料 PV 持久化(用 emptyDir)。
- 不做雲上(EKS)監控、不做 Grafana 登入整合(用 dev admin 帳密)。
- **不改任何結帳/庫存/鎖/退票的業務行為**(只加觀測與 `CheckoutException` 的 reason 欄位;控制流程一字不改)。

## 7. 驗收條件

- **AC-1**:`GET /actuator/prometheus` 回 200,格式為 Prometheus 可解析(含 `# TYPE`),內容含 `jvm_`、`http_server_requests_seconds_bucket`(有 histogram)、及自訂 `ticket_checkout_total`。
- **AC-2**:`/actuator/prometheus` 匿名(不帶 JWT)回 200;受保護 API(如 `/api/orders`)不帶 JWT 回 401/403;`/actuator/env`、`/actuator/beans` 等匿名不可得(未暴露)。
- **AC-3**:自訂業務指標正確累計——結帳成功一次 `{result="success"}` +1;售罄失敗一次 `{result="fail",reason="sold_out"}` +1;鎖失敗 `reason="lock_failed"`、限購失敗 `reason="quota_exceeded"`;超賣防護觸發時 `ticket_oversell_guard_total` +1;耗時進入 `ticket_checkout_duration`。
- **AC-4**:埋點例外不影響業務——當指標記錄路徑拋例外時,`checkout()` 仍正常完成、回成功、不誤觸 rollback(對應 F-5)。
- **AC-5**:`kubectl apply` 後 Prometheus pod Running;targets 頁看到 backend(全 UP)、node-exporter、kube-state-metrics、cadvisor 皆 UP。
- **AC-6**:Grafana pod Running,`http://localhost:3000` 可用 dev 帳密登入;Prometheus 資料源已設好;「搶票總覽」儀表板自動載入。
- **AC-7**:壓測時儀表板能看到:RPS、`/api/checkout` p99(用 `histogram_quantile` 跨 pod 算,對應 F-2)、結帳成功/失敗數、超賣防護觸發、每 pod CPU、後端副本數隨 HPA 由 2 上升(副本數來自 kube-state-metrics)。
- **AC-8**:Prometheus pod 刪除重建後舊歷史消失(emptyDir 預期),元件恢復抓取。

## 8. 測試要求

**可自動化(後端單元測試,`mvnw test`)**
- **T-1**:自訂指標增量——注入 `SimpleMeterRegistry`,mock `stockRedis`/`quotaRedis`/`distributedLock` 逼出成功、售罄、鎖失敗、限購失敗各路徑,驗證對應 Counter 增量、reason 標籤正確、Timer 有記錄(對應 AC-3)。
- **T-2**:埋點例外迴歸(對應 AC-4/F-5)——讓指標記錄路徑拋例外(例如注入會拋的 registry/來源),驗證 `checkout()` 仍成功、不變 fail、不誤觸 rollback。**這是本功能最關鍵的迴歸測試,不可省。**
- **T-3**:安全設定——**改為手動驗證(併入 M-1 runbook)**,理由見決策 #18:actuator 端點的安全整合測試需 bootable Spring context,本專案測試層為 Mockito 單元測試、無 DB/Redis context 基礎設施。手動以 curl 驗 `/actuator/prometheus` 匿名 200、`/api/orders` 未帶 token 401/403、`/actuator/env` 匿名不可得(對應 AC-2)。
- **T-4**:修正後重跑**全套**測試,確認未破壞既有結帳/庫存/退票測試(含既有結帳併發測試——正好覆蓋埋點是否引入 race/例外)。✅ 已跑:38 passed,`QuotaRedisRepositoryRedisTest` 因 Redis 未開自動跳過。

**手動驗證(整合層,無自動化測試,列入 runbook)**
- **M-1**:AC-5/6/7/8 屬 k8s 部署與儀表板層,與現有 k8s 部署一致採手動驗證;於 `k8s/monitoring/README.md` 補實測步驟(apply → 看 targets UP → 開 Grafana → 壓測看 p99 與擴縮)。此部分不寫自動化測試,理由同既有 k8s 部署(專案暫無叢集整合測試基礎設施)。

## 9. 決策紀錄

| # | 問題 | 決定 | 理由 |
|---|------|------|------|
| 1 | 部署方式:精簡版 vs Helm stack | **精簡版手寫 manifests** | 單節點資源有限;透明好學、與現有手寫 manifests 一致;不引入 Helm |
| 2 | 監控範圍 | **應用 + 基本 k8s** | 搶票該看的都涵蓋;不加外部 DB exporter 控元件數 |
| 3 | Prometheus 資料持久化 | **emptyDir 不持久** | 監控資料非正源,掉了不致命;demo 最輕 |
| 4 | HPA 改吃自訂指標 | **這次不做(範圍外)** | 先立監控;prometheus-adapter 留下一版,避免一次太大 |
| 5 | Grafana 對外 | **LoadBalancer → localhost:3000** | 與前端一致、本機直接開最直覺,不與前端 :80 衝突 |
| 6 | Grafana 儀表板 | **provision as code(自動帶搶票儀表板 + 資料源)** | 開箱即用、可版控、重建免手動 |
| 7 | Grafana 登入 | **dev admin/admin(Secret)** | 與專案 dev secret 慣例一致(僅本機) |
| 8 | 監控元件 namespace | **獨立 `monitoring`** | 與 app(default)分離;Prometheus ClusterRole 跨 ns 抓 |
| 9 | 後端 scrape 發現 | **pod 註解 + kubernetes_sd** | HPA 擴縮新 pod 自動被發現,免改 Prometheus 設定 |
| 10 | actuator 暴露範圍 | **僅 `prometheus`(不含 health)** | probe 用既有 `/api/health`;少一個匿名探依賴健康的面 |
| 11 | 多 pod p99 怎麼算 | **server-side 直方圖 + `histogram_quantile`** | client-side percentile 不可跨 pod 聚合;不開 histogram 則 AC-7 的 p99 做不出來 |
| 12 | fail 的 reason 從哪來、在哪計數 | **給 `CheckoutException` 加 typed reason 欄位;成功/失敗/duration 在 controller 層計數,臨界區只留 oversell_guard** | 最小化臨界區埋點;reason 欄位是不影響流程的加法 |
| 13 | 埋點與防超賣核心的風險 | **硬約束:exception-safe、純記憶體 O(1)、預註冊 meter、禁 I/O、禁高基數 tag**(F-5~F-9) | 臨界區 + `catch→rollback` 下,指標 bug 會害結帳失敗;此為紅線 |
| 14 | SecurityConfig 放行寫法 | **`EndpointRequest.to("prometheus").permitAll()`**(端點 id 字串版)置於 `anyRequest` 前 | 比字串 path matcher 穩、跟隨 management base-path;用端點 id 而非 `PrometheusScrapeEndpoint.class`,避免該類在不同 Spring Boot 版本換套件路徑,功能等價(實作時採用) |
| 15 | Prometheus 運行參數 | **scrape 15s、retention 3h、設 resources** | 控制 emptyDir 佔用與單機 OOM 風險 |
| 16 | RBAC | **分兩組(Prometheus 抓取用含 `nodes/proxy`;kube-state-metrics 自己一組含 HPA)** | 缺 `nodes/proxy` cadvisor 會 DOWN;KSM 需自己的權限否則起不來、AC-7 副本數看不到 |
| 17 | 與 v0.5 rate-limit 的交互 | **把 `/actuator/prometheus` 加入 rate-limit 排除清單(兩份需求書一致);scrape 15s(=4/分)遠低於 60/分** | 否則 scrape 被限流→429→target 抖動;跨需求書一致性,見 §10 |
| 18 | T-3 actuator 安全測試自動化不可行 | **改為手動驗證(M-1 runbook 以 curl 驗)** | 開發時發現:actuator+security 整合測試需 bootable Spring context,本專案測試層全為 Mockito 單元測試、無 DB/Redis context 基礎設施(與 architecture.md 記載一致);不為此硬塞一個需外部依賴才能跑的測試。核心保護(埋點不害結帳 AC-4、指標正確 AC-3)已由 T-1/T-2 自動化覆蓋 |
| 19 | 儀表板「每 pod CPU」查詢實測回空 | **改用 `container=""` 過濾 + `sum by (pod)`** | 根因:Docker Desktop 以 docker(cri-dockerd)為 runtime,cAdvisor 只有 pod 層級 cgroup 系列(container/image 為空、Prometheus 丟空標籤),`container!=""` 會全濾掉。`container=""` 同時匹配「無標籤」與「空字串」:本機正確,搬到 containerd(EKS)也只取 pod cgroup、不會因多出 POD/容器系列而 2 倍高估(k8s review 修正;原稿誤歸因於 k8s 1.34/apiserver proxy) |
| 20 | code review 抓到的標籤鍵不一致 bug | **`ticket_checkout_total` 的 success 也帶 `reason=none`;新增真 `PrometheusMeterRegistry.scrape()` 測試** | 同 name 下鍵集不一致時 PrometheusMeterRegistry 以先註冊者為準、靜默丟棄其餘系列(fail 整組消失);`SimpleMeterRegistry` 測試抓不到,必須用真 registry 的 scrape 守 |
| 21 | cAdvisor 抓取的 TLS | **用 SA 掛載的叢集 CA(`ca_file`)驗證,移除 `insecure_skip_verify`**(偏離 F-15 原稿,已同步改 F-15) | 走 apiserver proxy 非直連 kubelet;apiserver 憑證 SAN 含 `kubernetes.default.svc`、pod 內 ca.crt 即叢集 CA(review 實測 openssl verify OK)。skip 的理由只適用直連 kubelet |
| 22 | k8s review 後的收斂 | **KSM `--resources` 限縮 + 最小 ClusterRole(不含 secrets/configmaps)、Prometheus ClusterRole 去掉未用權限、移除 `--web.enable-lifecycle`、三元件補 liveness、`ticket-backend` job 限 namespace/app、namespace 加 PSA privileged 標籤** | KSM 上游全功能 RBAC 可 list 全叢集 Secret(含 JWT/DB 密碼),儀表板只用 deployment/HPA 指標;`/-/quit` 未認證是叢集內 footgun;最小權限與探針屬正確做法,對 dev 無副作用 |
| 23 | Endpoints API 棄用警告 | **維持 `role: endpoints`,記為技術債** | k8s 1.33+ 僅 log 警告、功能正常;改 endpointslice 有 relabel 對不上的風險,不值得在此輪冒 |

## 11. 驗收紀錄(2026-09-24,本機 Docker Desktop k8s v1.34 實測)

| AC | 結果 | 證據 |
|----|------|------|
| AC-1 | ✅ | `/actuator/prometheus` 200;含 `jvm_*`、`http_server_requests_seconds_bucket{le=…}`(histogram)、`ticket_checkout_total` **五條系列全部輸出**(success/reason=none + 4 個 fail reason) |
| AC-2 | ✅(手動 T-3) | `/actuator/prometheus` 匿名 200;`/api/orders` 無 token 403;`/actuator/env`、`/actuator/health` 403(未暴露) |
| AC-3 | ✅(自動化 T-1) | CheckoutControllerTest / CheckoutServiceTest / RefundServiceTest 驗計數與 reason 標籤 |
| AC-4 | ✅(自動化 T-2) | mock metrics 拋例外 → 結帳仍成功、業務例外仍往外拋;in-lock 版 → 仍 SOLD_OUT、回滾只一次 |
| AC-5 | ✅ | targets:`ticket-backend`(=pod 數)、`kubernetes-cadvisor`、`kube-state-metrics`、`node-exporter`、`prometheus` 全 UP,`up==0` 為空 |
| AC-6 | ✅ | Grafana 10.4.5 健康;「搶票總覽 (ticket-system)」儀表板與 Prometheus 資料源自動 provision |
| AC-7 | ✅ | hey 120s c=40 打 login(BCrypt):4102 req、~34 rps;HPA `cpu 410%/50%`、backend pod **2→8**;Prometheus:login RPS 34.6/s、跨 pod p99 3.25s、`kube_deployment_status_replicas` 與 HPA 現值可見、每 pod CPU 可見(依決策 #19 修正後) |
| AC-8 | ✅ | 刪 Prometheus pod → 13s 內重建、TSDB head 重新累積(歷史清空)、targets 恢復 UP、`up==0` 為空 |

全套後端測試:**40 passed**(含 2 顆 review 後新增的防迴歸測試)。

## 10. 跨需求書相依(v0.5 rate-limit)

- v0.5 [rate-limit.md](../v0.5/rate-limit.md) 目前排除清單只有 `GET /api/health`。本功能要求把 **`/actuator/prometheus`** 也加入排除清單(該檔已同步補上並註明「for v0.6 監控 scrape」)。
- 開發順序:兩功能獨立,但**若 rate-limit 先實作**,其排除清單必須已含 `/actuator/prometheus`,否則 Prometheus scrape 會被限流;本需求書開發時需確認此點。
- scrape_interval 15s(≈4 次/分)本就遠低於 rate-limit 預設 60/分,排除後更無虞。
