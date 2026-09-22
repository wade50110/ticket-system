# 排程防重:@Scheduled 在多 pod 下的問題與 ShedLock 解法

> 對應決策 3(架構總覽 architecture.md)。這份講清楚:問題是什麼、ShedLock 怎麼運作、這個專案具體要怎麼加。

## 一、問題:為什麼多 pod 排程會出事

現在後端只跑一個實例,`@Scheduled` 排程一分鐘觸發一次,沒問題。但 **autoscale 後後端會有 2、3、N 個 pod,而每個 pod 都是一個完整的 Spring Boot,各自的 `@Scheduled` 都會照自己的時鐘觸發**。

以這個專案唯一的排程為例([StockSyncListener.java:52](../../backend/src/main/java/com/example/ticket/checkout/StockSyncListener.java)):

```java
/** 每 60 秒掃未處理的失敗記錄重試。 */
@Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
public void retryFailedRecords() {
    var pending = failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc();
    ...
    retryBatch();   // 逐筆對 tickets 做 decrementStock,成功設 resolvedAt
}
```

它的職責是:掃 `stock_sync_failed` 表裡「還沒回寫成功」的庫存變更,重試寫回 DB(庫存回寫補償機制,見 checkout.md)。

**3 個 pod 的情況下會發生什麼:**

- 三個 pod 幾乎同時(各自的 60 秒到點)都去 `findTop100...` 撈到**同一批** pending 記錄。
- 三個 pod 同時對同一批記錄跑 `retryBatch()`:
  - `decrementStock` 有 `WHERE stock >= qty` 條件更新,本身不會把 DB 庫存扣成負(兜底還在),但**三個 pod 對同一筆做三次扣減**——第一個成功扣、後兩個可能也符合條件又各扣一次,造成**重複扣減 DB 庫存**(DB 數字被多扣)。
  - 就算條件擋下重複,三倍的 DB 查詢/更新也是純浪費,還彼此競爭行鎖。

> ⚠️ **前提提醒(重要)**:上面「重複扣減」的推論**假設這個排程本來有正常運作**。但這個排程目前有一個既有 bug 讓它其實幾乎沒在動——**加 ShedLock 前必須先修**,見下方第三節「加 ShedLock 前必須先修的既有 bug」。所以正確的順序是:先修排程、補測試讓它真的會運作,再上 ShedLock 防多 pod 重複。
- **更危險的是未來的排程**:訂單取消需求書(v0.5/order-cancel)規劃了「逾時自動取消 PENDING 訂單」的 `@Scheduled`。那個排程會**釋放庫存、退額度**——多 pod 同時跑同一批逾時訂單,若沒有像退票那樣的原子狀態閘門保護,可能重複釋放。所以排程防重不是「順便」,是 autoscale 的前提。

一句話:**`@Scheduled` 的語意是「這個工作每 N 秒該被做一次」,不是「每個 pod 每 N 秒各做一次」。** 多實例下需要一個機制保證「全叢集同一時刻只有一個 pod 真的執行」。

## 二、ShedLock 怎麼運作

ShedLock 是一個專門解這件事的輕量函式庫。原理一句話:**排程要執行前,先去一個共享儲存(這個專案用 Redis)搶一把具名鎖;搶到的 pod 才執行,沒搶到的直接跳過這一輪。**

流程:

```
  60 秒到點,三個 pod 同時想跑 retryFailedRecords
        │              │              │
      pod1           pod2           pod3
        │              │              │
        ▼              ▼              ▼
   [ 去 Redis 搶鎖 "retryFailedRecords" ,設 TTL ]
        │              │              │
     搶到 ✓         沒搶到 ✗        沒搶到 ✗
        │              │              │
     執行排程        跳過            跳過
        │
     執行完釋放鎖(或到 TTL 自動過期)
```

幾個關鍵設定(都在 `@SchedulerLock` 註解上):

- **`name`**:鎖的名字,同名排程全叢集共用一把鎖。
- **`lockAtMostFor`**:鎖最長持有多久。**保險絲**——如果搶到鎖的 pod 執行到一半當機、沒能正常釋放鎖,鎖會在這個時間後自動過期,否則排程會永遠卡住沒人能跑。要設得比「這個排程正常最久跑多久」還長一些。
- **`lockAtLeastFor`**:鎖最短持有多久。**防抖動**——就算排程 0.1 秒就跑完,也硬持有這麼久才放鎖,避免多個 pod 的時鐘太接近時、前一個剛放鎖後一個馬上搶到又跑一次。

和 Redisson 分散式鎖的關係:概念一樣(都是拿共享儲存當鎖),但 ShedLock 是**專為排程設計**的封裝——你只加一個註解,不用自己寫搶鎖/釋放/TTL 的邏輯。這個專案已經有 Redis 而且已經在用 Redisson,ShedLock 直接複用同一個 Redis,不引入新的基礎設施。

## 三、這個專案具體怎麼加

### 步驟 1:加依賴(pom.xml)

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>5.13.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-redis-spring</artifactId>
    <version>5.13.0</version>
</dependency>
```

用 Redis provider(複用現有 Redis);也有 JDBC provider(用 DB 一張 shedlock 表),但這個專案 Redis 是現成的、更輕。

### 步驟 2:開啟 ShedLock(設定類)

```java
@Configuration
// 注意:@EnableScheduling 已在 TicketApplication 主類別啟用,這裡不必再加
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")   // 預設保險絲 5 分鐘
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "ticket");  // key 前綴
    }
}
```

### 步驟 3:在排程方法加 `@SchedulerLock`

```java
@Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
@SchedulerLock(
        name = "retryFailedRecords",
        lockAtMostFor = "PT2M",     // 這個排程正常幾秒內跑完,給 2 分鐘保險絲
        lockAtLeastFor = "PT5S")    // 至少持有 5 秒,防抖動
public void retryFailedRecords() {
    ...
}
```

加 ShedLock 本身就這樣:**加兩個依賴、一個設定類、一個註解**。但**在這之前必須先修下面這個既有 bug**,否則加了鎖也是保護一個本來就沒在運作的排程。

### 加 ShedLock 前必須先修的既有 bug:retryBatch 自呼叫交易失效

看 [StockSyncListener.java](../../backend/src/main/java/com/example/ticket/checkout/StockSyncListener.java):

```java
@Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
public void retryFailedRecords() {      // ← 無 @Transactional
    ...
    retryBatch();                       // ← this.retryBatch(),同類別自呼叫
}

@Transactional                          // ← 這個 @Transactional 不會生效!
public void retryBatch() {
    ...
    ticketRepo.decrementStock(...);     // @Modifying,需要交易
    f.setResolvedAt(...);               // 靠 dirty-checking flush
}
```

問題:`retryFailedRecords()` 用 `this.retryBatch()` **同類別自呼叫**,Spring AOP proxy 被繞過,`@Transactional` **不生效**。後果:

1. `decrementStock`(`@Modifying`)在沒有交易下執行 → 拋 `TransactionRequiredException`,被 catch 吞掉。
2. 就算不拋,`setResolvedAt` / `setRetryCount` 寫在 detached entity 上不會 flush → 記錄永遠 `resolvedAt IS NULL`,每分鐘被重撈。

**等於這個補償重試排程實際上什麼都沒做。** 這是既有技術債(checkout.md 已知問題 2、6 有記載),不是 ShedLock 造成的。這個專案的作者其實知道這個坑——隔壁 `StockSyncWorker` 的註解就寫了「拆獨立 bean 讓 proxy 生效」,偏偏 `retryBatch` 沒比照拆。

**修法(擇一)**:把 `retryBatch` 抽到獨立 bean(比照 `StockSyncWorker`),或把 `@Transactional` 提到 `retryFailedRecords` 上並讓實際 DB 操作經過 proxy。**修完要補測試**(這個路徑目前完全沒測試),確認 `resolvedAt` 真的會被寫、重試真的會扣。**修好、能運作了,再加 `@SchedulerLock` 防多 pod 重複。**

### 之後每加一個排程都要記得

未來訂單取消的逾時排程、任何新的 `@Scheduled`,**都要配一個 `@SchedulerLock`**(不同的 `name`)。這會是多 pod 環境的固定規矩,寫進開發規則比較保險。

## 四、注意事項 / 邊界

- **鎖失效不會造成資料錯,只會退回「多 pod 都跑」的原狀**:ShedLock 是最佳化/防重,不是正確性的最後防線。真正的正確性仍要靠業務層(如退票的 `markRefunded` 條件更新原子閘門)。所以加了 ShedLock,排程本身的冪等/條件更新也不能拿掉。
- **`lockAtMostFor` 太短會出事**:如果排程實際跑的時間超過 `lockAtMostFor`,鎖會提前過期、另一個 pod 會同時開跑。所以要設得比最壞情況的執行時間長。
- **時鐘**:各 pod 時鐘不需要完全同步,ShedLock 靠 Redis 的鎖狀態判斷,不靠 pod 本地時間比較。
- ShedLock 只保證「同一時刻一個」,不保證「哪一個 pod」——每輪可能是不同 pod 搶到,這沒關係。

## 五、驗證方式(實作後)

- 起 3 個 backend pod,故意在 `stock_sync_failed` 塞幾筆 pending 記錄。
- 看 log:應該只有一個 pod 印 `[StockSync] retrying N failed records`,另兩個那一輪沒印(被鎖擋掉)。
- Redis 上會看到 `ticket:...retryFailedRecords` 之類的鎖 key,在鎖期間存在。
