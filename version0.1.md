# Ticket System v0.1

> 第一個可運作版本：使用者註冊、登入、登入後看到歡迎使用畫面。

發布日期：2026-05-18

---

## 一、本版本內容

| 項目 | 狀態 |
|------|------|
| 帳號註冊（BCrypt 密碼雜湊） | ✅ |
| 帳號登入（產生 JWT） | ✅ |
| 取得當前使用者資訊（驗證 JWT） | ✅ |
| 登入後歡迎使用畫面 | ✅ |
| 登出（清除前端 Token） | ✅ |
| MySQL 永久儲存 users 表 | ✅ |
| CORS 允許前端 localhost:5173 | ✅ |
| 票券、搶購、訂單功能 | ❌（v0.2+ 規劃） |
| Redis 分散式鎖 | ❌（v0.2+ 規劃） |
| Docker / Jenkins / AWS 部署 | ❌（後期版本） |

---

## 二、技術棧

| 層級 | 技術 |
|------|------|
| 後端 | Java 17、Spring Boot 3.2.5、Spring Security 6、Spring Data JPA |
| JWT | jjwt 0.12.5 |
| 資料庫 | MySQL 8 |
| 前端 | React 18、Vite 5、React Router 6 |
| 建置工具 | Maven、npm |

---

## 三、專案結構

```
ticket-system/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/ticket/
│       │   ├── TicketApplication.java        # 進入點
│       │   ├── HealthController.java         # /api/health
│       │   ├── auth/
│       │   │   ├── User.java                 # JPA Entity
│       │   │   ├── UserRepository.java
│       │   │   ├── UserService.java          # 註冊、驗證帳密（BCrypt）
│       │   │   ├── JwtService.java           # 簽發、解析 JWT
│       │   │   ├── JwtAuthFilter.java        # Authorization Header 解析
│       │   │   ├── AuthController.java       # /api/auth/{login,register,me}
│       │   │   └── dto/                      # LoginRequest / LoginResponse / RegisterRequest / UserInfo
│       │   └── config/
│       │       ├── SecurityConfig.java       # Spring Security + CORS
│       │       └── GlobalExceptionHandler.java
│       └── resources/
│           └── application.yml
└── frontend/
    ├── package.json
    ├── vite.config.js                        # 代理 /api → localhost:8080
    ├── index.html
    └── src/
        ├── main.jsx
        ├── App.jsx                           # 路由設定 + 守衛
        ├── styles.css
        ├── api/
        │   └── auth.js                       # login / register / logout / token 儲存
        └── pages/
            ├── Login.jsx
            ├── Register.jsx
            └── Welcome.jsx
```

---

## 四、API 規格

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

## 五、資料庫設定

啟動後端前，需先在 MySQL 建立資料庫（JPA `ddl-auto: update` 會自動建立 users 表）：

```sql
CREATE DATABASE ticketdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

連線資訊預設（可用環境變數覆蓋）：

| 環境變數 | 預設值 |
|---------|--------|
| `DB_HOST` | localhost |
| `DB_PORT` | 3306 |
| `DB_NAME` | ticketdb |
| `DB_USER` | root |
| `DB_PASS` | root |
| `JWT_SECRET` | （開發用預設值，正式請務必覆蓋，長度需 ≥ 32 bytes） |

---

## 六、啟動步驟

### 後端
```bash
cd ticket-system/backend
# Windows PowerShell 設環境變數（如需）
# $env:DB_PASS = "your-mysql-password"
./mvnw spring-boot:run
```
啟動成功後監聽 `http://localhost:8080`。

> 注意：本專案目錄尚未複製 `mvnw` script，可從 backend/ 同層或 Spring Initializr 取得，或改用本機已安裝的 `mvn spring-boot:run`。

### 前端
```bash
cd ticket-system/frontend
npm install
npm run dev
```
啟動成功後開啟 `http://localhost:5173`。

---

## 七、操作流程驗證

1. 開啟 http://localhost:5173 → 自動導向 `/login`
2. 點「註冊」→ 填寫 username / email / password（≥ 6 碼）/ name → 送出
3. 回到登入頁，輸入剛註冊的帳密 → 送出
4. 成功後跳轉 `/welcome`，畫面顯示「歡迎使用 Ticket System」與使用者資訊
5. 點「登出」→ 回到 `/login`，Token 從 localStorage 清除

---

## 八、安全性說明

- 密碼以 **BCrypt** 雜湊儲存（cost factor = 10，Spring 預設）
- JWT 使用 **HS256**，密鑰由 `jwt.secret` 環境變數注入
- Token 有效期 1 小時（`jwt.expiration: 3600`）
- 後端 Session 為 `STATELESS`，所有受保護 API 都需要 `Authorization: Bearer <token>`
- CORS 僅允許 `http://localhost:5173`（正式部署前需調整）
- ⚠️ 目前前端 Token 存於 `localStorage`，v0.2 可考慮改用 `httpOnly cookie` 或加上 Refresh Token

---

## 九、已知限制 / 下個版本（v0.2）規劃

- 尚無 Refresh Token 機制（過期需重新登入）
- 尚無登入失敗計數鎖定（規劃中：Redis 計數器）
- 尚無票券、搶購、訂單模組
- 尚未引入 Redis、SQS、Docker、Jenkins
- 前端尚未實作全域 fetch 攔截器（401 自動跳轉登入頁）

---

## 十、參考文件

- 完整系統規劃：[`../ticket-system-plan.md`](../ticket-system-plan.md)
