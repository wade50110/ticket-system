# 需求書:訂單取消(手動 + 逾時自動取消)

> 狀態:已定稿(2026-09-18,使用者確認;含設計審查修正)(草稿 → 已定稿 → 開發中 → 已完成)
> 版本:v0.5
> 建立日期:2026-09-18
> Todo:(開發開始後補上)

## 1. 目標與背景

現況訂單狀態機沒有 CANCELLED:`PENDING → PAID / FAILED → REFUNDED`。因 Mock 付款即刻成功,PENDING 視窗極短,但**結帳流程在「扣 Redis 庫存 → 建 PENDING 訂單」之後、標 PAID 之前若程式崩潰,會留下佔著庫存的孤兒 PENDING 訂單**(order.md 技術債 2、庫存洩漏)。本功能補上取消能力:

- 使用者可手動取消自己的 PENDING 訂單。
- 排程自動取消逾時 PENDING 訂單,修復孤兒訂單庫存洩漏。
- 為未來接真實金流鋪路(屆時 PENDING 會有真實的付款等待視窗)。

## 2. 功能需求

- 顧客可取消**自己的、狀態為 PENDING** 的訂單;取消後狀態轉 CANCELLED(終態),庫存釋回 Redis(正源)。
- **取消不發任何 DB 回寫事件**:結帳的 DB 扣減事件(`StockChangedEvent`)只在標 PAID 成功後才發佈,PENDING 訂單的扣減**從未寫進 DB**;若取消再發 DB 回補事件,DB 庫存會虛增,經 `StockBootstrap` 重建或 admin 編輯票券覆寫 Redis 的路徑轉為實際超賣。
- 排程每分鐘掃描:PENDING 且建立時間超過逾時門檻(預設 15 分鐘,設定檔 `ticket.order.pending-timeout-minutes` 可調)的訂單,自動走同一取消邏輯。
- 已取消訂單不可退票、不可再取消。
- 前端:訂單列表/明細對 PENDING 訂單顯示「取消訂單」按鈕(confirm 二次確認,成功就地更新);`OrderStatusBadge` 新增 CANCELLED →「已取消」。
- 若限購功能(purchase-limit)已上線,取消時同步釋回限購額度。

## 3. API 規格

| Method | Path | 權限 | 說明 |
|--------|------|------|------|
| POST | `/api/orders/{id}/cancel` | CUSTOMER(僅本人) | 取消 PENDING 訂單,成功回訂單明細 |

錯誤對照(對齊退票的語意與 GlobalExceptionHandler 格式):
- 不存在 / 非本人 → **400**「訂單不存在」(資訊隱藏,與退票一致)
- 非 PENDING(PAID/FAILED/REFUNDED/CANCELLED)/ 併發輸掉 → **409**「訂單已取消或目前狀態無法取消」

## 4. 資料模型變更

- `orders` 新增 `cancelledAt`(DATETIME,nullable)。
- `OrderStatus` 新增 `CANCELLED`(終態)。
- 新增設定 `ticket.order.pending-timeout-minutes`(預設 15)。

## 5. 邊界情況與錯誤處理

| 情況 | 系統反應 |
|------|----------|
| 併發重複取消(手動×2 或 手動+排程) | 原子條件更新 `markCancelled … WHERE status='PENDING'`(鏡射 `markRefunded`);`updated==0` 的輸家**不回補庫存、不釋回額度**,手動回 409、排程靜默跳過 |
| 取消與「結帳標 PAID」競態 | `markOrderPaid` 改為條件更新 `WHERE status='PENDING'`;更新 0 筆(已被取消)→ 執行 Mock 退款、回 409「訂單已被取消」,且結帳方**不回補庫存**(取消方已回補,避免雙重回補)——此分支不得走 checkout 既有的 `rollbackStock`。此分支發生在清購物車之前,**購物車保留**,使用者可調整後重結;退款交易序號記入 log(訂單已轉 CANCELLED 無欄位可寫,接真金流時需重新設計此處的冪等與序號保存) |
| CANCELLED 訂單申請退票 | 既有 `markRefunded WHERE status='PAID'` 天然擋住 → 409 |
| Redis 回補失敗 | 與退票同取捨:只 log 不中斷(狀態已轉,寧可少賣) |
| 排程掃描中單筆失敗 | 只 log、繼續處理下一筆,不中斷整批 |
| 逾時門檻設為 0 或負值 | 啟動時驗證,非法值拒絕啟動並給明確訊息 |

## 6. 非目標(範圍外)

- 不做 PAID 訂單的取消(那是退票,已存在)。
- 不做部分取消(整筆取消,與退票一致)。
- 不做取消原因欄位、不做 admin 代客取消。

## 7. 驗收條件

- AC-1:本人取消 PENDING 訂單 → 200,狀態 CANCELLED、`cancelledAt` 有值。
- AC-2:取消後 Redis 庫存加回品項數量;**不發 DB 回寫事件、DB 庫存不變**(PENDING 的扣減本來就未寫入 DB)。
- AC-3:非本人/不存在 → 400「訂單不存在」。
- AC-4:非 PENDING 狀態取消 → 409。
- AC-5:併發重複取消僅一個成功,輸家不重複回補庫存。
- AC-6:排程只取消「超過逾時門檻」的 PENDING 訂單並回補庫存;未逾時的不動。
- AC-7:結帳標 PAID 時發現已被取消 → 退款、回 409、不重複回補庫存。
- AC-8:前端 PENDING 訂單顯示取消按鈕(其他狀態不顯示),confirm 後成功就地更新;CANCELLED 顯示「已取消」徽章。
- AC-9:(限購上線後)取消後限購額度釋回,可再購。

## 8. 測試要求

- 後端(JUnit + Spring Boot Test):AC-1~AC-7 逐條覆蓋,重點:併發雙取消(參照 `RefundServiceTest` 的併發測試手法)、排程逾時篩選邏輯、markPaid 衝突分支不雙重回補。AC-7 需以**可控時序**重現(mock PaymentService 於 charge 返回後觸發取消,或以 latch 控制),不得寫成碰運氣的假併發測試。
- 前端(Vitest + RTL,mock API):按鈕顯示條件、confirm 取消不打 API、成功就地更新、409 錯誤顯示、CANCELLED 徽章。
- 跑過後端 `mvn test` 全套與前端 `npm test` 全套。

## 9. 決策紀錄

| # | 問題 | 決定 | 理由 |
|---|------|------|------|
| 1 | 「取消」的範圍?(退票 v0.4 已完成) | 為 PENDING 訂單加取消,新增 CANCELLED 狀態 | 為真金流鋪路;現況 Mock 下視窗極短但邏輯完整 |
| 2 | 要不要做逾時自動取消排程? | 要,手動+自動都做 | 順帶修復孤兒 PENDING 佔庫存的洩漏(結帳中途崩潰的情境) |
| 3 | 取消的庫存釋回機制 | **只回補 Redis,不發 DB 回寫事件**(設計審查後修正,原擬復用退票的 `StockRestoredEvent`) | PENDING 的 DB 扣減從未發生(`StockChangedEvent` 只在標 PAID 後發佈),再發 DB 回補會虛增 DB 庫存,經 `StockBootstrap`/admin 改票覆寫路徑轉為實際超賣 |
| 4 | markPaid 競態處理 | markOrderPaid 改條件更新 WHERE PENDING,衝突時退款且不重複回補 | 防止「已取消訂單被標 PAID」與庫存雙重釋放 |
