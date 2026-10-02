# ticket-system frontend(React + Vite)

搶票系統前端:React 18.3 + Vite 5.4 + React Router 6.26,純 CSR。正式部署由 nginx 服務靜態檔並把 `/api/**` 反代到後端 Service;開發時 Vite dev server 把 `/api` proxy 到 `http://localhost:8099`。

> 技術棧、角色與路由權限、開發規則見 [`../CLAUDE.md`](../CLAUDE.md);API 規格見 [`../docs/`](../docs/)。

## 結構

```
frontend/
├── index.html / vite.config.js     # dev server 5173、/api proxy → 8099、vitest 設定(jsdom、globals)
├── Dockerfile / nginx.conf         # node build → nginx(SPA fallback + /api 反代到 ticket-backend:8099)
├── Jenkinsfile                     # CI/CD(Checkout → Preflight → Install → Test → Build Image → Deploy → Cleanup)
└── src/
    ├── App.jsx                     # 路由 + 依角色守衛(PrivateRoute)
    ├── components/                 # AppLayout、OrderStatusBadge
    ├── api/                        # http(共用 fetch、帶 token)、auth、tickets、cart、orders
    ├── pages/                      # Login、Register、Shop、Cart、Orders、OrderDetail、admin/AdminTickets
    └── test/setup.js               # Vitest 共用設定(jest-dom)
```

## 角色與路由

| 角色 | 登入後首頁 | 可用頁面 |
|------|-----------|----------|
| `ADMIN` | `/admin/tickets` | 票券上下架與管理 |
| `CUSTOMER` | `/shop` | 商城、購物車、結帳、訂單與明細、退票 |

Token 存在 `localStorage`(key `ticket_token`),由 `api/http.js` 統一帶上 `Authorization: Bearer`。

## 啟動(開發模式)

前置:Node 20(對齊 `Dockerfile` 的 `node:20-alpine`)、後端已在 `http://localhost:8099`(見 [`../backend/README.md`](../backend/README.md))。

```powershell
cd frontend
npm install
npm run dev          # http://localhost:5173
```

完整系統(nginx + k8s)請用 `..\scripts\start-stack.ps1` → http://localhost,見 [`../README.md`](../README.md)。

| 指令 | 用途 |
|------|------|
| `npm run dev` | 開發伺服器(熱重載) |
| `npm run build` | 產出 `dist/`(Dockerfile 的 build 階段也是跑這個) |
| `npm run preview` | 預覽 production build |
| `npm test` | Vitest 跑一次(CI 用) |
| `npm run test:watch` | 監看模式 |

## 測試

- Vitest + React Testing Library + jest-dom;測試檔與被測檔同層,命名 `*.test.js(x)`;目前 **17** 顆(`api/orders`、`Shop`、`Orders`、`OrderDetail`、`admin/AdminTickets`)。
- 一律 `vi.mock('../api/xxx.js')` 模擬 API,不打真實後端;重點測使用者看得到的行為(按鈕出現條件、確認對話框、成功後畫面更新、錯誤訊息)。
- Jenkins 的 `ticket-frontend` job 每次 push 都跑 `npm ci` + `vitest run`(JUnit 報告),不過就不 build、不上板。
- 規則:完成任何修改都要補測試並實際跑過;測試失敗不得改弱(見 `../CLAUDE.md`)。

## 容器化與部署

- `Dockerfile`:`node:20-alpine` 跑 `npm install` + `npm run build` → `nginx:alpine` 放 `dist/` 與 `nginx.conf`。
- `nginx.conf`:`/api/` 反代 `http://ticket-backend:8099`(k8s Service 名稱),其餘路徑 SPA fallback 到 `index.html`,`/assets/` 長快取。
- image:Jenkins 以 `ticket-frontend:<git sha7>` 建、rollout 成功後同步打 `:local`;k8s `../k8s/frontend.yaml` 固定 2 副本、LoadBalancer 綁 `localhost:80`、不掛 HPA(CSR 靜態負載低)。

## 已知限制 / 技術債

- Token 放 `localStorage`;UI 改版、票券圖片、搶票排隊(v0.4 🅑🅒🅓)尚未開始,見 `../CLAUDE.md` 進度。
- Node 20 已 EOL,升級到 22 時 `Dockerfile`、Jenkins image(`ci/jenkins/Dockerfile`)要一起改;`Dockerfile` 的 `npm install` 宜改 `npm ci`。
