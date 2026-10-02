# ticket-system backend(Spring Boot)

搶票系統後端:Java 17、Spring Boot 3.2.5(web / security / data-jpa / validation / actuator)、MySQL 8、Redis 7 + Redisson。核心是「**Redis 庫存正源 + 可切換分散式鎖 + Lua 原子扣減**」防超賣,MySQL 只做事後非同步回寫。

> 本檔是後端入口。規格以 [`../docs/`](../docs/) 各模組 spec 為準;技術棧、開發規則見 [`../CLAUDE.md`](../CLAUDE.md)。

## 模組(`com.example.ticket`)

| 套件 | 內容 | spec |
|------|------|------|
| `auth/` | User / Role、`JwtService`、`JwtAuthFilter`、`UserService`(BCrypt)、`AuthController` | [`docs/auth.md`](../docs/auth.md) |
| `ticket/` | 票券 CRUD(admin)、顧客查詢(上架時間窗)、Redis 雙寫 | [`docs/ticket.md`](../docs/ticket.md) |
| `cart/` | 購物車(庫存檢查不預扣、限購預檢) | [`docs/cart.md`](../docs/cart.md) |
| `stock/` | `StockRedisRepository`(Lua DECRBY)、`QuotaRedisRepository`(限購額度)、啟動載入與對帳 | [`docs/inventory.md`](../docs/inventory.md) |
| `lock/` | `DistributedLock` 介面;`RedissonDistributedLock`(預設)/ `RedisTemplateDistributedLock`(手刻 SET NX PX + Lua) | [`docs/inventory.md`](../docs/inventory.md) |
| `checkout/` | 結帳主流程、`StockSyncListener`(@Async 回寫 DB)、`stock_sync_failed` 補償、退票庫存回補 | [`docs/checkout.md`](../docs/checkout.md) |
| `order/` | 訂單、明細快照、退票(`RefundService`) | [`docs/order.md`](../docs/order.md) |
| `payment/` | `PaymentService` 介面 + `MockPaymentService`(必成功) | [`docs/checkout.md`](../docs/checkout.md) |
| `metrics/` | `TicketMetrics`:結帳成功/失敗、超賣防護、退票、結帳延遲(Micrometer) | [`docs/monitoring.md`](../docs/monitoring.md) |
| `config/` | `SecurityConfig`(Security + CORS)、`GlobalExceptionHandler`、`SchedulerConfig`(ShedLock) | [`docs/auth.md`](../docs/auth.md) |

## API 一覽

| Method | Path | 權限 | 說明 |
|--------|------|------|------|
| POST | `/api/auth/register` | 公開 | 註冊(`username`、`email`、`password` 必填) |
| POST | `/api/auth/login` | 公開 | 登入,回 JWT |
| GET | `/api/auth/me` | 已登入 | 目前使用者 |
| GET | `/api/health` | 公開 | 健康檢查(k8s probe 也用這個) |
| GET | `/api/tickets`、`/api/tickets/{id}` | 已登入 | 票券列表 / 單筆(僅上架時間窗內) |
| GET/POST/PUT/DELETE | `/api/admin/tickets...` | ADMIN | 票券 CRUD(含列表、單筆) |
| POST | `/api/admin/quota/reconcile` | ADMIN | 限購額度對帳 |
| GET/POST/PATCH/DELETE | `/api/cart...` | CUSTOMER | 購物車 |
| POST | `/api/checkout` | CUSTOMER | 結帳當前購物車(搶購核心) |
| GET | `/api/orders`、`/api/orders/{id}` | CUSTOMER | 自己的訂單 / 明細 |
| POST | `/api/orders/{id}/refund` | CUSTOMER | 退票(僅 PAID、本人、整筆) |
| POST | `/api/admin/stock-sync/retry` | ADMIN | 手動觸發庫存回寫重試 |
| GET | `/actuator/prometheus` | 公開 | Prometheus 指標(其餘 actuator 端點未暴露,匿名 403) |

請求/回應格式與錯誤碼以各模組 spec 為準。

## 設定(`src/main/resources/application.yml`)

全部可用環境變數覆蓋(k8s 由 ConfigMap/Secret `envFrom` 注入):

| 變數 | 預設 | 說明 |
|------|------|------|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3307` / `ticketdb` | MySQL(`ddl-auto: update` 自動建表) |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | 開發用 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | 庫存正源、鎖、限購額度、ShedLock |
| `JWT_SECRET` | 開發用預設值 | HS256,≥ 32 bytes;正式環境必換 |
| `server.port` | `8099` | 不是 8080 |
| `ticket.lock.type` | `redisson` | 可切 `manual`(手刻 Lua 版) |

## 本機啟動(開發模式)

```powershell
# 1) MySQL / Redis
powershell -ExecutionPolicy Bypass -File ..\scripts\start-stack.ps1 -Mode infra     # 或 docker compose up -d
# 2) 後端(port 8099)
..\..\.claude\run-backend.cmd      # 這台機器沒有 mvnw、mvn 不在 PATH:此檔用 corretto-17 + IntelliJ 內建 Maven
#   有 mvn 的機器:mvn spring-boot:run
# 3) 驗證
curl.exe http://localhost:8099/api/health
```

完整系統(k8s + nginx + HPA)請用 `..\scripts\start-stack.ps1`,見 [`../README.md`](../README.md)。

## 測試

```powershell
mvn test        # 同上,這台機器以 IntelliJ 內建 Maven + JAVA_HOME=corretto-17 執行
```

- 共 **53** 顆:Mockito 單元測試 + `QuotaRedisRepositoryRedisTest`(**13** 顆,需本機 Redis `localhost:6380`,用 db 15;Redis 沒開會整類跳過,只剩 40 顆)。
- Jenkins 的 `ticket-backend` job 每次 push 都跑全套,且自起拋棄式 Redis、檢查該整合測試真的執行(`tests>0`),不會被靜默跳過。
- 搶購/結帳的併發實測(多個結帳同時打、驗證不超賣)見 `../version0.3.md`〈操作流程驗證〉路徑 C。
- 規則:完成任何修改都要補測試並實際跑過;測試失敗不得改弱(見 `../CLAUDE.md`)。

## 容器化與部署

- `Dockerfile`:多階段(`maven:3.9-eclipse-temurin-17` build,跳過測試 → `eclipse-temurin:17-jre` 執行),`exec java` 讓 JVM 成為 PID 1 以接收 SIGTERM(graceful shutdown)。
- image:Jenkins 以 `ticket-backend:<git sha7>` 建、rollout 成功後同步打 `:local`;手動 `docker build -t ticket-backend:local .` 僅備援。
- k8s:`../k8s/backend.yaml`(ConfigMap/Secret/Deployment/Service/HPA 2~10,CPU 50%);`Jenkinsfile` 為 CI/CD pipeline(Checkout → Preflight → Test → Build Image → Deploy → Cleanup),說明見 [`../docs/deployment/ci-cd-jenkins.md`](../docs/deployment/ci-cd-jenkins.md)。

## 設計重點(改這些模組前先讀)

1. **Redis 是庫存正源,DB 是事後紀錄**:結帳只信 Redis 扣減結果;DB `tickets.stock` 由事件非同步回寫;啟動時 key 已存在不可覆蓋。
2. **防超賣靠 Lua 原子扣減**:`GET → 判斷 → DECRBY` 一支腳本完成;不足時 `INCRBY` 回滾。限購檢查與扣減同一支 Lua。
3. **分散式鎖可切換**、**多票結帳依 ticketId 升冪加鎖**防死鎖。
4. **訂單快照**:`order_items` 存下單當下的單價與票名。
5. **庫存回寫補償**:`@Retryable` 3 次失敗 → `stock_sync_failed` 表 → `@Scheduled` 每分鐘重試(多 pod 以 ShedLock 只跑一份)。
6. **付款是 Mock**,換真金流只換 bean。
7. **指標埋點硬約束**:結帳鎖臨界區有 `catch(RuntimeException) → rollback`,埋點必須 exception-safe、純記憶體、預註冊(見 `docs/monitoring.md`)。

## 安全性

- 密碼 BCrypt;JWT HS256、`STATELESS`,受保護 API 帶 `Authorization: Bearer <token>`;CORS 允許 `http://localhost:5173`(開發)。
- `root/root`、預設 `JWT_SECRET`、k8s Secret 內的明文都只給本機開發;正式環境以環境變數 / 外部 Secret 管理覆蓋。
