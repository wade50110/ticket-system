# k8s 監控(Prometheus + Grafana)runbook

v0.6 監控的部署與驗證步驟。設計與決策見 [`../../docs/requirements/v0.6/monitoring.md`](../../docs/requirements/v0.6/monitoring.md),現況 spec 見 [`../../docs/monitoring.md`](../../docs/monitoring.md)。

架構:後端 pod 暴露 `/actuator/prometheus` → Prometheus(依 pod 註解自動發現)定時抓取 + 抓 cAdvisor / kube-state-metrics / node-exporter → Grafana 出「搶票總覽」儀表板。全部放 `monitoring` namespace,**精簡版手寫 manifests、不用 Helm/Operator**。

| 元件 | 檔案 | 作用 |
|------|------|------|
| Namespace | `00-namespace.yaml` | `monitoring`(帶 PSA privileged 標籤,node-exporter 需要) |
| Prometheus | `10-prometheus.yaml` | 抓取 + 存時序(emptyDir 不持久、15s、retention 3h)+ 最小權限 RBAC |
| node-exporter | `20-node-exporter.yaml` | 節點 OS 資源(DaemonSet,hostNetwork) |
| kube-state-metrics | `30-kube-state-metrics.yaml` | k8s 物件狀態(Deployment 副本數、HPA 現值),`--resources` 限縮 + 最小 RBAC |
| Grafana | `40-grafana.yaml` | 儀表板,自動 provision 資料源 + 「搶票總覽」;LoadBalancer → localhost:3000 |

## 前置

1. 依 [`../README.md`](../README.md) 把 app 跑起來(docker compose 的 MySQL/Redis、build image、apply backend/frontend、metrics-server)。
2. **後端 image 必須是含 v0.6 指標程式的版本**:改過後端要重 build 並 `kubectl rollout restart deployment ticket-backend`,否則 `/actuator/prometheus` 是 404。
3. `k8s/backend.yaml` 的 pod template 已含 `prometheus.io/*` 註解,apply 過即可。

## 部署

```powershell
cd ticket-system
kubectl apply -f k8s/monitoring/          # 依檔名順序:namespace → prometheus → exporters → grafana
kubectl get pods -n monitoring -w         # 等 prometheus / node-exporter / kube-state-metrics / grafana 全 Running
```

第一次會從網路拉 image(prom/prometheus、prom/node-exporter、registry.k8s.io/kube-state-metrics、grafana/grafana),約 1–3 分鐘。

> **改了 Prometheus scrape config(ConfigMap)之後**:沒有 config-reloader、也刻意不開 `--web.enable-lifecycle`(它會暴露未認證的 `/-/quit`),所以要 `kubectl rollout restart -n monitoring deployment/prometheus`。emptyDir 會清掉歷史,dev 可接受。

### 只驗 manifests 語法(不用叢集)

```powershell
kubectl apply --dry-run=client -f k8s/monitoring/
# scrape config 語法:把 ConfigMap 的 prometheus.yml 抽出來給 promtool 檢查
kubectl exec -n monitoring deploy/prometheus -- promtool check config /etc/prometheus/prometheus.yml
```

## 驗證(對應需求書 AC-5 ~ AC-8;含 AC-2 的手動 T-3)

### 1. 後端指標端點與安全設定(AC-1 / AC-2,手動 T-3)

用叢集內的臨時 curl pod 打後端 Service:

```powershell
kubectl run curl --image=curlimages/curl:8.8.0 --restart=Never --rm -i --command -- sh -c "
  B=http://ticket-backend.default:8099 ;
  echo prometheus: ; curl -s -o /dev/null -w '%{http_code}\n' \$B/actuator/prometheus ;
  echo orders-no-token: ; curl -s -o /dev/null -w '%{http_code}\n' \$B/api/orders ;
  echo actuator-env: ; curl -s -o /dev/null -w '%{http_code}\n' \$B/actuator/env ;
  echo sample: ; curl -s \$B/actuator/prometheus | grep -E '^(ticket_checkout_total|http_server_requests_seconds_bucket)' | head -5"
```

預期:`/actuator/prometheus` **200**(匿名可讀)、`/api/orders` 未帶 token **403**、`/actuator/env` **403**(未暴露且未放行——`anyRequest().authenticated()` 對匿名回 403,不是 404);sample 要看到 **五條** `ticket_checkout_total{...}`(success/reason=none + 4 個 fail reason)與帶 `le=` 的 `http_server_requests_seconds_bucket`(有 histogram)。

### 2. Prometheus targets 全 UP(AC-5)

```powershell
kubectl port-forward -n monitoring svc/prometheus 9090:9090
```

開 http://localhost:9090/targets ,應看到:`ticket-backend`(幾個 backend pod 就幾個,全 UP)、`kubernetes-cadvisor`、`kube-state-metrics`、`node-exporter`、`prometheus` 皆 UP。

- `ticket-backend` DOWN 且 404 → 後端 image 沒含指標程式,重 build + rollout restart。
- `kubernetes-cadvisor` 403 → Prometheus ClusterRole 缺 `nodes/proxy`;TLS 錯誤 → 確認 `tls_config.ca_file` 指到 SA 掛載的 `ca.crt`(走 apiserver proxy 不需 skip verify)。
- `kube-state-metrics` 起不來 → `kubectl logs -n monitoring deploy/kube-state-metrics`;通常是 `--resources` 列了 ClusterRole 沒給的資源,兩邊要同步。
- 記憶體觀察:Prometheus 查 `prometheus_tsdb_head_series`(2 個 backend pod 約 1.7 萬系列,10 pod 約 4 萬,512Mi limit 夠)。

### 3. Grafana 與儀表板(AC-6)

```powershell
kubectl get svc -n monitoring grafana      # EXTERNAL-IP 應為 localhost
```

開 **http://localhost:3000**,帳密 `admin / admin`(dev)。左側 Dashboards 應已有「**搶票總覽 (ticket-system)**」(自動 provision),資料源 Prometheus 已設好。剛部署時面板可能 No data,有流量後即出現;Grafana 重啟後 provision 掃描需幾十秒,太早查 API 會拿到空的。

> ⚠️ Docker Desktop 的 LoadBalancer 實際綁在 **0.0.0.0:3000**(全介面),LAN 內只要防火牆放行就能用預設帳密登入。離開可信網路時改用 `kubectl port-forward -n monitoring svc/grafana 3000:3000` 或改掉 `grafana-admin` Secret 的密碼。前端 :80 是同樣情況。

### 4. 壓測看指標與 HPA(AC-7)

**前置(必要)**:壓測用登入端點,靠 BCrypt 比對推高 CPU——但 `UserService` 在**帳號不存在時會先拋錯、根本不跑 BCrypt**。所以先建一個測試帳號(`email` 為必填):

```powershell
curl.exe -s -X POST http://localhost/api/auth/register -H "Content-Type: application/json" `
  -d '{\"username\":\"loadtest\",\"email\":\"loadtest@example.com\",\"password\":\"LoadTest123!\"}'
```

一個視窗盯儀表板,另一個打壓測(用**存在的帳號 + 錯誤密碼**,每次都跑 BCrypt、不會發 token):

```powershell
kubectl run load --image=williamyeh/hey --restart=Never -- `
  -z 2m -c 40 -m POST -H "Content-Type: application/json" `
  -d '{\"username\":\"loadtest\",\"password\":\"wrong-password\"}' `
  http://ticket-backend.default:8099/api/auth/login
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/load --timeout=200s ; kubectl logs load | Select-String "Requests/sec","Average","\[400\]"
kubectl delete pod load
```

> JSON 引號:上面是 **PowerShell 5.1** 寫法(`\"`);PowerShell 7.3+ / Git Bash 直接用 `-d '{"username":"loadtest","password":"wrong-password"}'` 即可。

**判讀是否真的打到 BCrypt**:hey 摘要應是 **每秒幾十次、平均延遲上百 ms~數秒、狀態碼 400**(密碼錯)。若看到 **每秒數千次、毫秒級延遲**,代表沒跑 BCrypt(帳號不存在或 JSON 被 shell 吃掉變非法 JSON),HPA 不會動,別誤判成監控壞掉。

儀表板應看到:**RPS 上升、p99 變化、每 pod CPU 飆、後端副本數由 2 隨 HPA 上升**(實測 2→8);壓測結束約 5 分鐘後 HPA 縮回 2。若有實際結帳流量,結帳結果/超賣防護面板才會有數據。

### 5. emptyDir 不持久(AC-8)

```powershell
kubectl delete pod -n monitoring -l app=prometheus     # Deployment 會自動重建
```

重建後 Prometheus 歷史清空(預期),targets 幾十秒內恢復 UP。Grafana 同樣無持久卷:UI 手動改的密碼/儀表板會在 pod 重建後消失,provision 的內容自動重建。

## 清理

```powershell
kubectl delete -f k8s/monitoring/          # 只拆監控,不動 app
```

## 疑難排解

- **Grafana 登入不了 / 密碼不對**:Secret `grafana-admin` 是 admin/admin;若之前在 UI 改過密碼(存在容器層),砍 pod 重建即回預設。
- **儀表板有但 No data**:先看 Prometheus targets 是否 UP;再到 Grafana Explore 直接查 `ticket_checkout_total` 或 `up`。
- **每 pod CPU 面板**:查詢用 `container=""` 只取 pod 層級 cgroup——本機(docker runtime)cAdvisor 只有這種系列;搬到 containerd(EKS)會多出 `container="POD"`/容器系列,此過濾仍正確、不會 2 倍高估。**不要**改成 `container!=""`(本機會回空)。
- **`/actuator/prometheus` 被 429**:v0.5 rate-limit 實作後必須把該路徑放入排除清單(rate-limit.md 已寫);scrape 15s 本身遠低於限額。
- **後端 graceful shutdown 期間 target 短暫 DOWN**:preStop / 排空期間屬預期,幾十秒後恢復。
- **log 出現 `v1 Endpoints is deprecated`**:node-exporter 發現用 `role: endpoints`,k8s 1.33+ 只是警告、功能正常;日後改 `role: endpointslice`。
