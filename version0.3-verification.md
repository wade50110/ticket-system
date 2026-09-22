# v0.3 驗證清單

> 對應規劃文件：[`version0.3.md`](./version0.3.md)
> 用途：實作完成後，依此清單逐項打勾，全部過了才能算 v0.3 release。
> 約定：`[ ]` 表示未驗證；驗證過改成 `[x]`。

---

## 〇、先決條件

- [ ] Docker Desktop 已開啟，`docker ps` 看到 `ticket-mysql` 與 `ticket-redis` 都 `Up`
- [ ] MySQL CLI `SELECT 1` 通：`docker exec -it ticket-mysql mysql -uroot -proot -e "SELECT 1"`
- [ ] Redis `PING` 回 `PONG`：`docker exec -it ticket-redis redis-cli PING`
- [ ] 有 ADMIN 帳號（v0.2 leo301 已升級為 ADMIN）
- [ ] 有至少一個 CUSTOMER 帳號

---

## 一、編譯 / 啟動 smoke test

### 1. 編譯
- [ ] `cd backend; mvn clean package -DskipTests` 無錯誤
- [ ] 沒有 unresolved symbol / lombok 沒處理到 / Jakarta vs javax 衝突

### 2. 啟動
- [ ] `mvn spring-boot:run` 啟得起來、無 BeanCreationException
- [ ] 啟動 log 出現 `DistributedLock impl = Redisson`（預設）
- [ ] 啟動 log 出現 `[StockBootstrap] loaded=N, skipped=0, total=N`（N = 現有 tickets 數）
- [ ] Hibernate 自動建出兩張新表：`orders`、`order_items`、`stock_sync_failed`
  ```sql
  USE ticketdb;
  SHOW TABLES LIKE 'orders';
  SHOW TABLES LIKE 'order_items';
  SHOW TABLES LIKE 'stock_sync_failed';
  ```
- [ ] `tickets.stock` 對應 Redis：`redis-cli GET ticket:stock:1` 與 `SELECT stock FROM tickets WHERE id=1` 一致

---

## 二、API 權限驗證（SecurityConfig）

| 場景 | 預期 | 結果 |
|------|------|------|
| 顧客 POST `/api/checkout`（已登入） | 200 / 409 | [ ] |
| 管理員 POST `/api/checkout` | 403 | [ ] |
| 未登入 POST `/api/checkout` | 401 | [ ] |
| 顧客 GET `/api/orders` | 200 | [ ] |
| 管理員 GET `/api/orders` | 403 | [ ] |
| 顧客 GET 別人的訂單 `/api/orders/{他人 id}` | 400「訂單不存在」 | [ ] |
| 管理員 POST `/api/admin/stock-sync/retry` | 200 | [ ] |
| 顧客 POST `/api/admin/stock-sync/retry` | 403 | [ ] |

---

## 三、五條主驗證路徑

### 路徑 A：基本搶票（單人單張）

1. [ ] Admin 上架票券（任意 ticket），`stock=5`，記下 `id=X`
2. [ ] 顧客加入購物車 1 張該票
3. [ ] 顧客 POST `/api/checkout`
4. [ ] 回應 `200`，body 含 `orderNo` 形如 `ORD-20260519...-xxxxxxxx`、`status=PAID`
5. [ ] `redis-cli GET ticket:stock:X` 為 `4`
6. [ ] 等 ~3 秒後 `SELECT stock FROM tickets WHERE id=X` 為 `4`（async sync）
7. [ ] 顧客 GET `/api/orders` 看到該筆訂單
8. [ ] 顧客 GET `/api/orders/{id}` 看到完整明細（含 `ticketName` / `unitPrice` 快照）
9. [ ] 該顧客的購物車已清空：GET `/api/cart` 回 `[]`

### 路徑 B：庫存不足

1. [ ] Admin 上架 stock=2 的票
2. [ ] 顧客把該票數量設為 3 加入購物車（v0.2 加購物車檢查會擋；可手動 POST 兩次以略過 UI 限制）
3. [ ] 顧客 POST `/api/checkout`
4. [ ] 回應 `409`，error message 含「庫存不足，剩餘 2 張」
5. [ ] `redis-cli GET ticket:stock:X` 仍為 `2`（rollback 成功）
6. [ ] `SELECT stock FROM tickets WHERE id=X` 仍為 `2`
7. [ ] `SELECT COUNT(*) FROM orders` 沒增加 PAID 訂單（如有 FAILED 訂單也可，視實作）

### 路徑 C：並發搶票（核心驗證不超賣）

1. [ ] Admin 上架 stock=10 的票，記下 `id=X`
2. [ ] 在 `scripts/` 跑：
   ```powershell
   .\stress-checkout.ps1 -TicketId X -N 50
   ```
3. [ ] 腳本 summary：success=10、conflict=40、other=0
4. [ ] `redis-cli GET ticket:stock:X` 為 `0`
5. [ ] 等 ~10 秒後 `SELECT stock FROM tickets WHERE id=X` 為 `0`
6. [ ] `SELECT COUNT(*) FROM orders WHERE status='PAID'` 與 success 數一致（至少 10）
7. [ ] **絕不允許**：PAID 訂單 > 10 或 Redis/DB stock < 0
8. [ ] log 沒有 `rollback stock failed` 之類錯誤
9. [ ] 沒有遺留鎖：`redis-cli KEYS lock:ticket:*` 為空

### 路徑 D：切換鎖實作（功能對等）

1. [ ] 編輯 `application.yml` 改 `ticket.lock.type: manual`
2. [ ] 重啟後端，啟動 log 出現 `DistributedLock impl = RedisTemplate (manual Lua)`
3. [ ] 重跑路徑 C，結果應**完全一致**
4. [ ] 改回 `redisson`，再驗一次能切回

### 路徑 E：DB 同步失敗演練

1. [ ] 正常情況下完成一次結帳（路徑 A）
2. [ ] **停掉 MySQL**：`docker stop ticket-mysql`
3. [ ] 顧客再下一筆訂單（同一個顧客或別人）
4. [ ] Redis stock 應已扣（結帳成功），但後端 log 出現 `[StockSync] all retries failed, recording`
5. [ ] **重啟 MySQL**：`docker start ticket-mysql`
6. [ ] 等 ~60 秒（排程觸發）後：
   - [ ] log 出現 `[StockSync] retrying N failed records`
   - [ ] `SELECT * FROM stock_sync_failed WHERE resolved_at IS NULL` 為空
   - [ ] `tickets.stock` 對齊 Redis
7. [ ] 手動觸發測試：admin POST `/api/admin/stock-sync/retry` 應回 `{ pendingBefore, pendingAfter }`

> **注意**：路徑 E 第 3 步在 MySQL 停掉時，order 寫入 DB 本身也會失敗，預期結帳整體會回 500 + Redis 已扣會被 rollback。要乾淨驗證「Redis 扣成功但 DB sync 失敗」，比較好的做法是在路徑 A 成功後立刻停 MySQL，然後等待背景 sync 失敗——但目前 sync 是 fire-and-forget 在訂單寫完之後才發事件，所以實際路徑是：完成 A → 停 MySQL → 等 async sync 失敗。下次再下單會直接整體失敗（因為 order 寫不進）。

---

## 四、回歸驗證（v0.1 / v0.2 功能不能壞）

- [ ] 註冊 / 登入 / 登出 流程正常
- [ ] Admin 票券 CRUD：新增、修改、刪除 都成功，Redis stock 同步更新
- [ ] 顧客 Shop 頁面看得到當下可見票券（過濾上下架時間）
- [ ] 加入購物車成功；庫存檢查改用 Redis 後仍正確（試試把 admin 改 stock，加購物車的上限會跟著動）
- [ ] 購物車 `[+]/[-]` 數量調整正常（v0.2 CORS hotfix 還在）
- [ ] 購物車 PATCH 跨域 OK：開 DevTools Network 沒有 `Invalid CORS request`
- [ ] 移除購物車單項、清空購物車正常

---

## 五、前端 UX 檢查

- [ ] Cart 頁面有「結帳」按鈕，按下後正確跳到 `/orders/{id}`
- [ ] 結帳中按鈕變灰、文字「結帳中…」
- [ ] 結帳失敗時 error 顯示，購物車自動 reload
- [ ] `/orders` 列表正確顯示訂單編號 / 狀態 / 金額 / 付款時間
- [ ] `/orders/{id}` 明細顯示所有 OrderItem（票名、單價、數量、小計、付款序號）
- [ ] header 上「我的訂單」連結對 CUSTOMER 可見、ADMIN 不可見
- [ ] 顧客直接打 `/admin/tickets` 會被 PrivateRoute 導走

---

## 六、Edge cases / 額外觀察

- [ ] 同一帳號短時間連點兩次「結帳」按鈕：第二次不應重複扣 Redis stock（前端有 `checkingOut` flag 擋；後端因為購物車已清，第二次會回 409「購物車是空的」）
- [ ] 購物車裡有 `tickets` 表已刪除的票：應回 `409 「購物車含已刪除票券」`
- [ ] 拿到鎖但 Redis Lua 回 `-2`（key missing）：表示 admin 在結帳中途刪了票券，應回「票券已下架」並 rollback 已扣的
- [ ] PaymentService 改寫一個會回 `success=false` 的版本，驗證 rollback + Order=FAILED 路徑（v0.3 用 Mock 必成功，可選做）

---

## 七、簽收

- [ ] 以上各區塊全部 `[x]`
- [ ] `version0.3.md` 文件無未對齊的描述
- [ ] 已 commit 並 push 到 `origin/main`

驗證人：__________  日期：__________
