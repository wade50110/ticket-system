# Ticket System Backend (v0.1)

Spring Boot 3.2.5 + Spring Security 6 + JPA + MySQL，提供註冊／登入／JWT 驗證的後端 API。

---

## 技術棧

| 層級 | 技術 |
|------|------|
| 語言 | Java 17 |
| 框架 | Spring Boot 3.2.5 |
| 安全 | Spring Security 6（Stateless + JWT） |
| 持久層 | Spring Data JPA + Hibernate |
| JWT | jjwt 0.12.5 |
| 資料庫 | MySQL 8 |
| 建置 | Maven |

---

## 專案結構

```
backend/
├── pom.xml
└── src/main/
    ├── java/com/example/ticket/
    │   ├── TicketApplication.java        # 進入點
    │   ├── HealthController.java         # /api/health
    │   ├── auth/
    │   │   ├── User.java                 # JPA Entity
    │   │   ├── UserRepository.java
    │   │   ├── UserService.java          # 註冊／驗證（BCrypt）
    │   │   ├── JwtService.java           # 簽發、解析 JWT
    │   │   ├── JwtAuthFilter.java        # Authorization Header 解析
    │   │   ├── AuthController.java       # /api/auth/{login,register,me}
    │   │   └── dto/                      # 請求／回應 DTO
    │   └── config/
    │       ├── SecurityConfig.java       # Spring Security + CORS
    │       └── GlobalExceptionHandler.java
    └── resources/
        └── application.yml
```

---

## API 規格

### 1. 註冊
```
POST /api/auth/register
Content-Type: application/json

{
  "username": "leo301",
  "email": "leo301@ton-wa.com",
  "password": "test1234",
  "name": "Leo"
}
```
回應 200：
```json
{ "id": 1, "username": "leo301", "email": "leo301@ton-wa.com", "name": "Leo" }
```

### 2. 登入
```
POST /api/auth/login
Content-Type: application/json

{ "username": "leo301", "password": "test1234" }
```
回應 200：
```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": { "id": 1, "username": "leo301", "email": "leo301@ton-wa.com", "name": "Leo" }
}
```

### 3. 取得目前登入者
```
GET /api/auth/me
Authorization: Bearer <accessToken>
```

### 4. 健康檢查
```
GET /api/health
```

---

## 啟動前置：用 Docker 建立 MySQL

> 環境：Windows 11 + Docker Desktop。
> 目標：啟一個乾淨的 MySQL 8 容器，帳密都用 `root`，資料庫 `ticketdb`，host port `3307`。

### 1. 安裝 Docker Desktop
- 下載：https://www.docker.com/products/docker-desktop/
- 安裝完開啟 Docker Desktop，等右下角小鯨魚圖示變綠（狀態：Running）

驗證安裝：
```powershell
docker --version
docker info
```

### 2. 啟動 MySQL 容器（兩種方式擇一）

#### 方法 A：用本 repo 提供的 docker-compose（推薦）

專案根目錄 `ticket-system/docker-compose.yml` 已準備好，直接：

```powershell
cd ticket-system
docker compose up -d
```

`docker-compose.yml` 內容：
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

volumes:
  ticket-mysql-data:
```

#### 方法 B：單行 docker run

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

> PowerShell 的續行符號是反引號 `` ` ``，不是反斜線 `\`。

### 3. 驗證 MySQL 已啟動

```powershell
docker ps                       # 看到 ticket-mysql 狀態 Up
docker logs ticket-mysql --tail 20   # 看到 "ready for connections"
docker exec -it ticket-mysql mysql -uroot -proot -e "SHOW DATABASES;"
```

第一次啟動需 10~30 秒初始化，太早連會被拒。

### 4. 常用維運指令

| 動作 | 指令 |
|------|------|
| 停止 | `docker compose stop` 或 `docker stop ticket-mysql` |
| 啟動 | `docker compose start` 或 `docker start ticket-mysql` |
| 看 log | `docker logs -f ticket-mysql` |
| 進 MySQL CLI | `docker exec -it ticket-mysql mysql -uroot -proot` |
| 砍掉重來（保留資料） | `docker rm -f ticket-mysql` |
| 連同資料一起刪 | `docker compose down -v` |

### 常見問題

**Q：port 3307 已被佔用？**
改 `docker-compose.yml` 的 `ports` 與 `application.yml` 的 `datasource.url` 中的 port。

**Q：本機已裝 MySQL Service 佔用 3306？**
本 repo 用 3307 故意避開預設 port，正常情況不會衝突。如真的衝到：
```powershell
Get-Service | Where-Object { $_.Name -like 'MySQL*' }
Stop-Service MySQL80      # 名稱依實際為準
```

---

## 啟動後端

### 1. 連線設定（已預設 `application.yml`）

| 設定 | 預設值 |
|------|--------|
| DB URL | `jdbc:mysql://localhost:3307/ticketdb` |
| DB User | `root` |
| DB Pass | `root` |
| Server Port | `8095` |
| JWT Secret | 開發用預設值（請以 `JWT_SECRET` 環境變數覆蓋） |
| JWT 有效期 | 3600 秒 |

### 2. 啟動

```powershell
cd backend
# 可選：覆蓋 JWT 密鑰（長度需 >= 32 bytes）
# $env:JWT_SECRET = "your-very-strong-secret-key-at-least-32-bytes"
mvn spring-boot:run
```

啟動成功後監聽 `http://localhost:8095`。

### 3. 驗證

```powershell
curl http://localhost:8095/api/health
```

或用 Postman 打 `POST /api/auth/register`、`POST /api/auth/login`。

---

## 安全性說明

- 密碼以 **BCrypt** 雜湊儲存（cost factor = 10，Spring 預設）
- JWT 使用 **HS256**，密鑰由 `jwt.secret` 注入
- Session 設為 `STATELESS`，受保護 API 需帶 `Authorization: Bearer <token>`
- CORS 僅允許 `http://localhost:5173`（前端開發伺服器）
- ⚠️ 開發階段 `root/root` 與預設 JWT secret 僅供本機，正式部署務必替換

---

## v0.2+ 規劃

- Refresh Token 機制
- 登入失敗計數鎖定（Redis）
- 票券、搶購、訂單模組
- Redis 分散式鎖、SQS、Docker 化後端
- Jenkins / AWS 部署
