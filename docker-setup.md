# Windows 用 Docker 建立 MySQL + Redis

> 環境：Windows 11、Docker Desktop
> 目標：用 Docker Compose 跑一組乾淨的 MySQL 8（給訂單／使用者）與 Redis 7（給分散式鎖與庫存正源），給 ticket-system 後端連線
> 對應版本：v0.3+（v0.2 之前只需要 MySQL，可只啟動 mysql service）

---

## 一、前置作業

### 1. 安裝 Docker Desktop
- 下載：https://www.docker.com/products/docker-desktop/
- 安裝完開啟 Docker Desktop，等右下角小鯨魚圖示變綠（狀態：Running）

### 2. 驗證安裝
PowerShell 執行：
```powershell
docker --version
docker info
```
能正常輸出版本與資訊就代表 OK。

---

## 二、Port 對應

本專案使用以下 host → container port 對應，避免跟本機已裝的 MySQL / Redis 衝突：

| Service | Host port | Container port | Spring Boot 連線 URL |
|---------|-----------|----------------|----------------------|
| MySQL   | `3307`    | `3306`         | `jdbc:mysql://localhost:3307/ticketdb` |
| Redis   | `6380`    | `6379`         | `localhost:6380` |

如果你本機 6380 已被佔用，改 compose 的左側即可（例如改成 `6381:6379`），對應 Spring `spring.data.redis.port=6381`。

---

## 三、啟動：docker compose 一鍵跑 MySQL + Redis（推薦）

專案根目錄 `ticket-system/docker-compose.yml` 已內含兩個 service：

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: ticket-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ticketdb
      TZ: Asia/Taipei
    ports:
      - "3307:3306"
    volumes:
      - ticket-mysql-data:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci

  redis:
    image: redis:7-alpine
    container_name: ticket-redis
    restart: unless-stopped
    ports:
      - "6380:6379"
    volumes:
      - ticket-redis-data:/data

volumes:
  ticket-mysql-data:
  ticket-redis-data:
```

啟動：
```powershell
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
docker compose up -d
```

第一次會自動下載 image，約 30~60 秒。

---

## 四、單獨啟動（不用 compose）

若想單純跑指令，照下列順序：

### MySQL
```powershell
docker run -d `
  --name ticket-mysql `
  -e MYSQL_ROOT_PASSWORD=root `
  -e MYSQL_DATABASE=ticketdb `
  -e TZ=Asia/Taipei `
  -p 3307:3306 `
  -v ticket-mysql-data:/var/lib/mysql `
  --restart unless-stopped `
  mysql:8.0 `
  --character-set-server=utf8mb4 `
  --collation-server=utf8mb4_unicode_ci
```

### Redis
```powershell
docker run -d `
  --name ticket-redis `
  -p 6380:6379 `
  -v ticket-redis-data:/data `
  --restart unless-stopped `
  redis:7-alpine
```

> PowerShell 的續行符號是反引號 `` ` ``，不是反斜線 `\`。

---

## 五、驗證連線

### 1. 看容器是否在跑
```powershell
docker ps
```
應同時看到：
- `ticket-mysql`：`Up`、`0.0.0.0:3307->3306/tcp`
- `ticket-redis`：`Up`、`0.0.0.0:6380->6379/tcp`

### 2. MySQL 連線測試
```powershell
docker exec -it ticket-mysql mysql -uroot -proot
```
進入後：
```sql
SHOW DATABASES;
USE ticketdb;
SHOW TABLES;
EXIT;
```
> 第一次啟動容器需要約 10-30 秒初始化資料庫，太早連會被拒，等一下再試。

### 3. Redis 連線測試
```powershell
docker exec -it ticket-redis redis-cli
```
進入後：
```
PING                       # 預期回 PONG
SET hello world
GET hello                  # 預期回 "world"
KEYS ticket:stock:*        # 後端啟動後可看到票券庫存 key
EXIT
```

### 4. 看 log
```powershell
docker logs ticket-mysql --tail 20    # 看到 "ready for connections"
docker logs ticket-redis --tail 20    # 看到 "Ready to accept connections"
```

---

## 六、Spring Boot 連線設定

`backend/src/main/resources/application.yml` 中對應段落：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/ticketdb?useSSL=false&serverTimezone=Asia/Taipei&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
    username: root
    password: root
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6380}
```

若要走 Docker 內部網路（後端也跑容器），把 host 改成 service 名 `mysql` / `redis`，並把它們加進同一個 compose network。本專案後端目前在 host 跑、Docker 跑 DB/cache，所以填 `localhost`。

---

## 七、常用維運指令

| 動作 | 指令 |
|------|------|
| 看全部容器 | `docker ps -a` |
| 停止單一 | `docker stop ticket-mysql` 或 `ticket-redis` |
| 重啟單一 | `docker start ticket-mysql` 或 `ticket-redis` |
| 看 log（持續） | `docker logs -f ticket-mysql` |
| MySQL CLI | `docker exec -it ticket-mysql mysql -uroot -proot` |
| Redis CLI | `docker exec -it ticket-redis redis-cli` |
| 刪除容器（資料保留） | `docker rm -f ticket-mysql ticket-redis` |
| 連同資料一起刪 | `docker rm -f ticket-mysql ticket-redis; docker volume rm ticket-mysql-data ticket-redis-data` |
| compose 啟動 | `docker compose up -d` |
| compose 停止 | `docker compose down` |
| compose 砍掉重來 | `docker compose down -v` （`-v` 會刪 volume） |
| compose 只啟動單一 service | `docker compose up -d redis` |
| compose 看狀態 | `docker compose ps` |

---

## 八、v0.3 場景常用的 Redis 指令

進入 `redis-cli` 後：

```
# 看所有票券庫存（v0.3 後端啟動時會自動載入）
KEYS ticket:stock:*

# 看單張票券剩餘
GET ticket:stock:1

# 看是否有殘留的鎖（正常結帳後鎖會自動釋放）
KEYS lock:ticket:*

# 手動清掉某張票券的 Redis 庫存（謹慎使用）
DEL ticket:stock:1

# 看 Redis 即時統計
INFO stats
INFO memory
```

驗證搶票不超賣的核心查詢：
```
GET ticket:stock:1                 # Redis 即時值
# 對應 MySQL：SELECT stock FROM tickets WHERE id=1;
# 兩者應在數秒內收斂（async sync）
```

---

## 九、常見問題

### Q1：port 已被佔用
症狀：`Bind for 0.0.0.0:XXXX failed: port is already allocated`
- **MySQL 3307**：本機通常沒裝；若被佔用，改 compose 為 `3308:3306`，Spring URL 同步改
- **Redis 6380**：可能本機已裝 Redis 或其他軟體。檢查並停掉：
  ```powershell
  Get-Service | Where-Object { $_.Name -like 'Redis*' }
  netstat -ano | findstr ":6380"
  ```
  或改 compose 為 `6381:6379`，並把 `application.yml` 的 `spring.data.redis.port` 改成 6381

### Q2：Spring Boot 連不上 MySQL
- 確認 `docker ps` 看到 `ticket-mysql` `Up` 超過 30 秒
- yml 中 `username/password` 是 `root/root`、port 是 **3307**
- 試：`docker exec -it ticket-mysql mysql -uroot -proot -e "SELECT 1"`

### Q3：Spring Boot 連不上 Redis
- 確認 `docker ps` 看到 `ticket-redis` `Up`
- 試：`docker exec -it ticket-redis redis-cli PING`，回 `PONG` 代表 Redis 沒問題
- 啟動 log 應出現 `[StockBootstrap] loaded=N`；若卡在那或炸 connection refused，多半是 host/port 沒對齊

### Q4：要乾淨重來
```powershell
docker compose down -v   # -v 會把兩個 volume 一起砍掉
docker compose up -d
```

### Q5：Redis 資料要不要持久化？
本專案 compose 有掛 `ticket-redis-data:/data` volume，預設 Redis 7 alpine 會做 RDB snapshot（每 N 秒）。
- 開發機需要持久化：保持現狀即可
- 想用純記憶體（重啟即清空）：把 redis service 的 `volumes` 段移除

---

## 十、安全性提醒

- `root/root` 與 Redis 無密碼設定**僅供本機開發**
- 正式環境：
  1. 用強密碼，建立應用專用 DB 帳號
  2. Redis 開啟 `requirepass`，並改用獨立 user（Redis 6 之後支援 ACL）
  3. 不要把 3307 / 6380 port 對外開放，只在 internal network 通
- `docker-compose.yml` commit 進 git 時，敏感資訊用 `.env` 抽出

---

## 十一、TL;DR

```powershell
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
docker compose up -d
docker ps                                           # 兩個容器都 Up
docker exec -it ticket-mysql mysql -uroot -proot -e "SHOW DATABASES;"
docker exec -it ticket-redis redis-cli PING         # 預期 PONG
```

兩個都通就可以啟動 Spring Boot。
