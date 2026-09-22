# 庫存與分散式鎖模組(stock / lock)

> 現況規格,對應程式碼為準。最後核對:2026-08-31。
> **這是防超賣的核心。改動前務必整份讀完,特別是「設計意圖」。**

## 現況規格

### 庫存正源原則
- **Redis 是庫存唯一正源**,key 格式 `ticket:stock:{ticketId}`(值 = 剩餘張數)。
- DB `tickets.stock` 只是**事後非同步回寫的紀錄**,允許短暫落後,絕不能當即時庫存用。
- 啟動載入(`StockBootstrap`,`@PostConstruct`):逐票 `setIfAbsent` — **key 已存在一律跳過不覆寫**,因為 Redis 上的才是即時值,DB 數字可能是舊的。

### Lua 原子扣減(`StockRedisRepository.tryDecrement`)
Lua 腳本(內嵌於 Java text block,resources 下沒有 .lua 檔):

```lua
local v = redis.call('GET', KEYS[1])
if not v then return -2 end
local stock = tonumber(v)
local qty = tonumber(ARGV[1])
if stock < qty then return -1 end
return redis.call('DECRBY', KEYS[1], qty)
```

- 回傳語意:`-2` = key 不存在(票未上架/已刪)、`-1` = 庫存不足、`≥0` = 扣減後剩餘量。
- `GET → 判斷 → DECRBY` 在單一腳本內原子完成 — **這是不超賣的真正保證**。
- 回補(`increment`)用單指令 `INCRBY`(本身原子,不需腳本)。
- 其他方法:`get` / `set`(無條件覆寫,危險,見已知問題)/ `setIfAbsent` / `delete`。

### 限購額度(quota,v0.5,`QuotaRedisRepository`)
- key `ticket:quota:{ticketId}:{userId}`(值 = 該帳號該票的目前持有數 = PENDING+PAID 訂單張數),無 TTL。
- **與庫存扣減同一支 Lua** `tryDecrementStockWithQuota`:GET 額度 → 判斷 `held+qty ≤ limit` → GET 庫存 → 判斷足量 → `DECRBY 庫存` + `INCRBY 額度`,原子完成。回傳碼:`-3` 超過限購、`-2` 庫存 key 不存在、`-1` 庫存不足、`≥0` 扣完剩餘庫存。`limit` 傳 `-1` 代表不限購(仍累計持有數)。**這是防同帳號併發繞過限購的關鍵**。
- 釋回額度(退票/取消/結帳回滾)走 `release` 的 Lua:key 不存在不動作、扣到低於 0 夾至 0 —— **quota 值任何情況不得為負**(裸 DECRBY 對不存在 key 會建負值 = 送額度超買)。
- 啟動重建 `QuotaBootstrap`:比照 `StockBootstrap`,`setIfAbsent` 從 DB `orders`(PENDING+PAID)依 (ticketId,userId) 彙總;修不了「存在但值錯」的 key。
- 錯誤值收斂靠 admin 對帳 `POST /api/admin/quota/reconcile`(`QuotaReconcileService`):以 DB 彙總覆寫全部 quota key、刪除 DB 已無持有的 stale key。

### 分散式鎖(lock/)
- 介面 `DistributedLock.tryLock(key, waitTime, leaseTime)` 回傳 `LockHandle`(`AutoCloseable`);業務層只依賴介面。
- 兩種實作,以 `ticket.lock.type` 切換(`LockConfig` @ConditionalOnProperty):

| | `redisson`(預設,含未設定時) | `manual` |
|---|---|---|
| 實作 | Redisson RLock | `SET NX PX` + 自旋(50ms 輪詢) |
| 釋放 | `unlock()`,先檢 `isHeldByCurrentThread` | Lua 比對 uuid token 才 DEL(防誤刪他人鎖) |
| 可重入 | 是 | 否 |

- 參數(`LockProperties`,`application.yml` `ticket.lock.*`):`waitMillis=200`(fail-fast,搶不到就回「人潮過多」)、`leaseMillis=3000`(TTL 自動釋放,防持鎖者當機死鎖)。
- **鎖 key 由業務層組**:`lock:ticket:{ticketId}`(在 `CheckoutService`,不在 lock 模組)。
- **多票防死鎖**:結帳時所有 ticketId **升冪排序後依序加鎖、反向釋放**(在 `CheckoutService`)— 全體使用者同一全域順序,不會循環等待。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| Lua 扣減 / INCRBY 回補 / key 命名 | `backend/src/main/java/com/example/ticket/stock/StockRedisRepository.java` |
| 啟動載入(setIfAbsent) | `stock/StockBootstrap.java` |
| 鎖介面 | `lock/DistributedLock.java`、`lock/LockHandle.java` |
| Redisson 實作 | `lock/RedissonDistributedLock.java` |
| 手刻 Lua 實作 | `lock/RedisTemplateDistributedLock.java` |
| 切換設定 | `lock/LockConfig.java`、`lock/LockProperties.java`、`application.yml`(`ticket.lock.*`) |
| 鎖的使用端(排序加鎖) | `checkout/CheckoutService.java` |

## 設計意圖(不要動的理由)

1. **`StockBootstrap` 用 `setIfAbsent` 是鐵律**:重啟不能用 DB 覆蓋 Redis。任何「同步 DB 到 Redis」的新功能都必須遵守這個方向性(Redis→DB 單向)。
2. **扣減必須走 Lua 腳本**,不要改成「先 GET 再 DECRBY」兩步 — 那會重新引入超賣 race。
3. **waitMillis 刻意短(200ms)**:搶票場景要 fail-fast 回「請稍後再試」,不要排長隊等鎖;leaseMillis 給業務時間 6 倍以上 buffer。
4. 兩種鎖實作同介面,是為了教學比較與可替換性;業務層不得依賴任一實作的特有行為(例如 Redisson 的可重入)。

## 已知邊界情況

- 鎖釋放失敗只 log 不拋(有 TTL 兜底)。
- 執行緒中斷時補 `Thread.currentThread().interrupt()` 並回未取得鎖。
- DB 端扣減 SQL 帶 `WHERE stock >= :qty` 條件,兜底防 DB 庫存變負(`TicketRepository.decrementStock`)。

## 已知問題 / 技術債(記錄,尚未修正)

1. **`TicketService` create/update 用 `set()` 無條件覆寫 Redis**(見 ticket.md 問題 1)— 與本模組「已存在不可覆蓋」的原則衝突,搶購中改票會洗掉即時庫存。
2. 手刻鎖自旋 50ms 輪詢,高併發下對 Redis 有額外壓力(預設用 Redisson 無此問題)。

## 測試現況

- **本模組無任何測試**(含兩種鎖實作、Lua 腳本)。補測試時優先:併發扣減不超賣(整合測試,需要 Redis)、key 不存在/不足的回傳值、鎖 token 誤刪防護。
