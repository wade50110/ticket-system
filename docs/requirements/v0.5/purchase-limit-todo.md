# Todo:單帳號限購(防黃牛)

> 對應需求書:purchase-limit.md
> 完成定義:實作完成 + 對應測試實際跑過且通過
> 狀態:已完成(2026-09-18)

- [x] 1. Ticket entity + TicketRequest/TicketResponse 加 `purchaseLimit`,驗證 ≥1 或 null(AC-1)
- [x] 2. QuotaRedisRepository:quota key 命名、扣減 Lua(額度+庫存原子檢查)、釋回 Lua(key 不存在不動作、夾 0)(AC-2, AC-11)
- [x] 3. CheckoutService:改用新 Lua、rollback 一併回滾額度(先庫存後額度)、超限 409 訊息含票名與剩餘額度(AC-2, AC-3, AC-5)
- [x] 4. RefundService:退票時 Lua 釋回額度(AC-7)
- [x] 5. CartService:加車/更新數量預檢(持有+車內 ≤ limit),400 訊息(AC-9)
- [x] 6. Quota 啟動重建 QuotaBootstrap:比照 StockBootstrap,setIfAbsent、DB 彙總 PENDING+PAID(AC-10)
- [x] 7. Admin 對帳 API `POST /api/admin/quota/reconcile`:DB 彙總覆寫全部 quota key(AC-12)
- [x] 8. 刪票時清除該票全部 quota key(SCAN)
- [x] 9. 前端:AdminTickets 表單限購欄位、Shop 顯示「每人限購 N 張」、超限錯誤訊息顯示(AC-1, AC-9)
- [x] 10. 後端測試:Lua 各分支、同帳號併發不超限、跨帳號隔離、回滾對稱、釋回夾 0、對帳 API、欄位驗證(AC-2~AC-7, AC-10~AC-12)
- [x] 11. 前端測試:表單欄位、限購顯示、錯誤訊息
- [x] 12. 跑過後端 `mvn test` 全套(36 passed)與前端 `npm test` 全套(17 passed)
- [x] 13. 更新 docs/ 現況 spec(ticket.md、inventory.md、checkout.md、cart.md、order.md)
- [x] 14. 需求書狀態改「已完成」

## E2E 實測結果(瀏覽器 + API)

- 商城正確顯示「每人限購 2 張」。
- 購物車第二次加入被擋:「已達限購上限:『Concert A - Demo』每人限購 2 張,你已持有 1 張」(AC-9)。
- 結帳買滿上限後回商城再加被擋(持有 2/2)。
- 退票後 Redis:quota 2→1、stock 48→49(AC-7)。

## Code review 修正(審查後,不需重複審查)

- 中度:`QuotaReconcileService` 無條件覆寫在有流量時會蓋掉併發扣減 → 造成永久超限。修正:javadoc + API 回應 `warning` + 需求書明確標注「僅可於無結帳流量的維護窗口執行」(對維護工具不引入全表鎖的取捨)。
- 低度:釋回 Lua 到 ≤0 時改為 `DEL` key(原本 SET 0),消除零值 key 無 TTL 累積。
- 低度:補 `TicketRequestValidationTest`(purchaseLimit null/≥1/0/負數,4 例)驗證 `@Min(1)`。
- 低度:補 `QuotaBootstrapTest`(AC-10 啟動重建 setIfAbsent,2 例)+ `QuotaRedisRepositoryRedisTest` 加 setIfAbsent 與 release 到 0 刪 key(共 +3 例)。
- **待補缺口(記錄,未做)**:`OrderRepository.aggregateHeldQuantities` 的 JPQL 無自動化 DB 測試(專案無 H2/testcontainers 基礎設施;啟動時 `QuotaBootstrap` 已 E2E 實測能從 DB 建出 quota key)。qty>1 且 limit 非整除的併發案例、走完整 CheckoutService(含鎖)的端到端併發測試屬加強項。→ 待引入 DB 測試基礎設施時補。

## 額外發現並修復的既有 bug(與限購無關)

- `orders.status` 在這台開發機的 MySQL 是 `enum('PENDING','PAID','FAILED')`,**缺 REFUNDED**——舊版本三值時被 `ddl-auto: update` 建成 ENUM,v0.4 加 REFUNDED 到 Java enum 後,`ddl-auto: update` 不會改既有 ENUM 定義,導致真實退票 UPDATE 報「Data truncated for column 'status'」。退票單元測試是純 Mockito(mock repository),真實 SQL 從沒跑過,故 v0.4 起潛伏未發現。
- 修復:`ALTER TABLE orders MODIFY COLUMN status VARCHAR(20) NOT NULL`(對齊 Order.java 的 `@Enumerated(STRING) @Column(length=20)` 意圖,現有值相容)。
- 已記入 order.md 已知問題;此修復同時解除「訂單取消(加 CANCELLED)」的阻塞。
