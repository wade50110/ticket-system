# 監控(Prometheus + Grafana)— 現況 spec

> 對應版本:v0.6(需求書:[`requirements/v0.6/monitoring.md`](requirements/v0.6/monitoring.md),已完成)
> 最後更新:2026-09-24
> 部署 runbook:[`../k8s/monitoring/README.md`](../k8s/monitoring/README.md)

## 1. 現況規格

### 1.1 後端指標暴露
- 依賴:`spring-boot-starter-actuator` + `micrometer-registry-prometheus`。
- 端點:`GET /actuator/prometheus`(Prometheus 文字格式),**匿名可讀**;actuator **只暴露 `prometheus`**(`management.endpoints.web.exposure.include=prometheus`),`/actuator/health`、`/actuator/env` 等**不暴露**(匿名 403)。k8s probe 仍用既有 `/api/health`。
- 自動指標:JVM、`http_server_requests`(**已開 server-side 直方圖** `percentiles-histogram`,供 `histogram_quantile` 跨 pod 算 p99)。所有指標帶共同標籤 `application=ticket-system`。
- 自訂業務指標(集中在 [`metrics/TicketMetrics`](../backend/src/main/java/com/example/ticket/metrics/TicketMetrics.java)):

| 指標 | 型別 | 標籤 | 在哪計 |
|------|------|------|--------|
| `ticket_checkout_total` | Counter | `result=success\|fail`、`reason=none\|sold_out\|lock_failed\|quota_exceeded\|other` | `CheckoutController`(鎖外) |
| `ticket_checkout_duration` | Timer(histogram) | — | `CheckoutController`(鎖外) |
| `ticket_oversell_guard_total` | Counter | `type=stock_insufficient\|rollback` | `CheckoutService`(鎖臨界區,唯一 in-lock 埋點) |
| `ticket_refund_total` | Counter | `result=success\|fail` | `RefundService`(`markRefunded` 翻轉成功後) |

- `CheckoutException` 帶 typed `Reason` enum(SOLD_OUT / LOCK_FAILED / QUOTA_EXCEEDED / OTHER),各 throw 點設定;純觀測用,不影響控制流程。

### 1.2 監控元件(k8s,`monitoring` namespace,精簡版手寫 manifests)
- **Prometheus**(`k8s/monitoring/10-prometheus.yaml`):Deployment ×1,**emptyDir 不持久**,`scrape_interval 15s`、`retention 3h`(控磁碟;記憶體靠 limits 與系列數);依 pod 註解(`prometheus.io/scrape|path|port`)自動發現後端 pod(限 `default` ns + `app=ticket-backend`);經 apiserver proxy 抓 kubelet/cAdvisor(用 SA 掛載的叢集 CA `ca_file` 驗證,**不** skip);抓 kube-state-metrics、node-exporter。**最小權限** ClusterRole(sd 用的 get/list/watch + `nodes/proxy` get)。**不開 `--web.enable-lifecycle`**(改設定用 rollout restart)。有 readiness `/-/ready` + liveness `/-/healthy`。Service ClusterIP,看 UI 用 port-forward。
- **node-exporter**(`20-node-exporter.yaml`):DaemonSet,hostNetwork/hostPID,量的是 k8s 節點(Docker Desktop 的 LinuxKit VM);未掛 rootfs,`node_filesystem_*` 量到的是容器 rootfs(需求只要 CPU/記憶體/負載)。namespace 帶 PSA `privileged` 標籤外顯此需求。
- **kube-state-metrics**(`30-kube-state-metrics.yaml`):Deployment ×1,**`--resources` 限縮**為 deployments/replicasets/pods/horizontalpodautoscalers/nodes/namespaces + 對應的最小 ClusterRole(**不含 secrets/configmaps**——上游全功能 RBAC 會讓它能 list 全叢集 Secret);readiness `/readyz`(8081)、liveness `/livez`(8080)。提供 `kube_deployment_status_replicas`、`kube_horizontalpodautoscaler_*`。
- **Grafana**(`40-grafana.yaml`):Deployment ×1,provision as code(Prometheus 資料源 + 「搶票總覽 (ticket-system)」儀表板 JSON 以 ConfigMap 掛入);Service **LoadBalancer → http://localhost:3000**,dev 帳密 `admin/admin`(Secret)。
- 後端 `k8s/backend.yaml` pod template 帶 scrape 註解,HPA 擴縮新 pod 自動被抓。

### 1.3 儀表板「搶票總覽」面板
RPS(/api/checkout 與全部)、結帳延遲 p50/p99(跨 pod)、結帳結果(成功/失敗依原因)、超賣防護觸發/補償回滾、後端副本數(Deployment 與 HPA 現值)、每 pod CPU、JVM heap、退票結果。

## 2. 檔案地圖

| 層 | 檔案 |
|----|------|
| 指標元件 | `backend/.../metrics/TicketMetrics.java` |
| 埋點 | `checkout/CheckoutController.java`、`checkout/CheckoutService.java`(oversell_guard)、`order/RefundService.java`;`checkout/CheckoutException.java`(Reason) |
| 設定 | `backend/pom.xml`(actuator、micrometer-prometheus)、`application.yml`(`management.*`)、`config/SecurityConfig.java`(`EndpointRequest.to("prometheus").permitAll()`) |
| k8s | `k8s/monitoring/00-namespace.yaml`、`10-prometheus.yaml`、`20-node-exporter.yaml`、`30-kube-state-metrics.yaml`、`40-grafana.yaml`、`README.md`;`k8s/backend.yaml`(scrape 註解) |
| 測試 | `CheckoutControllerTest`、`CheckoutServiceTest`、`RefundServiceTest` |

## 3. 設計意圖(不要動的理由)

1. **埋點硬約束(防超賣紅線)**:`CheckoutService.checkout()` 的分散式鎖臨界區外層有 `catch(RuntimeException)→rollback→拋 CheckoutException`。任何指標例外若逸出,會把一次正常的售罄失敗變成「結帳發生例外」並多跑一次回滾。因此:
   - `TicketMetrics` 每個 record 方法內部 try/catch(exception-safe);`CheckoutService` 的 in-lock 呼叫再包一層 `safeMetric`(defense-in-depth);`CheckoutController` 也包 `safe`。
   - 所有 meter **建構時預先註冊並持有參照**,熱路徑純記憶體 O(1),**禁止 Redis/DB I/O**。
   - 埋點位置最小化臨界區:成功/失敗/耗時在 controller(鎖外),臨界區只留 `oversell_guard`。
   - **不要**在臨界區新增會做 I/O 或動態建 meter 的埋點。
2. **同一 metric name 的標籤鍵集合必須一致**:`ticket_checkout_total` 的 success 也帶 `reason=none`。否則 `PrometheusMeterRegistry` 以第一個註冊者的鍵集為準、scrape 時**靜默丟棄**其餘系列(曾實際發生:fail 系列整組消失,而 `SimpleMeterRegistry` 測試全綠抓不到)。新增標籤時務必讓該 name 下所有系列鍵集相同,並用 `PrometheusMeterRegistry.scrape()` 測。
3. **基數護欄**:標籤值只用有界列舉;**禁止** userId / orderId / ticketId / ticketName 等高基數識別碼當標籤。
4. **只暴露 `prometheus`**:少一個匿名可探依賴健康的面;probe 用 `/api/health`。
5. **p99 用 server-side 直方圖 + `histogram_quantile`**:client-side percentile 不可跨 pod 聚合。
6. **監控與業務解耦**:監控元件掛掉不影響搶票主流程;Prometheus 資料非正源,emptyDir 重啟即清是刻意選擇。

## 4. 已知邊界情況

- 後端 graceful shutdown 期間(preStop 5s / grace 60s)scrape 短暫 DOWN,屬預期。
- 後端 pod 未 Ready 時 target DOWN,Ready 後自動 UP;HPA 擴縮自動增減 target。
- Prometheus pod 重啟 → 歷史清空(emptyDir),targets 幾十秒內恢復。
- **Grafana 無持久卷**:UI 手動改的密碼/儀表板/使用者在 pod 重建後消失;provision 的資料源與「搶票總覽」自動重建(與決策 #3/#6 一致,dev 刻意)。Grafana 重啟後 provision 掃描需幾十秒,太早查 API 會拿到空的。
- **cAdvisor 只有 pod 層級系列**:Docker Desktop 以 docker(cri-dockerd)為 runtime,kubelet 內建 cAdvisor 沒有 container handler,只輸出 pod cgroup 系列(`container`/`image` 為空值,Prometheus 存入時丟掉空標籤,查起來像「沒有 container 標籤」)。每 pod CPU 查詢用 **`container=""`**(同時匹配「無標籤」與「空字串」):本機取到 pod 層級;搬到 containerd 叢集(EKS)時同一 pod 會多出 `container="POD"` 與 `container="ticket-backend"` 系列,此過濾仍只取 pod cgroup、不會 2 倍高估。**不要**寫 `container!=""`(本機回空),也**不要**不過濾(containerd 重複計算)。
- Grafana LoadBalancer 在 Docker Desktop 實際綁 **0.0.0.0:3000**(全介面);離開可信網路改 port-forward 或改密碼。
- `/actuator/prometheus` 匿名可讀:僅有界列舉指標,無業務敏感資料。
- 前置步驟(讀購物車/預載票券)的非 CheckoutException 例外不進 `ticket_checkout_total`(仍反映在 `http_server_requests` 5xx);退票的「訂單不存在/非本人」不進 `ticket_refund_total`(安全取捨,不洩漏他人訂單)。

## 5. 已知問題 / 技術債

- **與 v0.5 rate-limit 的相依**:rate-limit 實作時**必須**把 `/actuator/prometheus` 放入排除清單(rate-limit.md 已寫),否則 scrape 被限流→429→target 抖動。
- node-exporter 發現用 `role: endpoints`,v1 Endpoints API 自 k8s 1.33 標記棄用(目前僅 log 警告、功能正常);日後改 `role: endpointslice`(ClusterRole 需加 `discovery.k8s.io/endpointslices` list/watch)。
- 改 Prometheus scrape config 需 rollout restart(無 config-reloader),會清 emptyDir 歷史;正式環境可加 reloader sidecar。
- `ticket_oversell_guard_total{type="rollback"}` 是「補償回滾總數」(含付款失敗等所有回滾路徑),語意比「超賣防護」寬,儀表板解讀時注意(description 已註明)。
- 未做:Alertmanager 告警、prometheus-adapter 自訂指標 HPA、外部 MySQL/Redis exporter、PV 持久化、雲上監控。
- Grafana 儀表板 JSON 手寫精簡版,面板單位/樣式可再調。

## 6. 測試現況

- **自動化(`mvn test`,40 passed)**:
  - `CheckoutControllerTest`(7):success/各 reason 計數與 Timer;mock metrics 拋例外結帳仍成功、業務例外仍往外拋;**真 `PrometheusMeterRegistry.scrape()` 同時含 success 與 `reason="sold_out"` 系列**(守標籤鍵一致)。
  - `CheckoutServiceTest`(4):售罄/限購路徑的 reason、`oversell_guard` 計數;**in-lock 指標例外 → 仍 SOLD_OUT、回滾只一次**。
  - `RefundServiceTest`(5):退票 success/fail 計數。
- **手動(runbook)**:actuator 安全(匿名 200 / 受保護 403 / 未暴露 403)、targets 全 UP、Grafana provision、壓測看 RPS/p99/HPA(實測 2→8 pod)、emptyDir 重啟。理由:actuator+security 整合測試需 bootable Spring context,本專案測試層為 Mockito 單元測試(決策 #18)。
