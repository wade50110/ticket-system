# Ticket System v0.3

> 搶票結帳：Redis 為庫存正源、可切換的分散式鎖（Redisson / 手刻 Lua）、Mock 付款、訂單查詢

發布日期：2026-05-19（規劃）

---

## 一、本版本新增內容

| 項目 | 狀態 |
|------|------|
| Redis 為庫存正源（`ticket:stock:{id}`） | 📐 規劃 |
| 分散式鎖介面 `DistributedLock` | 📐 規劃 |
| Redisson 實作（預設） | 📐 規劃 |
| 手刻 Lua + `SET NX PX` 實作（學習用） | 📐 規劃 |
| 透過 properties 切換鎖實作 | 📐 規劃 |
| 結帳 endpoint `POST /api/checkout`（購物車一次結） | 📐 規劃 |
| 多 ticket 結帳：固定排序加鎖防死鎖 | 📐 規劃 |
| `PaymentService` 介面 + `MockPaymentService` 必定成功 | 📐 規劃 |
| 訂單模組（`orders` / `order_items` 兩張表，快照價格名稱） | 📐 規劃 |
| 顧客查詢自己的訂單 / 明細 | 📐 規劃 |
| Stock Redis → DB 非同步回寫（`@Async` + 重試） | 📐 規劃 |
| `stock_sync_failed` 表記錄回寫失敗，供補償 | 📐 規劃 |
| 加入購物車的庫存檢查改讀 Redis | 📐 規劃 |
| 並發壓測腳本（PowerShell / curl 迴圈） | 📐 規劃 |
| 真實金流串接 | ❌（v0.4+） |
| 訂單取消 / 退票 | ❌（v0.4+） |
| 搶票排隊（queue） | ❌（v0.4+） |

---

## 二、為什麼這樣設計

### 2.1 為何 Redis 為庫存正源

搶票場景的瓶頸是「**庫存的原子扣減 + 高 QPS**」。

| 方案 | 優點 | 缺點 |
|------|------|------|
| DB 為主、Redis 只當鎖 | 實作簡單、強一致 | DB 行鎖造成單行熱點，QPS 撐不過幾百 |
| **Redis 扣 + async sync DB**（本版採用） | 扣減極快、原生原子 | DB 短暫落後，需要 sync 重試機制 |
| Redis 為主、DB 用 CDC | 工業級擴展性 | 架構過重，學習階段不必要 |

對應的取捨：
- 結帳時**只信 Redis**，扣成功就算扣成功 → 不會超賣
- DB 的 `tickets.stock` 變成「事後紀錄」，落後 Redis 幾百 ms 是可接受的
- 重啟時若 Redis key 不存在，從 DB 補載；若已存在，**不要覆蓋**（Redis 才是即時值）

### 2.2 為何鎖實作要可切換

| 實作 | 適用場景 |
|------|----------|
| **Redisson**（預設） | Production：成熟、Watchdog 自動續期、Reentrant、有完整測試 |
| **手刻 Lua + SET NX PX** | 學習 / 面試：看得見鎖的本質（token、TTL、Lua 釋放原子性） |

用 `ticket.lock.type=redisson` 或 `manual` 在 `application.yml` 切換，介面相同，業務層不變。

### 2.3 為何要排序加鎖

多張票一起結帳時，若顧客 A 鎖 `ticket:1` 等 `ticket:2`，顧客 B 鎖 `ticket:2` 等 `ticket:1` → **死鎖**。

**解法：**所有 ticketId 按數字升冪排序後依序 lock，全世界顧客都用同一順序就不會死鎖。經典做法，銀行轉帳兩個帳號同時鎖也是這招。

---

## 三、新增 / 修改的 API 規格

### 結帳（需 `ROLE_CUSTOMER`）

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/checkout` | 結帳目前購物車的所有項目 |

Request body：無（讀當前 user 的購物車）

成功回應（200）：
```json
{
  "orderId": 12,
  "orderNo": "ORD-20260519221103456-a1b2c3d4",
  "status": "PAID",
  "totalAmount": 14400.00,
  "items": [
    { "ticketId": 1, "ticketName": "周杰倫嘉年華 2026", "unitPrice": 4800.00, "quantity": 2, "subtotal": 9600.00 },
    { "ticketId": 3, "ticketName": "五月天演唱會", "unitPrice": 2400.00, "quantity": 2, "subtotal": 4800.00 }
  ],
  "paidAt": "2026-05-19T22:11:03"
}
```

失敗回應（409）：
```json
{ "error": "票券「五月天演唱會」庫存不足，剩餘 1 張" }
```

```json
{ "error": "搶票人潮過多，請稍後再試" }
```

### 顧客訂單查詢（需 `ROLE_CUSTOMER`，只能看自己的）

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/orders` | 列出自己的所有訂單（依時間倒序） |
| GET | `/api/orders/{id}` | 單筆訂單明細（含品項） |

GET `/api/orders` 回應：
```json
[
  { "id": 12, "orderNo": "ORD-20260519221103456-a1b2c3d4", "status": "PAID", "totalAmount": 14400.00, "paidAt": "2026-05-19T22:11:03" }
]
```

GET `/api/orders/{id}` 回應與 checkout 成功回應同結構。

---

## 四、資料庫變更

JPA `ddl-auto: update` 會自動建立：

### `orders` 表

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGINT PK | |
| `order_no` | VARCHAR(40) UNIQUE | `ORD-{yyyyMMddHHmmssSSS}-{UUID短8碼}`，時間戳前綴使 DB index 自然有序、UUID 防碰撞 |
| `user_id` | BIGINT FK | 訂購人 |
| `total_amount` | DECIMAL(10,2) | 訂單總金額 |
| `status` | VARCHAR(20) | `PENDING` / `PAID` / `FAILED` |
| `payment_transaction_id` | VARCHAR(64) | Mock 付款回傳的交易序號 |
| `created_at` | TIMESTAMP | |
| `paid_at` | TIMESTAMP NULL | |

### `order_items` 表

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGINT PK | |
| `order_id` | BIGINT FK | |
| `ticket_id` | BIGINT FK | 保留參照（不級聯刪） |
| `ticket_name` | VARCHAR(200) | **快照**，訂單後改名不影響 |
| `unit_price` | DECIMAL(10,2) | **快照**，訂單後降價不影響 |
| `quantity` | INT | |
| `subtotal` | DECIMAL(10,2) | |

### `stock_sync_failed` 表（補償機制）

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGINT PK | |
| `ticket_id` | BIGINT | 哪張票 |
| `delta` | INT | 要套用的數量變化（負數=扣、正數=加） |
| `order_id` | BIGINT NULL | 來源訂單（方便追溯） |
| `retry_count` | INT | `@Retryable` 已重試次數 |
| `last_error` | VARCHAR(500) | 最後一次失敗訊息 |
| `created_at` | TIMESTAMP | |
| `resolved_at` | TIMESTAMP NULL | NULL = 未處理，有值 = 已補回 DB |

**處理路徑：**
1. `@Retryable(3 次)` 都失敗 → `@Recover` 寫一筆到 `stock_sync_failed`（`resolved_at` 為 NULL）
2. 排程 `@Scheduled(fixedDelay = 60_000)` 每分鐘掃 `resolved_at IS NULL` 的記錄、再試一次
3. 成功 → 設 `resolved_at = now()`
4. Admin endpoint `POST /api/admin/stock-sync/retry` 可手動觸發重試（緊急補救用）

---

## 五、結帳流程（核心）

```
POST /api/checkout
   │
   ▼
讀取 user 購物車 cart_items（空 → 400）
   │
   ▼
ticketIds 升冪排序（防死鎖關鍵）
   │
   ▼
依序 tryLock("lock:ticket:{id}", wait=3s, lease=5s)
   │
   ▼  全部拿到鎖
   │
為每張票執行 Lua 原子扣減：
   IF stock >= qty THEN DECRBY ; RETURN newStock
   ELSE RETURN -1（庫存不足）
   │
   ├─ 任一張失敗 → INCRBY 回滾已扣的 → 釋放鎖 → 409
   ▼  全部扣成功
   │
建立 Order(status=PENDING) + OrderItems（價格、名稱快照）
   │
   ▼
paymentService.charge(orderId, total)   ← Mock 必回 success
   │
   ├─ 失敗 → INCRBY 回滾所有扣減 → Order=FAILED → 釋放鎖 → 500
   ▼  成功
   │
Order=PAID, paidAt=now, 清空購物車
   │
   ▼
publishEvent(StockChangedEvent)   ← 觸發 @Async 寫 DB
   │
   ▼
釋放所有鎖（finally 區塊保證執行）
   │
   ▼
回傳 Order 詳情
```

**關鍵點：**
- 鎖只負責「同一張票同時間只有一個 thread 在判斷+扣」，**不負責**保證庫存不超賣
- 真正不超賣靠 Redis 內的 Lua **原子**判斷扣減
- 鎖是為了避免 `GET → 判斷 → DECRBY` 三步之間被插隊；改用單一 Lua 後鎖其實可選，但保留鎖以練習「分散式鎖 + 業務邏輯包覆」的模式（業界常見：鎖內可能還有 DB 寫入、IO 等需要序列化）

---

## 六、分散式鎖介面設計

```java
public interface DistributedLock {
    /**
     * 嘗試取得鎖
     * @param key      鎖 key（例：lock:ticket:5）
     * @param waitTime 等待多久放棄
     * @param leaseTime 拿到後最長持有多久（防 client 死掉）
     * @return 鎖把手；isLocked()==false 表示沒拿到
     */
    LockHandle tryLock(String key, Duration waitTime, Duration leaseTime);
}

public interface LockHandle extends AutoCloseable {
    boolean isLocked();
    void release();
    @Override default void close() { release(); }
}
```

### 6.1 Redisson 實作（預設）

```java
@ConditionalOnProperty(name = "ticket.lock.type", havingValue = "redisson", matchIfMissing = true)
class RedissonDistributedLock implements DistributedLock {
    private final RedissonClient client;
    public LockHandle tryLock(String key, Duration wait, Duration lease) {
        RLock lock = client.getLock(key);
        try {
            boolean ok = lock.tryLock(wait.toMillis(), lease.toMillis(), MILLISECONDS);
            return new RedissonHandle(lock, ok);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RedissonHandle(lock, false);
        }
    }
}
```

### 6.2 手刻 Lua 實作

加鎖：`SET key {uuid-token} NX PX {leaseMillis}`，輪詢 + back-off 直到 waitTime 到。

解鎖（必須用 Lua 確保「比對 token + 刪除」原子）：
```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

```java
@ConditionalOnProperty(name = "ticket.lock.type", havingValue = "manual")
class RedisTemplateDistributedLock implements DistributedLock { ... }
```

### 6.3 切換設定

```yaml
ticket:
  lock:
    type: redisson         # redisson | manual
    wait-millis: 200       # 短等待：搶票場景沒拿到鎖等下去也大概率沒貨，fail-fast 給 client 重試
    lease-millis: 3000     # 業務邏輯通常 < 500ms，給 6x buffer；不過長避免 thread 死掉鎖卡其他人
```

**參數參考來源**：對齊美團秒殺技術文、阿里 Sentinel 雙十一秒殺、京東搶購的常見設定（`wait` 0~200ms、`lease` 2~5s）。Redisson 預設 Watchdog（30s + 續期）較適合一般業務，秒殺場景偏好「短 lease + 不續期」。

---

## 七、Lua 腳本（扣庫存）

```lua
-- KEYS[1] = ticket:stock:{id}
-- ARGV[1] = quantity
local v = redis.call('GET', KEYS[1])
if not v then return -2 end                  -- key 不存在（票券下架/不存在）
local stock = tonumber(v)
if stock < tonumber(ARGV[1]) then return -1 end  -- 庫存不足
return redis.call('DECRBY', KEYS[1], ARGV[1])    -- 回傳扣完後的庫存
```

回滾用 `INCRBY`，不需要 Lua（單一指令本身就是原子的）。

---

## 八、Redis ↔ DB 同步策略

```
                Redis (即時)
                  ▲
                  │ 啟動載入（key 不存在時）
                  │
                tickets.stock (DB) ← 異步 update
                  ▲
                  │ @Async @EventListener StockChangedEvent
                  │
                結帳成功 publishEvent
```

| 時機 | 動作 |
|------|------|
| App 啟動 | `@PostConstruct` 把所有 `tickets` 載入 Redis（**僅當 key 不存在**） |
| Admin 新增票券 | 雙寫：`tickets` insert + `SET ticket:stock:{id}` |
| Admin 修改票券 stock | 雙寫：`tickets` update + `SET ticket:stock:{id}`（**注意：會覆蓋當下 Redis 即時值，後台改庫存通常是上架前/活動結束時做**） |
| Admin 刪除票券 | 雙寫：`tickets` delete + `DEL ticket:stock:{id}` |
| 結帳扣庫存 | Redis DECRBY → 事件通知 → `@Async` 更新 DB |
| DB 同步失敗 | `@Retryable` 3 次（間隔 500/1000/2000 ms）；最終失敗只 log，**不影響使用者**（Redis 才是正源） |

> Trade-off：DB 同步失敗時 DB 數字會略小於實際剩餘票數。Production 級需要 outbox 表 + CDC，超過 v0.3 範圍。

---

## 九、付款 Mock

```java
public interface PaymentService {
    PaymentResult charge(Long orderId, BigDecimal amount);
}

public record PaymentResult(boolean success, String transactionId, String message) {}

@Service
class MockPaymentService implements PaymentService {
    public PaymentResult charge(Long orderId, BigDecimal amount) {
        log.info("[MOCK PAY] order={} amount={} → success", orderId, amount);
        return new PaymentResult(true, "MOCK-" + UUID.randomUUID(), "ok");
    }
}
```

v0.4+ 換真實金流（ECPay / Stripe）時只換 `@Service` 實作 bean，**業務層完全不動**。

---

## 十、專案結構新增

```
ticket-system/backend/src/main/java/com/example/ticket/
├── lock/                                   ★ 分散式鎖模組
│   ├── DistributedLock.java                ★ 介面
│   ├── LockHandle.java                     ★ 介面
│   ├── RedissonDistributedLock.java        ★ 預設實作
│   ├── RedisTemplateDistributedLock.java   ★ 手刻實作
│   └── LockConfig.java                     ★ @ConditionalOnProperty 切換
├── stock/                                  ★ Redis 庫存模組
│   ├── StockRedisRepository.java           ★ Lua DECRBY / GET / SET / DEL
│   ├── StockBootstrap.java                 ★ 啟動載入 DB → Redis
│   └── StockChangedEvent.java              ★ 結帳完事件
├── order/                                  ★ 訂單模組
│   ├── Order.java                          ★ Entity
│   ├── OrderItem.java                      ★ Entity
│   ├── OrderRepository.java                ★
│   ├── OrderService.java                   ★
│   ├── OrderController.java                ★ /api/orders
│   └── dto/{OrderResponse, OrderItemResponse}
├── checkout/                               ★ 結帳模組
│   ├── CheckoutService.java                ★ 結帳主流程
│   ├── CheckoutController.java             ★ /api/checkout
│   ├── StockSyncListener.java              ★ @Async + @Retryable 同步 DB
│   ├── StockSyncFailed.java                ★ Entity（補償表）
│   ├── StockSyncFailedRepository.java      ★
│   ├── StockSyncFailedScheduler.java       ★ @Scheduled 每分鐘掃失敗記錄重試
│   └── AdminStockSyncController.java       ★ /api/admin/stock-sync/retry（手動觸發）
├── payment/                                ★ 付款模組
│   ├── PaymentService.java                 ★ 介面
│   ├── MockPaymentService.java             ★ Mock
│   └── PaymentResult.java                  ★
├── ticket/
│   └── TicketService.java                  ☆ admin CRUD 加上 Redis 雙寫
├── cart/
│   └── CartService.java                    ☆ 加購物車時改用 Redis 檢查庫存
└── config/
    └── RedisConfig.java                    ★ RedissonClient bean、Lua script bean

ticket-system/frontend/src/
├── api/
│   └── orders.js                           ★ checkout / orders / orderDetail
└── pages/
    ├── Cart.jsx                            ☆ 加「結帳」按鈕
    ├── Orders.jsx                          ★ 訂單列表
    └── OrderDetail.jsx                     ★ 訂單明細
```

★ 新增　☆ 修改

---

## 十一、新增依賴 / 設定

### `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

### `application.yml`（新增段）

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

ticket:
  lock:
    type: redisson        # redisson | manual
    wait-millis: 200      # 大廠搶票常見值
    lease-millis: 3000

# 啟用 @Async / @Retryable / @Scheduled
```

並在主 application class 加 `@EnableAsync @EnableRetry @EnableScheduling`。

### `docker-compose.yml`（新增 service）

```yaml
redis:
  image: redis:7-alpine
  container_name: ticket-redis
  restart: unless-stopped
  ports:
    - "6379:6379"
  volumes:
    - ticket-redis-data:/data
```

---

## 十二、操作流程驗證

### 路徑 A：基本搶票（單人單張）
1. Admin 上架票券 stock=5
2. 顧客加入購物車 1 張、結帳 → `200`、Order=PAID
3. Redis `ticket:stock:1` 應為 `4`
4. DB `tickets.stock` 應在 1 秒內變成 `4`（async sync）
5. 顧客 `GET /api/orders` 看得到該筆

### 路徑 B：庫存不足
1. Admin 上架 stock=2
2. 顧客購物車放 3 張 → 結帳 → `409 庫存不足`
3. Redis 與 DB stock 都應**仍為 2**（rollback 成功）

### 路徑 C：搶票競爭（核心驗證不超賣）
1. Admin 上架 stock=10
2. 用 PowerShell / curl 同時發 50 個結帳請求（每個 1 張）
3. 預期：10 個成功、40 個 `409 庫存不足`
4. Redis stock=0、DB stock 最終=0、orders 表恰好 10 筆 PAID
5. **絕不能**出現 11 個 PAID

### 路徑 D：切換鎖實作
1. `application.yml` 改 `ticket.lock.type: manual`
2. 重啟，重跑路徑 C，結果應**完全一致**
3. log 中可看到 `RedisTemplateDistributedLock` 而非 `RedissonDistributedLock`

### 路徑 E：DB 同步失敗演練
1. 結帳成功後，**停掉** MySQL container
2. Redis 顯示已扣，DB 同步 retry 失敗，log 出 `WARN`
3. 重啟 MySQL → 手動觸發或下次扣減成功後狀態收斂
4. 驗證：使用者請求**不受影響**（因 Redis 才是正源）

---

## 十三、已知限制 / v0.4+ 規劃

- 沒有真實金流（Mock 必成功）
- 沒有訂單取消、退票流程
- DB 同步失敗有 `stock_sync_failed` 表 + 排程重試，但仍非 outbox / CDC 等級的強一致
- 沒有搶票排隊（lock 等不到直接回 409，沒有 queue 機制）
- 沒有限制單帳號搶票數量（防黃牛）
- 壓測腳本僅為簡易並發，未涵蓋 long-running soak test

---

## 十四、參考

- v0.2 release note：[`./version0.2.md`](./version0.2.md)
- 完整系統規劃：[`../ticket-system-plan.md`](../ticket-system-plan.md)
- Redisson 文件：https://github.com/redisson/redisson/wiki
- Spring `@Async` + `@Retryable` 使用模式
