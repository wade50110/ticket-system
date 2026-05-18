# Ticket System Frontend (v0.1)

React 18 + Vite 5 + React Router 6，提供註冊、登入、登入後歡迎使用畫面。

---

## 技術棧

| 層級 | 技術 |
|------|------|
| 框架 | React 18 |
| 建置 | Vite 5 |
| 路由 | React Router 6 |
| HTTP | 原生 `fetch` |
| Token 儲存 | `localStorage` |

---

## 專案結構

```
frontend/
├── package.json
├── vite.config.js                  # 開發伺服器 + /api 代理到 backend
├── index.html
└── src/
    ├── main.jsx                    # 進入點
    ├── App.jsx                     # 路由 + 守衛
    ├── styles.css
    ├── api/
    │   └── auth.js                 # login / register / logout / token 儲存
    └── pages/
        ├── Login.jsx
        ├── Register.jsx
        └── Welcome.jsx
```

---

## 啟動

### 前置需求
- Node.js 18+（建議用 LTS）
- 後端服務已啟動於 `http://localhost:8095`（見 `../backend/README.md`）

### 安裝與啟動

```powershell
cd frontend
npm install
npm run dev
```

啟動成功後開啟 `http://localhost:5173`。

### Scripts

| 指令 | 用途 |
|------|------|
| `npm run dev` | 啟動開發伺服器（熱重載） |
| `npm run build` | 產出 production bundle 到 `dist/` |
| `npm run preview` | 預覽 `dist/` 的 production build |

---

## 後端代理設定

`vite.config.js` 已設定把 `/api` 轉發到後端，避免開發階段的 CORS 問題：

```js
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://localhost:8095',
      changeOrigin: true,
    },
  },
}
```

> 若後端 port 不是 8095，請同步調整 `target`。

---

## 路由與守衛

| 路徑 | 元件 | 守衛 |
|------|------|------|
| `/login` | `Login.jsx` | 未登入可進；已登入導向 `/welcome` |
| `/register` | `Register.jsx` | 公開 |
| `/welcome` | `Welcome.jsx` | 需登入（無 token 導向 `/login`） |
| `/` | — | 自動依登入狀態導向 `/welcome` 或 `/login` |

判斷登入狀態的依據：`localStorage.getItem('ticket_token')` 是否存在。

---

## 操作流程

1. 開啟 http://localhost:5173 → 未登入導向 `/login`
2. 點「註冊」→ 填寫 username / email / password（≥ 6 碼）/ name → 送出
3. 回到登入頁，輸入剛註冊的帳密 → 送出
4. 成功後跳轉 `/welcome`，畫面顯示「歡迎使用 Ticket System」與使用者資訊
5. 點「登出」→ Token 從 localStorage 清除，回到 `/login`

---

## v0.1 限制

- 尚無全域 fetch 攔截器：Token 過期後 API 回 401 時不會自動跳轉登入頁
- Token 儲存於 `localStorage`，未來考慮改 `httpOnly cookie` + Refresh Token
- 尚無錯誤訊息的全域處理（目前各頁面自行 try/catch）

---

## v0.2+ 規劃

- 票券列表 / 詳細頁
- 搶購流程
- 我的訂單頁
- 全域 fetch 攔截器（自動帶 token、處理 401）
- UI library（待評估：Mantine / shadcn-ui / Ant Design）
