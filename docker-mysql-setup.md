# Windows 用 Docker 建立乾淨 MySQL（root/root）

> 環境：Windows 11、Docker Desktop
> 目標：用 Docker 跑一個乾淨的 MySQL 8，帳密都用 `root`，給 ticket-system 後端連線

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

## 二、啟動 MySQL 容器

### 方法 A：一行指令快速啟動（推薦初次使用）

PowerShell 執行：
```powershell
docker run -d `
  --name ticket-mysql `
  -e MYSQL_ROOT_PASSWORD=root `
  -e MYSQL_DATABASE=ticketdb `
  -e TZ=Asia/Taipei `
  -p 3306:3306 `
  -v ticket-mysql-data:/var/lib/mysql `
  --restart unless-stopped `
  mysql:8.0 `
  --character-set-server=utf8mb4 `
  --collation-server=utf8mb4_unicode_ci
```

> PowerShell 的續行符號是反引號 `` ` ``，不是反斜線 `\`。

參數說明：
| 參數 | 用途 |
|------|------|
| `--name ticket-mysql` | 容器名稱，之後 start/stop 都用這個名字 |
| `-e MYSQL_ROOT_PASSWORD=root` | root 密碼設為 `root` |
| `-e MYSQL_DATABASE=ticketdb` | 啟動時自動建立 `ticketdb` 資料庫 |
| `-p 3306:3306` | 把容器的 3306 port 對應到本機 3306 |
| `-v ticket-mysql-data:/var/lib/mysql` | 用 Docker volume 永久保存資料（即使刪容器也不會掉資料） |
| `--restart unless-stopped` | 重開機後自動啟動容器 |
| `--character-set-server=utf8mb4` | 預設字元集，支援中文 emoji |

### 方法 B：docker-compose（推薦長期使用）

在 `ticket-system/` 下建立 `docker-compose.yml`：
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
      - "3306:3306"
    volumes:
      - ticket-mysql-data:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci

volumes:
  ticket-mysql-data:
```

啟動：
```powershell
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
docker compose up -d
```

---

## 三、驗證連線

### 1. 看容器是否在跑
```powershell
docker ps
```
應看到 `ticket-mysql` 狀態為 `Up`、port 對應 `0.0.0.0:3306->3306/tcp`。

### 2. 進容器用 mysql cli
```powershell
docker exec -it ticket-mysql mysql -uroot -proot
```
進入後試：
```sql
SHOW DATABASES;
USE ticketdb;
SHOW TABLES;
EXIT;
```

> 第一次啟動容器需要約 10-30 秒初始化資料庫，太早連會被拒，等一下再試即可。

### 3. 看 log 確認啟動完成
```powershell
docker logs ticket-mysql --tail 20
```
看到 `ready for connections` 就代表已可連線。

---

## 四、Spring Boot 連線設定

要對齊本次 docker 設定，請把 `backend/src/main/resources/application.yml` 改回 `ticketdb` 與 `root/root`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticketdb?useSSL=false&serverTimezone=Asia/Taipei&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
```

> 若你想沿用目前 yml 裡的 `test` 資料庫與 `xpec` 帳號，可以改 docker 指令的 `MYSQL_DATABASE=test`，並在容器啟動後額外建立 `xpec` 使用者；但既然要乾淨，建議直接統一成 `ticketdb` + `root/root`。

---

## 五、常用維運指令

| 動作 | 指令 |
|------|------|
| 看容器狀態 | `docker ps -a` |
| 停止 | `docker stop ticket-mysql` |
| 重新啟動 | `docker start ticket-mysql` |
| 看 log | `docker logs -f ticket-mysql` |
| 進 shell | `docker exec -it ticket-mysql bash` |
| 進 MySQL CLI | `docker exec -it ticket-mysql mysql -uroot -proot` |
| 刪除容器（資料保留） | `docker rm -f ticket-mysql` |
| 連同資料一起刪 | `docker rm -f ticket-mysql; docker volume rm ticket-mysql-data` |
| compose 啟動 | `docker compose up -d` |
| compose 停止 | `docker compose down` |
| compose 砍掉重來 | `docker compose down -v` （`-v` 會刪 volume） |

---

## 六、常見問題

### Q1：port 3306 已被佔用
症狀：`Bind for 0.0.0.0:3306 failed: port is already allocated`
- 你本機可能已安裝 MySQL Service。檢查並停掉：
  ```powershell
  Get-Service | Where-Object { $_.Name -like 'MySQL*' }
  Stop-Service MySQL80      # 名稱依實際為準
  ```
- 或改用其他 port，例如 `-p 3307:3306`，Spring Boot URL 也要改成 `localhost:3307`

### Q2：Spring Boot 連不上
- 確認 `docker ps` 看到容器 `Up` 已超過 30 秒
- 確認 yml 中 `username/password` 是 `root/root`
- 試一次：`docker exec -it ticket-mysql mysql -uroot -proot -e "SELECT 1"`，能跑代表 MySQL 沒問題，問題就在後端設定

### Q3：要乾淨重來
```powershell
docker rm -f ticket-mysql
docker volume rm ticket-mysql-data
# 然後重跑「二、啟動 MySQL 容器」的指令
```

### Q4：Windows Defender / 防火牆問題
Docker Desktop 安裝時通常會自動處理。若仍連不上，到「Windows 安全性 → 防火牆 → 允許應用程式」確認 Docker 已被允許。

---

## 七、安全性提醒

- `root/root` **僅供本機開發**。正式環境一定要：
  1. 用強密碼
  2. 建立應用程式專用帳號，只給該資料庫的權限，不要直接用 root
  3. 不要把 3306 port 對外開放
- `docker-compose.yml` 若 commit 進 git，敏感資訊建議用 `.env` 抽出（v0.2 可改）

---

## 八、TL;DR — 一次性指令

```powershell
docker run -d --name ticket-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ticketdb -p 3306:3306 -v ticket-mysql-data:/var/lib/mysql --restart unless-stopped mysql:8.0
```
等 30 秒後：
```powershell
docker exec -it ticket-mysql mysql -uroot -proot -e "SHOW DATABASES;"
```
看到 `ticketdb` 就 OK，可以啟動 Spring Boot 了。
