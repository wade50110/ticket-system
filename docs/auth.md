# 認證與權限模組(auth)

> 現況規格,對應程式碼為準。最後核對:2026-08-31(v0.4 退票完成時點)。

## 現況規格

### 註冊 `POST /api/auth/register`(公開)
- 欄位驗證:`username` 3–100 字必填、`email` 必填且格式檢查、`password` 6–100 字必填、`name` 選填無驗證。
- 帳號與 Email 分別檢查重複,各回 400:「帳號已被使用」/「Email 已被使用」;DB 層另有 unique 約束兜底。
- 密碼以 BCrypt 雜湊存入 `password_hash`,任何 DTO 都不回傳密碼。
- **角色一律寫死 `CUSTOMER`**,沒有 API 可建立 ADMIN(只能直接改 DB)。

### 登入 `POST /api/auth/login`(公開)
- 帳號不存在與密碼錯誤回**同一句**「帳號或密碼錯誤」(刻意不洩漏帳號是否存在)。
- 成功回 JWT + 有效秒數 + 使用者資訊。

### JWT
- HS256,secret 來自 `${jwt.secret}`、有效期 `${jwt.expiration}` 秒(`application.yml`)。
- Payload:`sub` = userId 字串、claim `username`、claim `role`(缺失時 fallback `CUSTOMER`)。
- **沒有 refresh token 機制**,過期只能重新登入。

### 請求認證(JwtAuthFilter)
- 只認 `Authorization: Bearer <token>`;解析成功後 principal = **userId 字串**、authority = `ROLE_<role>`。
- token 過期/損毀/簽章錯:**吞例外並清空 SecurityContext**,不直接回錯,由授權層決定結果。
- Session 為 `STATELESS`。

### 路由權限對照(SecurityConfig,由上而下比對)

| 路徑 | 權限 |
|---|---|
| `POST /api/auth/login`、`/api/auth/register` | 公開 |
| `GET /api/health` | 公開 |
| `/api/admin/**` | ADMIN |
| `/api/cart/**`、`/api/checkout/**`、`/api/orders/**` | CUSTOMER |
| 其他(含 `/api/tickets/**`、`/api/auth/me`) | **已登入即可(ADMIN 也能看商城列表)** |

- CORS:只允許 `http://localhost:5173`(硬編碼),`allowCredentials(true)`。

### 全域錯誤處理慣例(GlobalExceptionHandler)

| 例外 | HTTP | 用途 |
|---|---|---|
| `IllegalArgumentException` | 400 | 參數/業務前置檢查失敗(含「不存在」類) |
| `MethodArgumentNotValidException` | 400 | Bean Validation,只取第一個欄位錯誤 |
| `CheckoutException` | 409 | 結帳衝突(見 checkout.md) |
| `RefundException` | 409 | 退票衝突(見 order.md) |

回應格式一律 `{"error": "訊息"}`。

## 檔案地圖

| 職責 | 位置 |
|---|---|
| 註冊/登入/me API | `backend/src/main/java/com/example/ticket/auth/AuthController.java` |
| 註冊/登入邏輯、BCrypt | `auth/UserService.java` |
| JWT 產生與解析 | `auth/JwtService.java` |
| 每請求認證過濾器 | `auth/JwtAuthFilter.java` |
| User entity / Role enum | `auth/User.java`、`auth/Role.java` |
| 路由權限 + CORS | `config/SecurityConfig.java` |
| 全域例外→HTTP 對照 | `config/GlobalExceptionHandler.java` |
| 前端登入/註冊/token 管理 | `frontend/src/pages/Login.jsx`、`Register.jsx`、`api/auth.js`、`api/http.js` |

前端 token 存 `localStorage`(key `ticket_token`);`api/http.js` 收到 401 會自動登出並導回 `/login`。

## 設計意圖(不要動的理由)

- **登入失敗訊息統一**是刻意的,防止帳號列舉攻擊;不要為了「更友善的提示」拆開。
- **JwtAuthFilter 吞例外**是刻意的:壞 token 等同未登入,交給授權層回應,filter 不該自己產生 HTTP 錯誤。
- 註冊寫死 CUSTOMER 是刻意的最小權限設計。

## 已知邊界情況

- role claim 缺失 → 一律當 CUSTOMER(JwtService 與 JwtAuthFilter 各兜底一次)。
- `User.@PrePersist` 兜底補 `createdAt` 與 `role`。

## 已知問題 / 技術債(記錄,尚未修正)

1. 未自訂 `AuthenticationEntryPoint`:**未帶 token 存取受保護 API 回 403 而非 401**(前端 http.js 只攔 401,403 不會自動登出)。
2. `GlobalExceptionHandler` 沒有 `Exception.class` 兜底,未預期例外回 Spring 預設 500;也沒處理 `DataIntegrityViolationException`(註冊 unique 競態時會露出 500)。
3. CORS origin 硬編碼 localhost,部署時要改為設定檔。

## 測試現況

- **本模組後端無任何測試**(整個 backend 只有 `RefundServiceTest`)。
- 前端也沒有 Login/Register 測試。補測試時優先:重複註冊、登入錯誤訊息一致性、過期 token 行為。
