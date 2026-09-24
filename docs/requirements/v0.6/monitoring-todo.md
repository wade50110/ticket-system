# Todo:監控(Prometheus + Grafana)

> 對應需求書:monitoring.md
> 完成定義:實作完成 + 對應測試實際跑過且通過(k8s 部分為手動驗證)

## A. 後端指標暴露(可自動測)✅ 完成(2026-09-24,全套 38 測試通過)

- [x] 1. `pom.xml` 加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`(F-1)
- [x] 2. `application.yml`:`management.endpoints.web.exposure.include=prometheus`、`http.server.requests` 開 percentiles-histogram、metrics common tags(F-2, F-4）
- [x] 3. `CheckoutException` 加 typed `reason` enum 欄位 + 各 throw 點帶 reason（F-8, AC-3）
- [x] 4. `SecurityConfig`:放行 prometheus 端點,置於 `anyRequest` 前(用 `EndpointRequest.to("prometheus")` 字串 id 版,較穩)（F-4, AC-2）
- [x] 5. `CheckoutService` 臨界區加 `ticket_oversell_guard_total`——集中到 `TicketMetrics` 元件,exception-safe、建構時預註冊持有參照、純記憶體（F-3, F-5~F-7, AC-3）
- [x] 6. `CheckoutController` 層加 `ticket_checkout_total{result,reason}` + `ticket_checkout_duration`(鎖外量,reason 來自 CheckoutException,呼叫端再包 safe)（F-3, F-7, AC-3）
- [x] 7. `RefundService` 的 `markRefunded` 成功後加 `ticket_refund_total{result}`（F-3, F-7）

## B. 後端測試（IntelliJ 內建 mvn,見建置記憶)

- [x] 8. T-1 指標增量測試:`CheckoutControllerTest`(success/各 reason)+ `CheckoutServiceTest`(oversell_guard)+ `RefundServiceTest`(refund success/fail),用 `SimpleMeterRegistry` 驗增量與標籤（AC-3）
- [x] 9. T-2 埋點例外迴歸:`CheckoutControllerTest.checkout_metricRecordingThrows_doesNotBreakCheckout`——mock TicketMetrics 拋例外,結帳仍成功;另驗業務例外仍正常往外拋（AC-4, F-5)★最關鍵
- [~] 10. T-3 安全設定 → **改為手動驗證(併入 C-16 runbook)**。理由:actuator 端點的安全整合測試需 bootable Spring context,而本專案測試層為 Mockito 單元測試、無 DB/Redis context 基礎設施(見決策 #18);以 curl 手動驗 `/actuator/prometheus` 匿名 200、受保護 API 401（AC-2）
- [x] 11. T-4 全套測試通過(38 passed;既有結帳併發測試含在內,確認未引入 race;`QuotaRedisRepositoryRedisTest` 因 Redis 未開自動跳過)

## C. k8s 監控 manifests（手動驗證)✅ 完成(2026-09-24,本機 k8s 實測全過)

- [x] 12. `k8s/backend.yaml` pod template 加 prometheus scrape 註解（F-14）
- [x] 13. `k8s/monitoring/10-prometheus.yaml`:Deployment(emptyDir, scrape 15s, retention 3h, resources) + ConfigMap(scrape config, insecure_skip_verify) + Service(ClusterIP) + ServiceAccount/ClusterRole/Binding（F-10, F-12, F-15）
- [x] 14. `20-node-exporter.yaml`(DaemonSet + headless Service) + `30-kube-state-metrics.yaml`(Deployment + Service + 自己一組 RBAC)（F-11, F-12）
- [x] 15. `40-grafana.yaml`:Deployment + Secret(admin/admin) + Service(LoadBalancer:3000) + ConfigMap(datasource + 「搶票總覽」dashboard JSON provision);每 pod CPU 查詢依決策 #19 修正（F-13, F-16）
- [x] 16. `k8s/monitoring/README.md` runbook + 手動驗證:AC-2 curl(200/403/403)、targets 5 job 全 UP、Grafana provision OK、壓測 HPA 2→8 + RPS/p99 可見、emptyDir 重啟恢復（M-1, AC-2, AC-5~8）

## D. 收尾 ✅

- [x] 17. 新增 `docs/monitoring.md` 現況 spec;CLAUDE.md 文件表與方式二啟動步驟、k8s/README.md 加監控指引
- [x] 18. 需求書狀態改「已完成」+ §11 驗收紀錄 + 決策 #19/#20;同步 v0.6 `README.md` 與頂層目錄狀態
