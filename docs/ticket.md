# 票券模組(ticket)

> 現況規格,對應程式碼為準。最後核對:2026-08-31。

## 現況規格

### Ticket 欄位
`id`、`name`(必填, ≤200)、`description`(≤2000)、`price`(DECIMAL 12,2, ≥0)、`stock`(≥0)、`purchaseLimit`(每帳號限購數,nullable = 不限,驗證 ≥1;v0.5)、`visibleAt` / `visibleUntil`(皆可 null = 不限)、`createdAt` / `updatedAt`(自動維護)。

**尚無 `imageUrl` 欄位** — v0.4 🅑 票券圖片功能還沒實作。

### 顧客端查詢
- `GET /api/tickets`(已登入):只回「上架時間窗內」的票 — `(visibleAt 為 null 或 ≤ now) AND (visibleUntil 為 null 或 ≥ now)`,依 id 由新到舊。
- **不會排除 stock = 0 的票**,售完仍列出(前端 Shop 把售完票的按鈕 disable)。
- `GET /api/tickets/{id}`:直接 findById,**不檢查上架時間窗**。

### 管理端 CRUD(`/api/admin/tickets`,ADMIN)
- `GET`(全部,不過濾)/ `GET /{id}` / `POST` / `PUT /{id}` / `DELETE /{id}`(204)。
- 驗證:`visibleUntil` 不可早於 `visibleAt`(create/update 都檢查)。
- **Redis 雙寫**(庫存 key `ticket:stock:{id}`):
  - 建立:DB save 後 `SET` 寫入初始庫存。
  - 修改:更新 DB 後 **無條件 `SET` 覆寫 Redis 庫存**(等同重設庫存)。
  - 刪除:先清掉所有使用者購物車中引用此票的項目(防孤兒)→ 刪 DB → 刪 Redis stock key → SCAN 刪該票全部 quota key(`ticket:quota:{id}:*`)。
- Redis 操作在 `@Transactional` 內但不參與交易:DB rollback 時 Redis 已寫入不會回滾。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| Entity | `backend/src/main/java/com/example/ticket/ticket/Ticket.java` |
| 顧客查詢 API | `ticket/TicketController.java` |
| 管理 CRUD API | `ticket/AdminTicketController.java` |
| 業務邏輯 + Redis 雙寫 | `ticket/TicketService.java` |
| 時間窗查詢 / 庫存條件更新 SQL | `ticket/TicketRepository.java`(`findVisibleAt`、`decrementStock`、`incrementStock`) |
| 前端商城 / 管理頁 | `frontend/src/pages/Shop.jsx`、`pages/admin/AdminTickets.jsx`、`api/tickets.js` |

## 設計意圖(不要動的理由)

- **Redis 是庫存正源,DB `tickets.stock` 只是事後回寫**(見 inventory.md)。任何讀「即時庫存」的地方都應優先讀 Redis。
- 刪票前先清購物車引用,是為了讓 cart 列表不出現孤兒(cart 端另有自動清理兜底)。
- 顧客列表不過濾售完票是目前的產品決定(讓使用者看得到「售完」狀態)。

## 已知邊界情況

- 上/下架時間可為 null(不限時)。
- 票券不存在統一回 400「票券不存在」。

## 已知問題 / 技術債(記錄,尚未修正)

1. **`update` 無條件覆寫 Redis 庫存**:搶購進行中若 admin 編輯票券(即使只改名),Redis 即時扣減值會被表單上的 stock 蓋掉,可能造成超賣或庫存憑空恢復。改庫存邏輯前務必先讀 inventory.md。
2. `GET /api/tickets/{id}` 不檢查上架時間窗,顧客可透過 id 直接查未上架/已下架票的資訊。
3. Redis 與 DB 雙寫無交易一致性保證(目前靠 StockBootstrap 與回寫機制收斂)。

## 測試現況

- **本模組無任何測試**(後端、前端皆無)。補測試時優先:時間窗過濾邏輯、update 對 Redis 的影響、刪票清購物車。
