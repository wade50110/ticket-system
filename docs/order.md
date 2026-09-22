# 訂單與退票模組(order)

> 現況規格,對應程式碼為準。最後核對:2026-08-31(退票為 v0.4 🅐,已完成)。

## 現況規格

### Order / OrderItem 欄位
- `orders`:`id`、`orderNo`(unique,格式 `ORD-yyyyMMddHHmmssSSS-{UUID前8碼}`)、`userId`、`totalAmount`、`status`、`paymentTransactionId`、`createdAt`、`paidAt`、`refundedAt`、`refundTransactionId`;索引 `(user_id, created_at)`。
- `order_items` **快照欄位**:`ticketName`、`unitPrice` 存下單當下的值 + `quantity`、`subtotal` — 事後改票名/改價不影響歷史訂單。

### 訂單狀態(OrderStatus)
`PENDING`、`PAID`、`FAILED`、`REFUNDED` 四值,**沒有 CANCELLED**。轉移規則(散在程式中,無集中狀態機):

```
建立 → PENDING → PAID ──(退票)──▶ REFUNDED(終態)
              └→ FAILED(付款失敗,終態)
```

`PAID → REFUNDED` 是唯一合法退票轉移,由 SQL 條件更新強制(`markRefunded` … `WHERE status = 'PAID'`)。

### 查詢 API(限 CUSTOMER)
- `GET /api/orders`:自己的訂單列表,**依 id 由新到舊**;用 `summary()` 轉換,**items 固定空陣列**(避免 N+1)。
- `GET /api/orders/{id}`:明細,`@EntityGraph` 一次撈 items。
- **只能查自己的**:非本人與不存在回**同一句**「訂單不存在」(400),不洩漏他人訂單存在性。
- userId 一律取自 JWT principal,不吃前端參數。

### 退票 `POST /api/orders/{id}/refund`(v0.4)

**規則(使用者確認過的規格決策)**:
- 只有 **PAID** 可退;**整筆訂單退**(不做單項退);**僅本人**可退。
- **無時間限制**(活動開演前隨時可退)、**不收手續費**。
- 退款走 Mock(`MOCK-REFUND-{UUID}`),狀態轉 `REFUNDED`,庫存加回後**可被再搶**。

**流程與防護**:
1. 查訂單 → 驗本人(失敗訊息同「訂單不存在」)→ 驗 PAID。
2. Mock 退款(失敗 → 409「退款失敗:…」)。
3. **原子翻轉** `markRefunded`(條件更新 `WHERE status = PAID`,同時寫 refundedAt/refundTransactionId);`updated == 0` → 409「訂單已退票或目前狀態無法退票」——**這是防重複退票/併發退票的關鍵閘門**,輸掉的請求不會釋放庫存、不會發事件。
4. 逐品項 `INCRBY` 加回 **Redis(正源)**;單筆失敗只 log 不中斷(款已退、狀態已改,不能回頭)。
5. 發 `StockRestoredEvent` → `StockRestoreWorker` 非同步回寫 DB(`incrementStock`,失敗 3 次落 `stock_sync_failed`,delta 為正,排程補償——見 checkout.md)。
6. bulk update 不回填 detached 物件,手動 set 狀態欄位供回應。

**錯誤對照**:不存在/非本人 → 400;非 PAID / 退款失敗 / 併發輸掉 → 409(`RefundException`)。

### 前端對應
- `Orders.jsx`:列表逐列退票按鈕(只在 PAID 顯示),confirm 二次確認,成功**就地更新該列不重抓列表**,送出中只鎖該列。
- `OrderDetail.jsx`:申請退票按鈕、退票時間/退款序號顯示。
- `OrderStatusBadge.jsx`:共用狀態徽章(處理中/已付款/失敗/已退票,未知狀態顯示原始碼)。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| Entity / 狀態 | `backend/src/main/java/com/example/ticket/order/Order.java`、`OrderItem.java`、`OrderStatus.java` |
| 查詢 API / 邏輯 | `order/OrderController.java`、`OrderService.java` |
| 退票主邏輯 | `order/RefundService.java` |
| 退票例外(409) | `order/RefundException.java` |
| 原子條件更新 SQL | `order/OrderRepository.java`(`markRefunded`) |
| 退票庫存回補(DB 端) | `checkout/StockRestoredEvent.java`、`checkout/StockRestoreWorker.java` |
| 退款 Mock | `payment/MockPaymentService.java`(`refund`) |
| 後端測試 | `backend/src/test/java/com/example/ticket/order/RefundServiceTest.java` |
| 前端 | `frontend/src/pages/Orders.jsx`、`OrderDetail.jsx`、`components/OrderStatusBadge.jsx`、`api/orders.js` |
| 前端測試 | `pages/Orders.test.jsx`、`pages/OrderDetail.test.jsx`、`api/orders.test.js` |

## 設計意圖(不要動的理由)

1. **`markRefunded` 條件更新是併發防護的核心**,不要改成「先查狀態再 save」——那會重新引入重複退票 race。
2. **Redis 先回補、DB 事後補**與結帳同一方向性(Redis 正源);Redis 加回失敗不中斷是刻意的取捨(款已退,寧可庫存少賣不可讓使用者退款失敗)。
3. 非本人回「訂單不存在」是刻意的資訊隱藏,與 cart/order 查詢同一原則。
4. 列表 summary 不含 items 是效能決定;要看品項走明細 API。

## 已知邊界情況

- 併發重複退票:只有一個請求能成功(測試已覆蓋)。
- 退款序號/退票時間為 null 時前端顯示 `—`。

## 已知問題 / 技術債(記錄,尚未修正)

1. Mock 退款在 `markRefunded` **之前**執行:若退款成功但 markRefunded 輸掉併發(另一請求已退),這筆 Mock 退款等於白呼叫(Mock 無副作用所以目前無害,接真金流時要重新設計順序或加冪等)。
2. 無 CANCELLED 狀態:PENDING 訂單無法取消(v0.5 訂單取消功能規劃中,見 requirements/v0.5/order-cancel.md)。
3. **`orders.status` 欄位型別陷阱(v0.5 修復)**:此開發機 MySQL 曾把 `status` 建成 `enum('PENDING','PAID','FAILED')`(舊三值時期),`ddl-auto: update` 不會修改既有 ENUM 定義,導致 v0.4 加的 REFUNDED 寫入時「Data truncated」——真實退票其實從 v0.4 起就壞,只因 `RefundServiceTest` 是純 Mockito(mock repository)真實 SQL 從沒跑過而未被發現。已 `ALTER TABLE orders MODIFY COLUMN status VARCHAR(20) NOT NULL` 修復(對齊 `@Enumerated(STRING) @Column(length=20)`)。**全新環境 Hibernate 會直接建 VARCHAR 不受影響;但任何從舊 ENUM 升上來的既有環境,加新狀態值(如 CANCELLED)前都要先做此 ALTER。** 專案無 migration 工具(ddl-auto:update),這類 enum 演進需人工留意。

### 限購額度連動(v0.5)
- 退票成功後,除了釋放庫存,也對每個品項 `QuotaRedisRepository.release`(Lua 夾 0)釋回該帳號的限購額度;釋回失敗只 log(額度偏高、對使用者保守誤擋),由 admin 對帳 API 收斂。與 `markRefunded` 併發輸掉的請求一樣不釋回額度。

## 測試現況

- 後端 `RefundServiceTest` 5 例:成功回補+發事件、非本人不動作、不存在、非 PAID、併發輸掉不釋放庫存。**未覆蓋**:退款失敗分支、Redis increment 例外分支、Controller 層整合。
- 前端 3 檔 11 例:按鈕顯示條件、confirm 取消不打 API、成功就地更新、失敗顯示訊息可重試、API 路徑與 header、409 錯誤傳遞。
- 查詢(OrderService/OrderController)無測試。
