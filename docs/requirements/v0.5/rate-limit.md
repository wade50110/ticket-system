# 需求書:API Rate Limit(服務層,全域預設 + 端點覆寫)

> 狀態:已定稿(2026-09-18,使用者確認;含設計審查修正)(草稿 → 已定稿 → 開發中 → 已完成)
> 版本:v0.5
> 建立日期:2026-09-18
> Todo:(開發開始後補上)

## 1. 目標與背景

限制 API 請求頻率,防搶票腳本高頻打結帳、防登入暴力破解與批量註冊,並給所有 API 一層防異常流量的兜底。與限購(業務額度)、排隊(流量整形)互補:本功能管的是**請求頻率**。

**架構決策**:本專案現階段無 gateway(前端直連 8099),限流先做在**服務層**(Redis 計數,與既有基礎設施共用);未來 v0.5+ Docker 化/上雲時,再於 Nginx/ALB/WAF 補粗粒度 IP 限流,屆時服務層規則不變(縱深防禦)。

## 2. 功能需求

- 實作一個 rate limit Filter,**插入 Spring Security filter chain 中、`JwtAuthFilter` 之後、授權(AuthorizationFilter)之前**,對每個請求依規則計數,超限回 **429**。位置理由:放在認證後才拿得到 userId;放在授權前,未帶 token 打受保護端點的異常流量**先被 per-IP 全域規則計數、再收到 401**,否則「未登入全域規則」形同虛設。
- **註冊方式必須防雙重計數**:Filter 進 security chain 的同時,以 `FilterRegistrationBean.setEnabled(false)` 停用 Spring Boot 對 Filter bean 的 servlet 自動註冊(現有 `JwtAuthFilter` 即存在雙重註冊現象,JWT 冪等無害,但限流計數兩次會使所有限額實際砍半)。
- CORS preflight(OPTIONS)不計數。
- **分層規則:全域預設(鬆)+ 端點覆寫(緊)**,全部由設定檔(`application.yml` 之 `ticket.ratelimit.*`)驅動,新增/調整規則不改程式。
- 識別鍵:已登入 → `userId`;未登入 → 客戶端 IP。
- 草稿數值(**定稿時請確認,皆可於設定檔調整**):

| 規則 | 鍵 | 限制 |
|------|-----|------|
| 全域預設(已登入) | per-user | 120 次/分鐘 |
| 全域預設(未登入) | per-IP | 60 次/分鐘 |
| `POST /api/checkout` | per-user | 5 次/分鐘 |
| `POST /api/auth/login` | per-IP | 10 次/分鐘 |
| `POST /api/auth/register` | per-IP | 5 次/分鐘 |

- 端點覆寫命中時**只計覆寫規則**,不重複計入全域(一個請求只被一條規則計數)。
- 429 回應:JSON 格式對齊 `GlobalExceptionHandler` 既有錯誤格式(**欄位名是 `error`**,前端 `http.js` 只讀 `data.error`),並帶 `Retry-After` header(秒)。實作註記:Filter 在 MVC 之外,`@RestControllerAdvice` 不會參與,需在 Filter 內手動組 response(ObjectMapper 寫 JSON、`Content-Type: application/json;charset=UTF-8`——中文訊息勿漏 charset)。
- 排除清單:`GET /api/health` 不限流(監控/探活用)。
- 前端:`api/http.js` 統一攔截 429,顯示「操作太頻繁,請稍後再試」。

## 3. API 規格

無新端點。所有既有 API 新增可能的 429 回應:

```json
HTTP 429, Retry-After: {秒}
{ "error": "操作太頻繁,請稍後再試" }
```

## 4. 資料模型變更

- DB:無。
- Redis:計數 key `rl:{ruleId}:{key}:{windowStart}`,TTL = 視窗長度(自動過期,不需清理)。
- 設定:`ticket.ratelimit.enabled`(預設 true)、`ticket.ratelimit.default-*`、`ticket.ratelimit.rules[]`(path、method、scope=user|ip、limit、window)。

## 5. 邊界情況與錯誤處理

| 情況 | 系統反應 |
|------|----------|
| 計數原子性 | `INCR` + 首次 `EXPIRE` 以 Lua 合併為單一腳本,防 INCR 成功但 EXPIRE 未設造成 key 永存 |
| 固定視窗邊界突刺 | 已知限制:視窗交界瞬間最多 2 倍流量,接受(實作簡單優先);未來需要再換 sliding window |
| **Redis 不可用** | **fail-open:放行請求並 log error**——限流器故障不可癱瘓整個服務;Redis 掛掉時結帳本身也會失敗,風險可控。**catch 範圍限定 Redis/連線類例外**,不得 catch 全部 Exception(否則規則設定錯誤也被無聲吞掉);規則設定非法(如 window ≤ 0)應在啟動時驗證失敗、拒絕啟動 |
| 未登入請求打有 per-user 覆寫的端點(如未帶 token 打 checkout) | 端點覆寫是 per-user、對匿名不適用 → **落回全域 per-IP 規則計數**,之後才被授權層擋(401)。掃描受保護端點的異常流量因此仍受限流保護 |
| IP 取得 | 現階段直連取 `remoteAddr`;`X-Forwarded-For` 僅在未來設定「信任代理」開啟後才採用(防偽造 header 繞過)。**已知限制**:經 Vite dev proxy 時 remoteAddr 是 proxy 所在機器,同一前端的所有使用者共享同一 IP bucket——本機單人開發影響小,部署後由信任代理設定解決 |
| 同一 user 多裝置 | 共用同一額度(per-user 本意即如此) |
| `enabled=false` | Filter 直接放行,供本地開發/壓測關閉 |
| 併發測試(路徑 C)與限流衝突 | 併發防超賣測試需以測試設定放寬 checkout 限流或關閉,避免測試互相干擾 |

## 6. 非目標(範圍外)

- 不引入 gateway(Nginx / Spring Cloud Gateway)——留給部署階段。
- 不做黑白名單、封鎖時長遞增(ban)、驗證碼挑戰等進階風控。
- 不做分散式多節點的精確全域配額(單節點 + 共用 Redis 已足夠現階段)。

## 7. 驗收條件

- AC-1:對覆寫端點連打超過限制 → 429 + `Retry-After`;限制內正常回應。
- AC-2:全域預設對未覆寫端點生效(超過 120 次/分被擋)。
- AC-3:不同 user、不同 IP 計數互相隔離。
- AC-4:視窗過期後計數重置,恢復可用。
- AC-5:命中端點覆寫的請求不重複計入全域額度。
- AC-6:`GET /api/health` 完全不受限。
- AC-7:Redis 不可用時請求放行(fail-open)且有 error log。
- AC-8:429 回應格式與全域錯誤格式一致;前端顯示友善訊息。
- AC-9:調整設定檔數值/新增規則後(重啟生效)行為隨之改變,不需改程式。
- AC-10:`enabled=false` 時所有限流失效。

## 8. 測試要求

- Filter 單元測試:計數與 429、鍵隔離(user/IP)、覆寫優先於全域、排除清單、fail-open(mock Redis 拋例外)、enabled 開關。
- 整合測試(需 Redis):對 checkout 打 6 次驗證第 6 次 429 且 `Retry-After` 合理;視窗重置(以短視窗設定測,不 sleep 長時間)。
- 前端:http.js 對 429 的統一提示。
- 確認既有全部測試(含未來的併發測試)在測試設定下不被限流干擾。
- 跑過後端 `mvn test` 全套與前端 `npm test` 全套。

## 9. 決策紀錄

| # | 問題 | 決定 | 理由 |
|---|------|------|------|
| 1 | 放 gateway 還是服務層? | 服務層先做,gateway 等部署階段再補 | 現階段無 gateway,為限流引入 gateway 成本不划算;per-user 限流本就該貼近業務層 |
| 2 | 全域還是端點限流? | 全域預設(鬆)+ 端點覆寫(緊) | 使用者提出全域直覺,經討論採分層:全域防異常流量、覆寫防針對性濫用,一套 Filter 同時滿足 |
| 3 | Redis 故障時? | fail-open 放行 + log | 限流是保護手段,不能反過來成為單點故障 |
| 4 | 演算法? | 固定視窗(Lua: INCR+EXPIRE) | 實作最簡單;邊界突刺 2 倍已知且接受,需要時再演進 |
| 5 | 具體數值 | 見第 2 節表格,草稿值 | 定稿時確認,設定檔可隨時調 |
| 6 | Filter 擺位與註冊(設計審查後修正) | 進 security chain(JwtAuthFilter 後、授權前)+ 停用 servlet 自動註冊 | 放 chain 尾端會使「未登入全域規則」無適用對象;雙重註冊會使限額砍半 |
| 7 | 429 欄位名(設計審查後修正) | `error`(非 `message`) | 對齊 `GlobalExceptionHandler` 與前端 `http.js` 既有格式,否則前端只會顯示 fallback 訊息 |
