# Ticket System v0.2

> 角色分離（管理員 / 顧客）、票券上架（含上下架時間）、購物車（永久保存於 DB）

發布日期：2026-05-19

---

## 一、本版本新增內容

| 項目 | 狀態 |
|------|------|
| 兩種角色：ADMIN / CUSTOMER | ✅ |
| 註冊預設為 CUSTOMER（ADMIN 由 SQL 手動升級） | ✅ |
| JWT claim 帶 `role`，後端用 `hasRole` 控管 API | ✅ |
| 管理員票券 CRUD（含上架時間 `visibleAt`、下架時間 `visibleUntil`） | ✅ |
| 顧客票券列表 API（只列出當下可見的票券） | ✅ |
| 購物車存於 DB `cart_items`（登出再登入仍保留） | ✅ |
| 前端依角色自動導向（ADMIN → `/admin/tickets`，CUSTOMER → `/shop`） | ✅ |
| 前端 401 攔截，自動跳回登入頁 | ✅ |
| 結帳 / 訂單 | ❌（v0.3+） |
| Redis 分散式鎖 | ❌（v0.3+） |

---

## 二、新增的 API 規格

所有非公開 API 都需要 `Authorization: Bearer <accessToken>`。

### 管理員：票券 CRUD（需 `ROLE_ADMIN`）

| Method | Path | 說明 |
|--------|------|------|
| GET    | `/api/admin/tickets`        | 列出全部票券（含未上架/已下架） |
| GET    | `/api/admin/tickets/{id}`   | 取得單一票券 |
| POST   | `/api/admin/tickets`        | 新增票券 |
| PUT    | `/api/admin/tickets/{id}`   | 更新票券 |
| DELETE | `/api/admin/tickets/{id}`   | 刪除票券 |

Request body：

```json
{
  "name": "周杰倫嘉年華 2026",
  "description": "台北小巨蛋・搖滾區",
  "price": 4800.00,
  "stock": 100,
  "visibleAt": "2026-05-20T10:00:00",
  "visibleUntil": "2026-06-30T23:59:00"
}
```
`visibleAt` / `visibleUntil` 為 `null` 表示不限制（永遠可見 / 永不下架）。

### 顧客：票券列表（任何登入者皆可呼叫，會自動過濾）

| Method | Path | 說明 |
|--------|------|------|
| GET    | `/api/tickets`        | 列出當下可見的票券（過濾 `visibleAt <= now <= visibleUntil`） |
| GET    | `/api/tickets/{id}`   | 取得單一票券 |

### 顧客：購物車（需 `ROLE_CUSTOMER`）

| Method | Path | 說明 |
|--------|------|------|
| GET    | `/api/cart`         | 列出購物車（含每項小計） |
| POST   | `/api/cart`         | 加入購物車（同票券會累加數量） |
| PATCH  | `/api/cart/{id}`    | 修改數量 |
| DELETE | `/api/cart/{id}`    | 移除單一項目 |
| DELETE | `/api/cart`         | 清空購物車 |

POST body：
```json
{ "ticketId": 1, "quantity": 2 }
```

回應範例（GET /api/cart）：
```json
[
  {
    "id": 5,
    "ticketId": 1,
    "ticketName": "周杰倫嘉年華 2026",
    "price": 4800.00,
    "stock": 100,
    "quantity": 2,
    "subtotal": 9600.00
  }
]
```

---

## 三、資料庫變更

JPA `ddl-auto: update` 會自動：

1. 在 `users` 表新增 `role` 欄位（`VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER'`）
2. 建立 `tickets` 表
3. 建立 `cart_items` 表（含 `(user_id, ticket_id)` 唯一鍵）

### 把現有帳號升級為管理員

啟動後手動執行：

```sql
USE ticketdb;
UPDATE users SET role = 'ADMIN' WHERE username = 'leo301';
```

升級後**需要重新登入**（JWT 需要重新簽發以帶入新的 role）。

---

## 四、專案結構新增

```
ticket-system/backend/src/main/java/com/example/ticket/
├── auth/
│   ├── Role.java                          ★ ENUM { CUSTOMER, ADMIN }
│   ├── User.java                          ☆ 加 role 欄位
│   ├── JwtService.java                    ☆ JWT 加 role claim
│   ├── JwtAuthFilter.java                 ☆ 把 role 變成 ROLE_xxx authority
│   └── dto/UserInfo.java                  ☆ 回傳 role
├── ticket/
│   ├── Ticket.java                        ★
│   ├── TicketRepository.java              ★ findVisibleAt(now)
│   ├── TicketService.java                 ★
│   ├── AdminTicketController.java         ★ /api/admin/tickets
│   ├── TicketController.java              ★ /api/tickets
│   └── dto/{TicketRequest,TicketResponse}
├── cart/
│   ├── CartItem.java                      ★
│   ├── CartItemRepository.java            ★
│   ├── CartService.java                   ★
│   ├── CartController.java                ★ /api/cart
│   └── dto/{AddToCartRequest,UpdateCartItemRequest,CartItemResponse}
└── config/SecurityConfig.java             ☆ /api/admin/** → hasRole("ADMIN")
                                              /api/cart/** → hasRole("CUSTOMER")

ticket-system/frontend/src/
├── api/
│   ├── http.js                            ★ fetch 包裝，401 自動跳轉
│   ├── tickets.js                         ★ customerTickets / adminTickets
│   └── cart.js                            ★
├── components/
│   └── AppLayout.jsx                      ★ 共用 header（含登出 / 角色徽章）
└── pages/
    ├── Login.jsx                          ☆ 依角色導向
    ├── admin/AdminTickets.jsx             ★ 票券 CRUD
    ├── Shop.jsx                           ★ 顧客商城
    └── Cart.jsx                           ★ 購物車

★ 新增  ☆ 修改
```

---

## 五、操作流程驗證

### 路徑 A：管理員上架票券
1. 登入 `leo301`（已用 SQL 升級為 ADMIN）→ 自動跳到 `/admin/tickets`
2. 點「＋ 新增票券」，填寫資料；下架時間可填，可不填
3. 設定上架時間為 **未來時間**，儲存
4. 登出，用另一個顧客帳號登入 → `/shop` 應該**看不到**此票券
5. 把上架時間改成現在 → 顧客重新整理 `/shop`，該票券會出現

### 路徑 B：顧客購物車（持久化測試）
1. 註冊一個新帳號（自動為 CUSTOMER）→ 登入後跳 `/shop`
2. 對某張票券點「加入購物車」
3. 切到 `/cart`，調整數量、再加另一張票券
4. **登出**
5. 重新登入 → 進入 `/cart`，購物車內容應**完整保留**

### 路徑 C：權限隔離
1. 顧客身份直接打 `/api/admin/tickets` → 應回 `403`
2. 管理員身份打 `/api/cart` → 應回 `403`（管理員不能購物，這版設計）
3. 任何人未帶 token → `401`

---

## 六、已知限制 / v0.3 規劃

- 沒有結帳、訂單模組（購物車存著也買不了）
- 加入購物車時做了一次 `stock` 檢查，但**沒有真的扣庫存**，仍有超賣風險（待 v0.3 用 Redis 鎖處理）
- 沒有票券分類、搜尋、分頁
- 管理員自己沒有購物車（如果之後要讓管理員測試購買，需放寬 `/api/cart/**` 的角色限制）
- 上下架時間以後端 `LocalDateTime.now()`（伺服器時區）為準，前後端時區一致時才正確

---

## 七、參考

- v0.1 release note：[`./version0.1.md`](./version0.1.md)
- 完整系統規劃：[`../ticket-system-plan.md`](../ticket-system-plan.md)
