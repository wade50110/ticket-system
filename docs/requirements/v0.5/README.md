# v0.5 需求總覽

> 這一版總共改什麼的入口。想看細項,點對應的需求書。
> 建立日期:2026-09-18

## 這版的主題

防黃牛與流量控制:限制單帳號購買量、補上訂單取消、加上 API 限流。三者互補——
限購管「一個帳號最多買幾張」(業務額度)、rate limit 管「一個帳號/IP 每秒打幾次」(請求頻率)、
訂單取消補完訂單狀態機並修復孤兒 PENDING 佔庫存的洩漏。

## 功能一覽

| # | 功能 | 需求書 | 狀態 | 一句話 |
|---|------|--------|------|--------|
| 1 | 單帳號限購(防黃牛) | [purchase-limit.md](purchase-limit.md)([todo](purchase-limit-todo.md)) | ✅ 已完成 | 每票券由 admin 設限購數,限購檢查與庫存扣減同一支 Lua 原子完成 |
| 2 | 訂單取消 | [order-cancel.md](order-cancel.md) | 📝 已定稿 | PENDING 訂單手動/逾時自動取消,新增 CANCELLED 狀態 |
| 3 | API Rate Limit | [rate-limit.md](rate-limit.md) | 📝 已定稿 | 服務層限流,全域預設 + 端點覆寫,Redis 固定視窗、fail-open |

開發順序:限購 ✅ → 訂單取消 → rate limit(取消的「額度釋回」驗收依賴限購先存在;rate limit 獨立殿後)。

## 這版改到的主要範圍

- **後端新模組**:`stock/QuotaRedisRepository`、`QuotaBootstrap`、`QuotaReconcileService`、`AdminQuotaController`(限購)。
- **資料模型**:`tickets.purchase_limit`(限購);訂單取消將加 `orders.cancelled_at` 與 `OrderStatus.CANCELLED`。
- **既有 bug 修復**:`orders.status` 舊 ENUM 缺 REFUNDED(退票實際壞掉),已 `ALTER` 成 VARCHAR(20)——詳見 [purchase-limit-todo.md](purchase-limit-todo.md) 與 `docs/order.md`。

## 收尾後的現況以哪裡為準

功能完成後,現況一律以 `docs/` 的模組 spec 為準(ticket / inventory / checkout / cart / order);
本 folder 的需求書轉為歷史紀錄(這版當初決定做什麼、為什麼這樣決定)。
