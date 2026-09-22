# 部署架構改造:容器化 + Nginx + k8s(HPA autoscale)

> 狀態:設計(設計 → 實作中 → 已完成)
> 建立日期:2026-09-22
> 這份是總覽。兩個關鍵機制另有專文:[排程防重(ShedLock)](scheduled-jobs-shedlock.md)、[HPA 與 metrics-server](hpa-and-metrics-server.md)。

## 目標

把目前「後端與前端都在 host 本機跑、只有 DB/Redis 在容器」的開發架構,改成:

1. **前後端都容器化**(各自 Dockerfile)。
2. **前面放 Nginx**:serve 前端靜態檔 + 反向代理 `/api` 到後端。
3. **後端跑在 k8s**,用 HPA 依流量自動增減 pod(搶票尖峰放大、離峰縮回)。

目標環境:**本機 Docker Desktop 內建的 k8s**,實際部署起來並能壓測 demo HPA 開關 pod。雲(EKS)是後續。

## 目標架構

```
                  [ 使用者瀏覽器 ]
                         │
                         ▼
        ┌────────────────────────────────┐
        │  Nginx (k8s Service, 對外)      │
        │  - serve 前端 dist 靜態檔        │
        │  - /api/** 反代 → backend svc    │
        └────────────────────────────────┘
                         │  /api/**
                         ▼
        ┌────────────────────────────────┐
        │  backend Service (ClusterIP)    │  ← 負載均衡到多個 pod
        └────────────────────────────────┘
              │          │          │
              ▼          ▼          ▼
          [pod 1]    [pod 2]    [pod N]   ← Deployment,受 HPA 控制數量
          Spring Boot 8099        (依 CPU 自動 2..10,min..max)
              │          │          │
              └──────────┼──────────┘
                         ▼
        ┌────────────────────────────────┐
        │  k8s 外(不進叢集)              │
        │  MySQL 3307   Redis 6380        │  ← 現有 docker-compose
        └────────────────────────────────┘
             (Redis = 庫存正源 + 分散式鎖)
```

## 四個關鍵決策(2026-09-22 使用者確認)

| # | 決策 | 選擇 | 理由 |
|---|------|------|------|
| 1 | 目標環境 | 本機 Docker Desktop k8s + 實測 HPA | 能真的部署起來、壓測 demo autoscale;雲後續 |
| 2 | MySQL/Redis 位置 | **留在 k8s 外** | 有狀態服務不進叢集最簡單安全;Redis 是庫存正源不冒 PV 風險;pod 用環境變數連 host |
| 3 | 排程多 pod 重複 | **ShedLock**(Redis 鎖) | 後端多 pod 後 `@Scheduled` 每個 pod 都會跑,需分散式鎖只讓一個 pod 執行。詳見專文 |
| 4 | HPA 指標 | **CPU-based + metrics-server** | 標準做法,本機裝 metrics-server 才有 CPU 指標可實測。詳見專文 |

## 前端為什麼不需要 autoscale(CSR 定位)

前端是 **React + Vite SPA = 純 CSR(client-side rendering)**:`vite build` 產出的是靜態檔(HTML/JS/CSS),nginx 只是「原封不動回傳檔案」,幾乎不吃 CPU;使用者下載後所有互動都在瀏覽器內跑 JS + 打 `/api`。所以搶票尖峰的運算壓力**全在後端**,前端這層負載幾乎不變。

因此把「要不要多副本」拆成兩個問題:

| 需求 | 由什麼決定 | 前端(CSR)的答案 |
|------|-----------|------------------|
| 為**分攤運算負載**而 autoscale | CSR / SSR(SSR 每請求要 server 渲染才會吃 CPU) | ❌ 不需要——CSR 發靜態檔負載極低 |
| 為**高可用/不中斷**而多副本 + LB | 可用性考量,與 CSR/SSR 無關 | ✅ 固定 2 副本即可(一個掛了/滾動更新不中斷),**不掛 HPA** |

對比後端:後端是「跑程式」,尖峰 CPU 會飆,所以要 HPA;前端是「發靜態檔」,固定副本做高可用就夠。（雲上更理想是把 CSR 靜態資源丟 CDN / 物件儲存,連自己的 nginx 都不用;本機/k8s 用 nginx 多副本是等價的簡化版。）

## 各元件怎麼做

### 後端 image(多階段 build)
- 用 `maven:3.9-eclipse-temurin-17` 在容器內 build jar → `eclipse-temurin:17-jre` 執行。
- **好處:build 在容器內,不依賴本機工具鏈**(這台機器沒有 mvnw、mvn 不在 PATH,見 build 記憶),CI/任何機器都能一致建置。
- `application.yml` 的連線改為全參數化(環境變數),見下方「必要的程式調整」。

### 前端 image(多階段 build)
- 用 `node:20` 跑 `npm run build` 產出 `dist/` → `nginx:alpine` serve。
- 這個 nginx image 同時負責 **serve 靜態檔** 與 **反代 `/api` 到 backend Service**——就是架構圖「前面的 Nginx」。
- 取代目前開發用的 Vite dev server proxy(`vite.config.js` 的 `/api` → 8099)。

### k8s manifests(後端)
- `Deployment`(backend,image + env + readiness/liveness probe 打 `/api/health`)。
- `Service`(ClusterIP,backend,port 8099)——nginx 反代的目標,自動負載均衡到多 pod。
- `HPA`(min/max replicas,CPU 目標門檻)——詳見 HPA 專文。
- `ConfigMap` / `Secret`:非敏感設定(Redis/MySQL host)放 ConfigMap,`JWT_SECRET`、DB 密碼放 Secret。
- nginx 也可用 `Deployment` + `Service`(type LoadBalancer / NodePort 對外)。

### 資料層(k8s 外)
- 維持現有 `docker-compose.yml`(MySQL 3307、Redis 6380)。
- pod 從叢集內連 host 的服務:優先試 `host.docker.internal` 當 host,**但 pod 走的是 CoreDNS 不是 Docker 內建 DNS,能否解析隨 Docker Desktop 版本而異**(歷史上不穩),可能要加 `hostAliases` 指到 host-gateway 或改 CoreDNS rewrite;fallback 用 headless Service + Endpoints 指向 host IP。實作時實測確認。

## 必要的程式/設定調整(實作階段會做)

1. **`application.yml` datasource 參數化**:目前寫死 `jdbc:mysql://localhost:3307/...`,改成 `${DB_HOST}`/`${DB_PORT}` 等環境變數(Redis 已是環境變數,JWT secret 已是環境變數)。
2. **加 ShedLock**:讓 `@Scheduled` 排程在多 pod 下只有一個執行(見專文,含程式改法)。**注意:加鎖前要先修 `retryBatch` 的自呼叫交易失效既有 bug**,否則等於「上了鎖但排程本來就沒在運作」——見 ShedLock 專文。
3. **graceful shutdown**:`server.shutdown=graceful` + k8s `terminationGracePeriodSeconds`,避免縮容砍 pod 時 `@Async` 佇列事件遺失(見上表)。
4. **正式環境 `ddl-auto` 改 `validate`**:避免多 pod 首次部署並行建表競爭(本機 demo 可暫留 update)。
5. **Dockerfile ×2、.dockerignore、k8s manifests、nginx.conf** 新增。
6. **健康檢查**:`/api/health` 已存在,直接當 readiness/liveness probe。

## 對防超賣的影響(多 pod 安全性分析)

後端從單實例變多 pod,逐一檢視現有機制:

| 機制 | 多 pod 下 | 結論 |
|------|-----------|------|
| Redis Lua 原子扣減 | Redis 單執行緒 + 單腳本,跨 pod 天然序列化 | ✅ 安全 |
| Redisson 分散式鎖 | 本來就是跨實例的鎖 | ✅ 安全(設計初衷) |
| StockBootstrap / QuotaBootstrap(`@PostConstruct`) | 每個 pod 啟動都跑,但 `setIfAbsent` 冪等 | ✅ 安全 |
| `@Async` 庫存回寫事件 | 各 pod 處理自己發的事件;但**縮容砍 pod 時,executor 記憶體佇列裡未處理的事件會遺失**(且不會進 `stock_sync_failed`——那表只收「處理過、重試 3 次仍失敗」的) | ⚠️ 防超賣安全(Redis 正源),但需搭 **graceful shutdown**(`server.shutdown=graceful` + k8s `terminationGracePeriodSeconds` + 關機前等 executor 排空),否則縮容時 DB 庫存會相對 Redis 靜默漂移 |
| **`@Scheduled` 補償排程** | **每個 pod 都跑 → 重複掃描重試** | ⚠️ **需 ShedLock**(決策 3);且該排程本身有既有交易 bug 要先修,見 ShedLock 專文 |
| Hibernate `ddl-auto: update` | 多 pod **同時首次部署**時並行跑 DDL 更新,可能競爭/鎖表出錯 | ⚠️ 正式環境改 `validate` + 由 migration 工具建表;本機 demo 影響小(可先讓一個 pod 起來建好表再擴) |
| JWT 驗證 | stateless,無 session | ✅ 安全 |

**結論:防超賣核心早已為多實例設計,唯一要補的是排程防重(ShedLock)。** 這也是為什麼這個專案適合 autoscale——不是硬把單體塞進 k8s。

## 實作清單

已完成(2026-09-22,後端 45 測試通過、context 與容器實跑驗證):

- [x] 修 `retryBatch` 自呼叫交易失效(抽 `StockSyncRetryWorker` 獨立 bean)+ 改逐筆 REQUIRES_NEW(一筆 DB 例外不污染整批)
- [x] `application.yml` 連線參數化(`${DB_HOST}` 等)
- [x] 加 ShedLock 依賴 + `@SchedulerLock` + `SchedulerConfig`(見專文)
- [x] graceful shutdown:`server.shutdown=graceful` + `spring.task.execution.shutdown.await-termination`(真的等 @Async 佇列排空)+ Dockerfile `exec`(JVM 收得到 SIGTERM)+ k8s preStop
- [x] admin 手動重試繞過 `@SchedulerLock`(不被排程鎖靜默跳過)
- [x] backend Dockerfile(多階段)+ .dockerignore
- [x] frontend Dockerfile(多階段 → nginx)+ nginx.conf(serve dist + 反代 /api)
- [x] k8s:backend.yaml(ConfigMap/Secret/Deployment/Service/HPA、startupProbe/preStop)、frontend.yaml(Deployment/Service)
- [x] build 兩個 image、backend 容器連 host DB/Redis 實跑健康

已部署並實測(2026-09-22,Docker Desktop kubeadm k8s):

- [x] 裝 metrics-server(+ `--kubelet-insecure-tls`)、`kubectl apply` backend/frontend
- [x] 四個 pod Running,`http://localhost/` 200、`/api/health` UP(nginx serve 前端 + 反代 → backend Service → host DB/Redis 全鏈路通;**`host.docker.internal` 在 kubeadm pod 內可解析,先前擔心的解析問題沒發生**)
- [x] **HPA 擴容實測**:壓測登入(BCrypt 吃 CPU)→ CPU 400% → pod 2→4→8→10(觸頂 maxReplicas)
- [x] **HPA 縮容實測**:壓測結束 CPU 降 1-2% → 冷卻窗(~5min)後一次縮回 minReplicas=2

待做:

- [ ] 更新 docker-setup.md / CLAUDE.md 啟動步驟(補 k8s 部署路徑,指向 k8s/README.md)

## Code review 待補(記錄,未做)

- **`retryBatch` 的 DB 整合測試**:逐筆 REQUIRES_NEW「一筆 DB 例外只回滾那筆、不污染整批」屬交易隔離,Mockito 驗不到,需 `@DataJpaTest`/`@SpringBootTest`(專案暫無 H2/testcontainers 基礎設施,與限購的 `aggregateHeldQuantities` JPQL 同批待補)。
- **失敗記錄 dead-letter**:`decrementStock` 永遠回 0 的記錄(票券已刪等)會每 60 秒被重掃、`retryCount` 不再當停止條件(既有技術債,checkout.md 問題 6),建議設 retryCount 上限後標終態或告警。
- **hardening**:backend Dockerfile 以 root 執行(建議非 root user)、nginx `proxy_pass` upstream 名稱在啟動時解析(frontend 先於 backend Service 存在會 crash-loop,可用變數 + resolver 緩解)。
