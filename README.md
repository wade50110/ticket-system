# ticket-system — 高併發搶票系統

模擬演唱會/活動搶票的高併發系統,核心目標是「**大量使用者同時搶同一張票也不超賣**」。
後端 Spring Boot 3(Java 17)、前端 React + Vite;以 **Redis 為庫存正源 + 分散式鎖 + Lua 原子扣減** 防超賣,MySQL 事後非同步回寫。容器化跑在本機 Docker Desktop Kubernetes(後端依 CPU 自動擴縮),Prometheus + Grafana 監控,Jenkins 做「push main 即上板」。

> 開發規則與技術細節的正源是 [`CLAUDE.md`](CLAUDE.md);各模組現況規格在 [`docs/`](docs/)。本檔只是入口。

## 架構一眼看

```
瀏覽器 ── http://localhost ──► [Docker Desktop k8s]
                                 nginx pod ×2 ──(serve React 靜態檔;/api 反代)──► Service ticket-backend(ClusterIP)
                                                                                      └─► Spring Boot pod ×2~10(HPA, CPU 50%)
                                                                                            ├─► MySQL 8  (docker compose, host:3307)  訂單 / 使用者 / 票券
                                                                                            └─► Redis 7  (docker compose, host:6380)  庫存正源 / 鎖 / 限購額度
                                 monitoring ns(選用):Prometheus ← /actuator/prometheus;Grafana http://localhost:3000
GitHub main ──每分鐘輪詢──► Jenkins(docker compose profile ci, http://127.0.0.1:8088)──測試 → build image(sha7)→ kubectl set image──► 上面的 Deployment
```

## 快速開始(Windows 11 / PowerShell)

前置:Docker Desktop(Settings → Kubernetes → Enable,provisioning 選 **Kubeadm**)、kubectl;開發模式另需 Java 17 與 Node 20。

```powershell
cd ticket-system
powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1                   # MySQL/Redis + k8s 前後端 → http://localhost
powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1 -Monitoring -Ci   # 再加 Grafana(:3000)與 Jenkins(:8088)
powershell -ExecutionPolicy Bypass -File scripts/stop-stack.ps1                    # 全部關掉(資料 volume 保留)
```

- 腳本冪等、重跑安全;首次會 build image(約 3~10 分鐘)。在 Claude Code 內對應 skill:`/start-ticket-system`、`/stop-ticket-system`。
- **開發模式**(前後端在 host 跑、熱重載):`start-stack.ps1 -Mode infra` 只起 MySQL/Redis,再 `..\.claude\run-backend.cmd`(或 `mvn spring-boot:run`)起後端 8099、`cd frontend; npm run dev` 起前端 5173。
- 手動逐步操作與疑難排解:[`k8s/README.md`](k8s/README.md)。

## 連接埠

| 服務 | 位址 | 說明 |
|------|------|------|
| 前端 + API(k8s) | http://localhost | nginx LoadBalancer,`/api/**` 反代到後端 |
| 後端(開發模式) | http://localhost:8099 | `server.port`,不是 8080 |
| 前端 dev server | http://localhost:5173 | Vite,`/api` proxy 到 8099 |
| MySQL | localhost:3307 | `root/root`,DB `ticketdb` |
| Redis | localhost:6380 | 庫存正源、鎖、限購額度 |
| Grafana(選用) | http://localhost:3000 | admin/admin |
| Jenkins(選用) | http://127.0.0.1:8088 | admin/admin,只綁 loopback |

## 專案結構

```
ticket-system/
├── backend/            Spring Boot 後端(README、Dockerfile、Jenkinsfile)
├── frontend/           React + Vite 前端(README、Dockerfile、nginx.conf、Jenkinsfile)
├── k8s/                前後端 manifests、monitoring/(Prometheus + Grafana)、ci/(Jenkins 部署用 RBAC)
├── ci/jenkins/         Jenkins image、JCasC、Job DSL、pipeline 共用腳本、runbook
├── scripts/            start-stack / stop-stack / ci-bootstrap / 壓測
├── docs/               各模組現況 spec、deployment/、requirements/(需求書,一版一個 folder)
├── docker-compose.yml  MySQL、Redis;profile ci = Jenkins
└── CLAUDE.md           技術棧、API、設計重點、啟動方式、開發規則
```

## 文件地圖

| 想知道 | 看 |
|--------|----|
| 技術棧、API 一覽、設計重點、進度 | [`CLAUDE.md`](CLAUDE.md) |
| 認證 / 票券 / 購物車 / 庫存與鎖 / 結帳 / 訂單與退票 的現況規格 | [`docs/auth.md`](docs/auth.md)、[`docs/ticket.md`](docs/ticket.md)、[`docs/cart.md`](docs/cart.md)、[`docs/inventory.md`](docs/inventory.md)、[`docs/checkout.md`](docs/checkout.md)、[`docs/order.md`](docs/order.md) |
| 監控(指標、埋點硬約束) | [`docs/monitoring.md`](docs/monitoring.md)、runbook [`k8s/monitoring/README.md`](k8s/monitoring/README.md) |
| 容器化 / k8s / HPA / ShedLock | [`docs/deployment/`](docs/deployment/)、runbook [`k8s/README.md`](k8s/README.md) |
| CI/CD(Jenkins 即時上板) | [`docs/deployment/ci-cd-jenkins.md`](docs/deployment/ci-cd-jenkins.md)、runbook [`ci/jenkins/README.md`](ci/jenkins/README.md) |
| 需求書(為什麼這樣做) | [`docs/requirements/`](docs/requirements/) |
| MySQL / Redis 的 Docker 維運 | [`docker-setup.md`](docker-setup.md) |
| 歷史版本規格 | `version0.1.md`、`version0.2.md`、`version0.3.md`、`version0.3-verification.md` |

## 測試

- 後端:`mvn test`(repo 沒有 mvnw;這台機器用 `.claude/run-backend.cmd` 同款的 corretto-17 + IntelliJ 內建 Maven)。共 53 顆,其中 13 顆 Redis 整合測試需要本機 Redis `localhost:6380`,沒開會整類跳過只剩 40 顆——Jenkins 內一定會跑(pipeline 自起拋棄式 Redis)。
- 前端:`cd frontend; npm test`(Vitest + React Testing Library,17 顆)。
- 每次 push main,Jenkins 都會先跑完測試才 build / 上板;測試不過不上板。
