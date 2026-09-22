# 購物車模組(cart)

> 現況規格,對應程式碼為準。最後核對:2026-08-31。

## 現況規格

### API(全部限 CUSTOMER,userId 一律取自 JWT,不吃前端參數)

| Method | Path | 行為 |
|---|---|---|
| GET | `/api/cart` | 列出自己的購物車 |
| POST | `/api/cart` | 加入(`ticketId` 必填、`quantity` ≥1) |
| **PATCH** | `/api/cart/{id}` | 更新數量(≥1;要清掉該項請用 DELETE,不能設 0) |
| DELETE | `/api/cart/{id}` | 移除單項 |
| DELETE | `/api/cart` | 清空 |

### 加入購物車規則
- 票券不存在 → 400「票券不存在」;未到 `visibleAt` → 「票券尚未開賣」;超過 `visibleUntil` → 「票券已下架」。
- **同票券重複加入 = 累加數量**,不會出現兩列;DB 有 `uk_cart_user_ticket(user_id, ticket_id)` unique 約束兜底。
- **庫存檢查但不預扣**:累加後總量 > 可用庫存 → 400「超出庫存:剩餘 N 張」。可用庫存**優先讀 Redis**(`ticket:stock:{id}`),Redis 無值 fallback DB。真正扣庫存在結帳(checkout.md)。
- **限購預檢(v0.5,友善提示、不佔額度)**:票券有設 `purchaseLimit` 時,`目前持有(讀 quota key,不存在視為 0)+ 購物車內同票數量(含本次)> limit` → 400「已達限購上限:『{票名}』每人限購 {N} 張,你已持有 {m} 張」。這只是提示,**權威閘門是結帳時的 Lua 原子檢查**(見 inventory.md / checkout.md);未設 limit 則跳過。

### 更新 / 刪除
- 項目不存在與**非本人的項目**回同一句「購物車項目不存在」(防越權 + 不洩漏存在性)。
- 更新時同樣做庫存上限檢查。

### 列表
- 遇到票券已被刪除的孤兒項目:**自動刪除並記 warn log**,不回給前端。
- 回傳的 `stock` 欄位取自 **DB**(非 Redis 正源),僅供前端顯示參考。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| API | `backend/src/main/java/com/example/ticket/cart/CartController.java` |
| 業務邏輯(累加/庫存檢查/越權防護) | `cart/CartService.java` |
| Entity(unique 約束) | `cart/CartItem.java` |
| DTO 驗證 | `cart/dto/AddToCartRequest.java`、`UpdateCartItemRequest.java` |
| 前端購物車頁 | `frontend/src/pages/Cart.jsx`、`api/cart.js` |

## 設計意圖(不要動的理由)

- **加購物車不預扣庫存是刻意的**:防超賣的唯一防線在結帳的 Redis Lua 原子扣減(inventory.md)。購物車的庫存檢查只是 UX 提前擋,天生有 TOCTOU race,不需要也不應該把它做成強一致。
- 越權操作回「不存在」而非「無權限」,與 order 模組同一原則:不洩漏他人資料是否存在。

## 已知邊界情況

- 孤兒項目自動清理(票券被 admin 刪除後)。
- Redis 無庫存值時 fallback DB,不會 NPE。

## 已知問題 / 技術債(記錄,尚未修正)

1. `CartItemResponse.stock` 讀 DB 而加入時檢查讀 Redis,兩者可能不一致(顯示用,影響小)。
2. 無限購/防黃牛機制(v0.4+ 規劃項目)。

## 測試現況

- **本模組無任何測試**。補測試時優先:重複加入累加、超庫存擋下、越權存取回「不存在」、孤兒清理。
