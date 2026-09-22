# CLAUDE.md — ticket-system

> 本檔案描述 ticket-system 的技術棧與細節。根目錄 [`../CLAUDE.md`](../CLAUDE.md) 的開發規則同樣適用(開發後要寫測試、一律用繁體中文、有疑問先問、新功能先寫需求書)。

## 一、專案簡介

模擬演唱會/活動的**高併發搶票系統**,核心價值在於「**大量使用者同時搶同一張票也不超賣**」。

流程:使用者註冊/登入(JWT)→ 瀏覽票券 → 加入購物車 → 結帳(搶購核心)→ 查詢訂單。管理員可上架、修改、下架票券。

防超賣靠三件事:**Redis 為庫存正源** + **可切換的分散式鎖** + **Lua 腳本原子扣減**;MySQL 只做事後非同步回寫,不是即時正源。

---

## 二、技術棧

### 後端 `backend/`(Maven,artifact 版本 0.3.0)

| 項目 | 技術 / 版本 |
|------|-------------|
| 語言 | Java 17 |
| 框架 | Spring Boot 3.2.5(web / security / data-jpa / validation) |
| 安全 | Spring Security 6 + JWT(jjwt 0.12.5,HS256) |
| 資料庫 | MySQL 8 + Spring Data JPA(`ddl-auto: update` 自動建表) |
| 快取 / 分散式鎖 | Redis 7 + Redisson 3.27.2(可切手刻 Lua 版) |
| 非同步 / 重試 | spring-retry + spring-aspects(`@Async` / `@Retryable` / `@Scheduled`) |
| 輔助 | Lombok |
| 測試 | spring-boot-starter-test、spring-security-test |

### 前端 `frontend/`(npm)

| 項目 | 技術 / 版本 |
|------|-------------|
| 框架 | React 18.3 |
| 建置 | Vite 5.4 |
| 路由 | React Router 6.26 |
| Token 儲存 | `localStorage`(key: `ticket_token`) |

### 開發基礎設施

- **Docker Compose**(`docker-compose.yml`)一鍵起 MySQL 8 + Redis 7。
- 後端與前端目前都在 host 本機跑,只有 DB / Redis 在容器內。

---

## 三、連接埠與連線設定(重要,實際值)

| 服務 | Host Port | 說明 |
|------|-----------|------|
| 後端 Spring Boot | **8099** | `server.port`(注意:不是常見的 8080) |
| 前端 Vite dev server | **5173** | `npm run dev` |
| MySQL(容器) | **3307** → 3306 | `jdbc:mysql://localhost:3307/ticketdb`,帳密 `root/root` |
| Redis(容器) | **6380** → 6379 | `application.yml` 預設 `REDIS_PORT:6380`,對齊 `docker-compose.yml` 的 `6380:6379` |

> ⚠️ 埠號注意事項:
> - 後端固定 **8099**,前端呼叫 API / vite proxy 都要對到 8099。
> - Redis 實際用 **6380**(以 `application.yml` 與 `docker-compose.yml` 為準);`docker-setup.md` 內文有幾處寫 6379 屬舊值,以程式設定為準。
> - 敏感值(`JWT_SECRET`、DB 密碼)正式環境一律用環境變數覆蓋,不進版控。

---

## 四、目前實作進度

實際程式碼已實作到 **v0.3**(結帳 / 分散式鎖 / 訂單皆有實體程式,非僅規劃):

- ✅ v0.1:註冊、登入、JWT、`/api/auth/me`、健康檢查
- ✅ v0.2:票券 CRUD(admin)、商城瀏覽、購物車
- ✅ v0.3:Redis 庫存正源、可切換分散式鎖、Lua 原子扣減結帳、Mock 付款、訂單/明細查詢、庫存回寫 DB 的補償機制
- 🔨 v0.4(進行中):✅ 🅐 訂單退票(前後端含測試皆完成);❌ 🅑 票券圖片、🅒 UI 改版、🅓 搶票排隊 尚未開始
- ❌ v0.5+(尚未做):真實金流、防黃牛限購、Docker 化後端、Jenkins/AWS 部署

各版本詳細規格見 `version0.1.md` / `version0.2.md` / `version0.3.md`;**各模組現況規格見 `docs/`(見第十一節,改功能前先讀對應模組的 spec)**。

---

## 五、後端專案結構(`com.example.ticket`)

```
backend/src/main/java/com/example/ticket/
├── TicketApplication.java          # 進入點(@EnableAsync/@EnableRetry/@EnableScheduling)
├── HealthController.java           # GET /api/health
├── auth/                           # 認證:User, Role, JwtService, JwtAuthFilter,
│                                   #      UserService(BCrypt), AuthController, dto/
├── ticket/                         # 票券:Ticket, TicketService, TicketController(顧客查詢),
│                                   #      AdminTicketController(admin CRUD), dto/
├── cart/                           # 購物車:CartItem, CartService, CartController, dto/
├── lock/                           # 分散式鎖:DistributedLock/LockHandle 介面,
│                                   #      RedissonDistributedLock(預設) / RedisTemplateDistributedLock(手刻 Lua),
│                                   #      LockConfig + LockProperties(@ConditionalOnProperty 切換)
├── stock/                          # Redis 庫存:StockRedisRepository(Lua DECRBY), StockBootstrap(啟動載入)
├── payment/                        # 付款:PaymentService 介面 + MockPaymentService(必成功) + PaymentResult
├── order/                          # 訂單:Order, OrderItem, OrderStatus, OrderService, OrderController,
│                                   #      RefundService/RefundException(v0.4 退票), dto/
├── checkout/                       # 結帳核心:CheckoutService(主流程), CheckoutController,
│                                   #      StockChangedEvent, StockSyncListener(@Async 回寫 DB),
│                                   #      StockSyncWorker, StockSyncFailed(+Repository)(補償表),
│                                   #      StockRestoredEvent/StockRestoreWorker(v0.4 退票庫存回補),
│                                   #      AdminStockSyncController(手動重試), CheckoutException
└── config/                         # SecurityConfig(Security+CORS), GlobalExceptionHandler
```

前端結構:

```
frontend/src/
├── App.jsx                         # 路由 + 依角色守衛(PrivateRoute)
├── components/{AppLayout, OrderStatusBadge}.jsx
├── api/{http, auth, tickets, cart, orders}.js
└── pages/{Login, Register, Shop, Cart, Orders, OrderDetail}.jsx + admin/AdminTickets.jsx
```

---

## 六、角色與權限

| 角色 | 登入後首頁 | 可存取 |
|------|-----------|--------|
| `ADMIN` | `/admin/tickets` | 票券上下架與管理 |
| `CUSTOMER` | `/shop` | 商城、購物車、結帳、訂單查詢 |

後端 Session 為 `STATELESS`,受保護 API 需帶 `Authorization: Bearer <token>`。

---

## 七、主要 API

| Method | Path | 權限 | 說明 |
|--------|------|------|------|
| POST | `/api/auth/register` | 公開 | 註冊(BCrypt) |
| POST | `/api/auth/login` | 公開 | 登入,回 JWT |
| GET | `/api/auth/me` | 已登入 | 取得目前使用者 |
| GET | `/api/health` | 公開 | 健康檢查 |
| GET | `/api/tickets` | 已登入 | 票券列表(僅上架時間窗內) |
| POST/PUT/DELETE | `/api/admin/tickets...` | ADMIN | 票券 CRUD(Redis 雙寫) |
| GET/POST/PUT/DELETE | `/api/cart...` | CUSTOMER | 購物車操作 |
| POST | `/api/checkout` | CUSTOMER | 結帳當前購物車(搶購核心) |
| GET | `/api/orders`、`/api/orders/{id}` | CUSTOMER | 查自己的訂單/明細 |
| POST | `/api/orders/{id}/refund` | CUSTOMER | 退票(僅 PAID、僅本人、整筆退) |
| POST | `/api/admin/stock-sync/retry` | ADMIN | 手動觸發庫存回寫重試 |

---

## 八、核心設計重點(改動這些模組前務必理解)

1. **Redis 是庫存正源,DB 是事後紀錄**:結帳只信 Redis 扣減結果;DB `tickets.stock` 由 `@Async` 事件非同步回寫,允許短暫落後。重啟時 key 不存在才從 DB 載入,**已存在不可覆蓋**。
2. **防超賣靠 Lua 原子扣減**(`stock/StockRedisRepository`):`GET → 判斷 → DECRBY` 由單一 Lua 腳本原子完成;庫存不足回滾用 `INCRBY`。
3. **分散式鎖可切換**:`ticket.lock.type = redisson`(預設)或 `manual`(手刻 `SET NX PX` + Lua 釋放);兩者實作同一 `DistributedLock` 介面,業務層不變。
4. **多票結帳防死鎖**:所有 `ticketId` **升冪排序後依序加鎖**,全體顧客用同一順序即不會死鎖。
5. **訂單金額/名稱快照**:`order_items` 存下單當下的 `unit_price` / `ticket_name`,事後票券改名或改價不影響歷史訂單。
6. **庫存回寫補償**:DB 回寫 `@Retryable` 3 次仍失敗 → 寫 `stock_sync_failed` 表,`@Scheduled` 每分鐘掃描重試;失敗不影響使用者(Redis 才是正源)。
7. **付款是 Mock**:`MockPaymentService` 必定成功;未來換真實金流只換 bean 實作,業務層不動。

---

## 九、本地啟動步驟(Windows / PowerShell)

兩種啟動方式:**方式一** 開發用(前後端在 host 直接跑,改一行馬上看到),**方式二** 容器化 + k8s(貼近正式部署、可 demo HPA autoscale)。

### 方式一:host 本機跑(開發預設)

```powershell
# 1) 起 MySQL + Redis(在 ticket-system/ 目錄)
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
docker compose up -d
docker ps                       # 確認 ticket-mysql、ticket-redis 都 Up

# 2) 起後端(port 8099)
cd .\backend
.\mvnw spring-boot:run

# 3) 起前端(port 5173)
cd ..\frontend
npm install
npm run dev
```

DB / Redis 更多操作與疑難排解見 [`docker-setup.md`](docker-setup.md)。

### 方式二:容器化 + k8s(本機 Docker Desktop)

前後端容器化跑在 k8s、Nginx 在前(serve 前端 + 反代 `/api`)、後端依 CPU 自動擴縮 pod;**MySQL/Redis 仍用 docker compose 留在 k8s 外**(方式一的 DB/Redis 直接沿用)。

```powershell
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
# 0) 起 DB/Redis(同方式一,留在 k8s 外)
docker compose up -d
# 1) Docker Desktop → Settings → Kubernetes → Enable(provisioning 選 Kubeadm,不要 kind)
# 2) build image
docker build -t ticket-backend:local ./backend
docker build -t ticket-frontend:local ./frontend
# 3) apply(HPA 需先裝 metrics-server,見下方 runbook)
kubectl apply -f k8s/backend.yaml
kubectl apply -f k8s/frontend.yaml
# 開 http://localhost 就是完整系統
```

> ⚠️ **改程式碼後重新部署**:image tag 固定為 `:local` 且 `imagePullPolicy: IfNotPresent`,所以「重 build image + 重 apply」**不會**讓運行中的 pod 換版(Deployment spec 沒變不觸發 rollout,舊 pod 繼續跑舊 code)。重 build 後要手動觸發:`kubectl rollout restart deployment ticket-backend`(或 `ticket-frontend`)。

**完整步驟**(metrics-server 安裝、壓測驗證 HPA 開關 pod、`host.docker.internal` 連 host 疑難排解)見 [`k8s/README.md`](k8s/README.md);架構設計見 [`docs/deployment/architecture.md`](docs/deployment/architecture.md)。

---

## 十、測試(對應根目錄開發規則第 1 條)

- 後端測試指令:
  ```powershell
  cd C:\Users\tw24301\Desktop\claudeTest\ticket-system\backend
  .\mvnw test
  ```
- 完成任何後端功能/修改後要補對應測試(JUnit + Spring Boot Test),並實際跑過 `mvnw test` 確認通過。
- 搶購/結帳這類併發邏輯,除了單元測試,建議照 `version0.3.md`〈操作流程驗證〉的路徑 C(同時發多個結帳請求,驗證不超賣)實測。

- 前端測試指令(Vitest + React Testing Library,v0.4 導入):
  ```powershell
  cd C:\Users\tw24301\Desktop\claudeTest\ticket-system\frontend
  npm test              # = vitest run,跑一次
  npm run test:watch    # 監看模式
  npm run build         # 順便確認可以建置
  ```
- 測試檔與被測檔放同一層,命名 `*.test.js` / `*.test.jsx`(例:`src/pages/Orders.test.jsx`)。
- 設定位置:`vite.config.js` 的 `test` 區塊(`environment: 'jsdom'`、`globals: true`),共用設定在 `src/test/setup.js`。
- 前端測試一律 mock API 模組(`vi.mock('../api/xxx.js')`),不打真實後端;重點測「使用者看得到的行為」:按鈕出現條件、確認對話框、成功後畫面更新、錯誤訊息顯示。
- 完成任何前端功能/修改後要補對應測試,並實際跑過 `npm test` 確認通過。

---

## 十一、相關文件

### 需求書(`docs/requirements/`,新功能開發前先寫)

- **一個版本一個 folder**:`docs/requirements/<版本>/`(例:[`docs/requirements/v0.5/`](docs/requirements/v0.5/)),folder 內 `README.md` 是「這版總共改什麼」的總覽,細項需求書為 `<功能代號>.md`(去版本前綴)。先看總覽,想看細項再點進去。
- 每個功能一份需求書,決策在定稿階段做完,問答記入需求書內的「決策紀錄」章節;**經確認「已定稿」後才開始開發**。
- 開發時從需求書的驗收條件拆出 todo 檔(`<功能代號>-todo.md`)放同 folder,逐項完成並即時更新。
- 完整流程與模板見 [`../.claude/skills/write-requirements.md`](../.claude/skills/write-requirements.md)。
- 功能完成後需求書轉為歷史紀錄,現況一律以 `docs/` 模組 spec 為準。

### 模組現況 spec(`docs/`,改功能前先讀對應那份)

| 模組 | 文件 | 涵蓋 |
|------|------|------|
| 認證與權限 | [`docs/auth.md`](docs/auth.md) | 註冊/登入、JWT、路由權限、全域錯誤處理 |
| 票券 | [`docs/ticket.md`](docs/ticket.md) | 票券 CRUD、上架時間窗、Redis 雙寫 |
| 購物車 | [`docs/cart.md`](docs/cart.md) | 加入/更新規則、庫存檢查(不預扣) |
| 庫存與鎖(核心) | [`docs/inventory.md`](docs/inventory.md) | Redis 正源、Lua 原子扣減、分散式鎖 |
| 結帳(核心) | [`docs/checkout.md`](docs/checkout.md) | 搶購主流程、回滾、DB 回寫補償、付款 |
| 訂單與退票 | [`docs/order.md`](docs/order.md) | 訂單狀態機、查詢、退票(v0.4) |

每份 spec 固定包含:現況規格、檔案地圖、設計意圖(不要動的理由)、已知邊界情況、已知問題/技術債、測試現況。**規格變更時直接更新對應 spec,不要另開新文件**;變更歷史交給 git。

### 其他

- 完整系統藍圖(含 AWS / Jenkins 規劃):[`../ticket-system-plan.md`](../ticket-system-plan.md)
- 歷史版本規格(唯讀參考,現況以 `docs/` 為準):`version0.1.md`、`version0.2.md`、`version0.3.md`、`version0.3-verification.md`
- Docker(MySQL/Redis)建置與維運:[`docker-setup.md`](docker-setup.md)
