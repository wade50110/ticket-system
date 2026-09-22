# 結帳模組(checkout / payment)

> 現況規格,對應程式碼為準。最後核對:2026-08-31。
> 搶購核心流程。庫存/鎖的底層機制見 inventory.md。

## 現況規格

### API
- `POST /api/checkout`(CUSTOMER):結帳「目前購物車全部內容」,成功回訂單明細。無 request body 參數。
- `POST /api/admin/stock-sync/retry`(ADMIN):手動觸發庫存回寫補償,回 `{"pendingBefore": n, "pendingAfter": m}`。

### 主流程(`CheckoutService.checkout`,實際順序)

| # | 步驟 | 失敗時 |
|---|---|---|
| 1 | 取購物車(依 id 升冪) | 空 → 409「購物車是空的」 |
| 2 | **依 ticketId 升冪排序**(防死鎖) | — |
| 3 | 一次預讀全部票券(取 name/price 快照) | 缺票券 → 409「購物車含已刪除票券…」 |
| 4 | 依序對每票 `tryLock("lock:ticket:{id}", 200ms, 3s)` | 任一失敗 → 409「搶票人潮過多,請稍後再試」 |
| 5 | 逐票 Lua 原子扣減 Redis(v0.5 起同一支 Lua 併做限購檢查+額度累計) | key 不存在/超過限購/不足 → 回補已扣的前幾筆(庫存+額度) → 409 |
| 6 | 建 **PENDING** 訂單(含品項快照) | — |
| 7 | Mock 付款 `paymentService.charge` | 失敗 → 回補全部庫存 + 訂單標 FAILED → 409 |
| 8 | 訂單標 **PAID** → 清空購物車 | — |
| 9 | 發 `StockChangedEvent`(非同步回寫 DB) | — |
| 10 | `finally`:**反向**釋放所有鎖 | 釋放失敗只 log(TTL 兜底) |

- 注意順序是**先建 PENDING 訂單、再付款**;付款失敗會留下一筆 FAILED 訂單(可追蹤)。
- 回滾(`rollback`,v0.5 更名):逐筆 `INCRBY` 加回庫存 Redis,再 Lua 釋回限購額度(兩步非原子,方向保守只會短暫誤判 409),單筆失敗只 log 不中斷。
- 限購超限訊息:「超過限購數量:『{票名}』每人限購 {N} 張,你還可購買 {r} 張」。權威閘門在 Lua(購物車預檢只是友善提示,見 cart.md)。
- 任何未預期 `RuntimeException` 也會觸發回補全部已扣庫存。

### 庫存回寫 DB(事後、非同步、可補償)

```
StockChangedEvent ──@Async──▶ StockSyncListener.onStockChanged
                                   │ 逐筆
                                   ▼
                     StockSyncWorker.syncOne  @Retryable(3次, backoff 500ms×2)
                     └─ TicketRepository.decrementStock(WHERE stock >= qty)
                     3次皆敗 → @Recover 寫 stock_sync_failed(delta = -qty)
                                   │
             @Scheduled(每60s, 初次延遲30s) StockSyncListener.retryFailedRecords
             └─ 撈 top100 未解決紀錄逐筆重試,成功設 resolvedAt,失敗 retryCount++
```

- **回寫失敗不影響使用者**:Redis 才是正源,DB 落後只是報表不準。
- `stock_sync_failed.delta` 語意:**負數 = 要扣(結帳)、正數 = 要加回(退票)**。
- 退票的回補走對稱的 `StockRestoredEvent` → `StockRestoreWorker`(見 order.md);兩個 Worker 刻意拆成獨立 bean(避免 `@Recover` 簽章衝突 + 確保 AOP proxy 生效)。

### 付款(payment/)
- `PaymentService` 介面:`charge(orderId, amount)` / `refund(orderId, amount)`,回 `PaymentResult(success, transactionId, message)`。
- 目前唯一實作 `MockPaymentService`:**兩個方法都無條件成功**,txn 格式 `MOCK-{UUID}` / `MOCK-REFUND-{UUID}`。換真實金流時只換 bean,業務層的失敗分支(`PaymentResult.fail`)已預留。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| 結帳主流程 + 回滾 | `backend/src/main/java/com/example/ticket/checkout/CheckoutService.java` |
| 結帳 API | `checkout/CheckoutController.java` |
| 結帳例外(409) | `checkout/CheckoutException.java` |
| 扣減事件 / 監聽 / Worker | `checkout/StockChangedEvent.java`、`StockSyncListener.java`、`StockSyncWorker.java` |
| 回補事件 / Worker(退票用) | `checkout/StockRestoredEvent.java`、`StockRestoreWorker.java` |
| 補償表 | `checkout/StockSyncFailed.java`(+`StockSyncFailedRepository`) |
| 手動重試 API | `checkout/AdminStockSyncController.java` |
| 付款介面 / Mock | `payment/PaymentService.java`、`MockPaymentService.java`、`PaymentResult.java` |
| 前端結帳入口 | `frontend/src/pages/Cart.jsx`(結帳按鈕)、`api/orders.js`(`checkout()`) |

## 設計意圖(不要動的理由)

1. **排序加鎖 + 反向釋放**:全體使用者用同一全域順序加鎖,是多票結帳不死鎖的前提。新增任何「一次鎖多票」的功能都必須沿用同一排序。
2. **結帳只信 Redis 扣減結果**,絕不在結帳路徑上讀寫 DB 庫存(DB 只由非同步 Worker 動)。
3. **品項快照**(ticketName/unitPrice 存進 order_items)在建單時就要寫入,之後改票不影響歷史訂單。
4. 補償 Worker 拆獨立 bean 不是風格問題:同類別自呼叫會繞過 Spring AOP,`@Retryable`/`@Recover` 會失效。

## 已知邊界情況

- 空購物車、含已刪票券、票券下架(key 不存在)、庫存不足(錯誤訊息帶剩餘張數)、付款失敗,皆有對應 409 與回補。
- 錯誤訊息截斷 500 字配合 `last_error` 欄位。

## 已知問題 / 技術債(記錄,尚未修正)

1. **標 PAID 之後的例外會誤回補庫存**:步驟 8 之後(清購物車、發事件)若拋 RuntimeException,catch 區仍會 `rollbackStock`,造成「訂單已 PAID 但庫存被加回」的不一致。
2. **`@Transactional` 失效兩處**:(a) `CheckoutService` 的 `createPendingOrder`/`markOrderPaid`/`markOrderFailed` 是 protected 且同類別自呼叫;(b) `StockSyncListener.retryFailedRecords` 自呼叫 `retryBatch`,排程補償的 dirty checking 更新(resolvedAt/retryCount)可能不會 flush。
3. **排程補償未依 delta 正負分支**:對退票紀錄(delta 正)算出負的 qty 丟給 `decrementStock`,`stock = stock - (-n)` 剛好等效加回、條件恆真 — 結果碰巧正確,但與 `StockRestoreWorker` 註解宣稱的行為不符,且完全沒用到 `incrementStock`,易誤導後續維護。
4. **無冪等鍵**:重複點結帳靠「購物車已被清空」擋,沒有訂單層級唯一約束。
5. 補償排程一次只撈 top100,積壓超過 100 筆時清消速度受限(有 log 可觀察)。
6. 排程補償中 `decrementStock` 回 0(WHERE 條件不成立,例如 DB 庫存不足)時,**既不設 resolvedAt 也不加 retryCount**,該紀錄會無聲滯留 pending,只能靠人工發現。

## 測試現況

- **本模組無任何測試**。最該補:併發結帳不超賣(整合測試)、回滾路徑(庫存不足/付款失敗)、補償表寫入與排程重試(可順便驗證已知問題 2、3)。
