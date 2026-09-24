# v0.6 需求總覽

> 這一版總共改什麼的入口。想看細項,點對應的需求書。
> 建立日期:2026-09-24

## 這版的主題

**可觀測性(監控):把 Prometheus + Grafana 部署進 k8s,讓搶票系統「看得到」。** 後端暴露指標端點,Prometheus 定時抓取並存時序資料,Grafana 出儀表板。核心價值:高併發搶票尖峰時,能即時看到請求量、延遲、結帳成功/失敗率、超賣防護是否觸發、以及 HPA 把 pod 從 2 擴到幾個。

與既有 metrics-server 的關係:**互補不重複**。metrics-server 只給 HPA 當前 CPU/mem;Prometheus + Grafana 提供歷史、儀表板、豐富的應用與業務指標。

## 功能一覽

| # | 功能 | 需求書 | 狀態 | 一句話 |
|---|------|--------|------|--------|
| 1 | 監控(Prometheus + Grafana) | [monitoring.md](monitoring.md)([todo](monitoring-todo.md)) | ✅已完成(2026-09-24) | 後端暴露指標 + 精簡版 Prometheus/Grafana 進 k8s,含搶票儀表板;實測 HPA 2→8 可視、全 AC 通過。現況見 [`../../monitoring.md`](../../monitoring.md) |

開發順序:單一功能。內部順序:先做後端指標暴露(可單元測試)→ 再做 k8s 監控 manifests(手動驗證)。

**跨版本相依**:與 v0.5 [rate-limit](../v0.5/rate-limit.md) 有一個排除清單相依——`/actuator/prometheus` 必須在 rate-limit 排除清單內(否則 Prometheus scrape 會被限流)。rate-limit.md 的排除清單已同步補上;若 rate-limit 先實作,務必確認此點。

## 這版改到的主要範圍

- **後端**:新增 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 依賴;`application.yml` 開 actuator prometheus 端點;`SecurityConfig` 放行 `/actuator/prometheus`;`CheckoutService`/`RefundService` 加自訂業務指標(結帳成功/失敗/超賣防護/延遲)。
- **k8s**:新增 `k8s/monitoring/`(Prometheus + Grafana + node-exporter + kube-state-metrics 的手寫 manifests,含 RBAC);`k8s/backend.yaml` 的 pod 加 prometheus scrape 註解。
- **資料模型**:無變更。
- **HPA**:不變(維持 CPU-based)。

## 範圍外(留待後續)

- Alertmanager 告警規則。
- prometheus-adapter + HPA 改吃自訂指標(如搶購 RPS)。
- 外部 MySQL/Redis 的 exporter。
- Prometheus 資料 PV 持久化。
- 雲上(EKS)監控。
