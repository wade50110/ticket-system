# 需求書:Jenkins CI/CD(前後端分流即時上板)

> 狀態:已完成(草稿 → 已定稿 2026-10-01 → 開發中 → 已完成 2026-10-01;現況以 [`../../deployment/ci-cd-jenkins.md`](../../deployment/ci-cd-jenkins.md) 為準)
> 版本:v0.7
> 建立日期:2026-10-01(2026-10-01 依技術審查修訂第 2 稿)
> Todo:[jenkins-cicd-todo.md](jenkins-cicd-todo.md)

## 1. 目標與背景

**現況痛點**(見 `k8s/README.md`「改程式碼後重新部署」警告):

- 改 code 後要人工 `docker build` → `kubectl rollout restart`,忘了 restart 就是舊版在跑。
- 沒有自動測試閘門:沒跑測試也能上板。
- image tag 固定 `:local`,線上跑的是哪個 commit 無從追溯、也無法 `rollout undo` 回上一版(兩版同名)。

**目標**:push 到 GitHub `main` 後,約 1~1.5 分鐘內自動開始「跑測試 → 以 git sha 建 image → 滾動更新 k8s Deployment」;**測試不過不上板、上板失敗自動回滾**;前後端各一條獨立 pipeline,只有改到的那端重新上板。

**定位**:這是 [`ticket-system-plan.md`](../../../../ticket-system-plan.md) 規劃的 Jenkins CI/CD 在本機 Docker Desktop k8s 的落地版。設計上保留日後換 AWS 的接點:image 名稱可加 registry 前綴、部署身分是 kubeconfig(換成 EKS 的即可)、觸發方式從輪詢換 webhook。

## 2. 功能需求

| # | 需求 |
|---|------|
| F-1 | **Jenkins 跑在 docker compose(k8s 外)**,與 MySQL/Redis 同一份 `docker-compose.yml`,放 `ci` profile:`docker compose up -d` 行為不變(只起 MySQL/Redis);`docker compose --profile ci up -d --build` 才會多起 Jenkins。 |
| F-2 | **自製 Jenkins image**(`ci/jenkins/Dockerfile`):基於官方 `jenkins/jenkins:2.580.1-lts-jdk21`(定稿時最新 LTS;官方 jdk17 映像線到 2.541.3 止、已無安全更新,故 Jenkins 本體用 JDK 21),另從 `eclipse-temurin:17-jdk` 複製 JDK 17 到 `/opt/java/temurin-17` **專供 Maven 建置/測試**(Jenkinsfile 設 `JAVA_HOME`,與 app 的 Java 17 一致);內建 Maven 3.9、Node 20(對齊 `frontend/Dockerfile` 的 `node:20-alpine`;Node 20 已 EOL,升級與 Dockerfile 一起列技術債)、docker CLI + buildx plugin、kubectl v1.34(與叢集同 minor)。plugins 以 `plugins.txt` **鎖版**(`name:version`)安裝:`configuration-as-code`、`job-dsl`、`workflow-aggregator`、`git`、`junit`、`pipeline-stage-view`、`timestamper`。掛主機 Docker socket,建出的 image 直接進 Docker Desktop k8s 共用的 image store,不需 registry。 |
| F-3 | **設定全部程式碼化**(JCasC `casc.yaml` + Job DSL `jobs.groovy`):容器啟動即有 admin 帳號、2 個 pipeline job、2 個 executor、關閉 setup wizard、關閉 agent 埠(`slaveAgentPort: -1`)、Jenkins URL 由 `JENKINS_HTTP_PORT` 組出(預設 `http://localhost:8088/`);不需任何 UI 手動設定,砍掉 volume 重起可完整重建(image 重 build 亦可重現,因 base image 與 plugins 皆鎖版)。 |
| F-4 | **兩個 job**:`ticket-backend`、`ticket-frontend`,Pipeline script from SCM(GitHub `https://github.com/wade50110/ticket-system.git`,公開 repo 不需憑證),**SCM 分支固定 `*/main`**(不含任何參數),分別讀 `backend/Jenkinsfile`、`frontend/Jenkinsfile`。Job 層級以 Job DSL 宣告:每分鐘輪詢、路徑過濾、`disableConcurrentBuilds`、`quietPeriod(0)`、`buildDiscarder`(保留 30 筆)、`BRANCH` 參數。 |
| F-5 | **觸發**:每分鐘輪詢 `main`(`* * * * *`,workspace polling,每 job 每分鐘一次 `git fetch`);Git plugin 路徑過濾(regex、整段比對)——backend job `includedRegions = backend/.*`,frontend job `frontend/.*`(各自含 Jenkinsfile 本身);其他路徑(`k8s/.*`、`docs/.*`、`ci/.*`、根目錄檔)不觸發任何 job。另可手動「Build with Parameters」,字串參數 `BRANCH`(預設 `main`,白名單 `^[A-Za-z0-9._/-]+$`)。**`BRANCH` 不進 SCM 設定**(輪詢展開參數會用上一次 build 的值,會讓 main 的自動觸發失效):Jenkinsfile 先 `checkout scm`(main,取得 Jenkinsfile 並維持輪詢基準),若 `BRANCH != main` 再以 `git fetch origin <BRANCH>` + `git checkout FETCH_HEAD` 切換**內容**;Jenkinsfile 永遠來自 main。 |
| F-6 | **後端 pipeline 階段**(`backend/Jenkinsfile`,declarative,`agent any`):`Checkout`(含 BRANCH 切換、算 `sha7`)→ `Preflight`(BRANCH 白名單、`docker version` 可用、kubectl 連得到叢集且 Deployment `ticket-backend` 存在)→ `Test`:起**拋棄式 Redis**(`docker run -d --rm --name ci-redis-backend --network container:<Jenkins 容器名> redis:7-alpine redis-server --port 6380 --save "" --appendonly no`;先 `docker rm -f` 同名殘留;以 `docker exec … redis-cli -p 6380 ping` 等到 PONG)→ `JAVA_HOME=/opt/java/temurin-17 mvn -B clean test`(`clean` 見決策 #26;**非零 exit 即 FAILURE**,不得加 `-Dmaven.test.failure.ignore`;`junit` 步驟只負責發佈報告,不得把結果降為 UNSTABLE 續跑)→ `post always` 停 Redis、`junit backend/target/surefire-reports/*.xml` → **Redis 閘門檢查**:`TEST-com.example.ticket.stock.QuotaRedisRepositoryRedisTest.xml` 必須存在且 `tests` 屬性 > 0(定稿時為 13;該類在 `@BeforeAll` Assumption 失敗時 surefire 會寫成 `tests="0" skipped="0"`,**不能**用 skipped 判斷)且 failures=errors=skipped=0(類內任一顆被 skip/`@Disabled` 也算失敗),否則 FAILURE → `Build Image`:`docker build -t ticket-backend:<sha7> ./backend` → `Deploy`:`kubectl set image deployment/ticket-backend ticket-backend=ticket-backend:<sha7>` + `kubectl annotate … kubernetes.io/change-cause="jenkins ticket-backend #<build> <sha7>"` → `kubectl rollout status --timeout=300s` → 失敗:`kubectl rollout undo` + 等回滾完成 + 印診斷(`get pods`、失敗 pod 的 `describe`/`logs`/events)+ FAILURE;**成功後才** `docker tag ticket-backend:<sha7> ticket-backend:local` → `Cleanup`(僅 build 成功時執行):呼叫 `ci/jenkins/scripts/prune-images.sh`(見 F-14)。整條 pipeline `timeout 30 分鐘`。 |
| F-7 | **前端 pipeline 階段**(`frontend/Jenkinsfile`):`Checkout`(同上)→ `Preflight`(BRANCH 白名單、docker、kubectl、Deployment `ticket-frontend` 存在)→ `Install`:`npm ci` → `Test`:`npx vitest run --reporter=default --reporter=junit --outputFile=../ci-reports/frontend-junit.xml`(報告寫到 docker build context 之外;非零 exit 即 FAILURE)→ `post always` `junit ci-reports/*.xml` → `Build Image`:`docker build -t ticket-frontend:<sha7> ./frontend` → `Deploy`:`kubectl set image deployment/ticket-frontend nginx=ticket-frontend:<sha7>` + change-cause → `rollout status --timeout=180s` → 失敗 undo + 診斷 + FAILURE;成功後才打 `:local` → `Cleanup`。 |
| F-8 | **部署身分最小權限**:`k8s/ci/jenkins-rbac.yaml` 建 ServiceAccount `jenkins-deployer`(default namespace)+ Role(`apps/deployments` get/list/watch/patch/update;`apps/replicasets` get/list/watch;`pods` get/list/watch;`pods/log` get;`events` 與 `events.k8s.io/events` get/list——`describe pod` 沒有 events 權限時會**靜默**少掉 Events 區,回滾診斷就看不到 readiness/CrashLoop 原因)+ RoleBinding + 長效 token Secret(`type: kubernetes.io/service-account-token`,以 annotation 綁 SA、**不**加進 SA 的 `.secrets` 清單以免被 legacy token 清理機制回收)。`scripts/ci-bootstrap.ps1`:apply → 等 Secret 的 `.data.token` 由 controller 填入 → 以 `ca.crt`(直接作 `certificate-authority-data`)與解碼後的 token 寫出 `ci/jenkins/secrets/kubeconfig`(**UTF-8 無 BOM、LF**;**gitignore**)。compose 以唯讀掛到 `/etc/jenkins/kubeconfig` 並設環境變數 `KUBECONFIG=/etc/jenkins/kubeconfig`(不放進 named volume 內的 `~/.kube`,避免 root 建目錄與 bind 單檔更新不同步問題;重新產生 kubeconfig 後需重啟 Jenkins 容器)。 |
| F-9 | **測試用 Redis 由 pipeline 自管**(不放 compose):`Test` 階段起拋棄式 `redis:7-alpine` 兄弟容器並以 `--network container:` 加入 Jenkins 的網路命名空間,所以測試寫死的 `localhost:6380` 在 Jenkins 容器內連到它;與開發用 `ticket-redis`(主機 6380)完全隔離、每次 build 全新、不會因 Jenkins 容器重啟而失效(compose sidecar 的 `network_mode: service:` 在 Jenkins 重啟後會留在失效的命名空間,故不採用)。 |
| F-10 | **存取、帳號與資源**:Jenkins 只綁 `127.0.0.1:${JENKINS_HTTP_PORT:-8088}`(主機 8080 已被其他服務佔用;Jenkins 握有 docker socket 等同主機 root,不可對 LAN 開放);不發佈 agent 埠。帳號 `admin`,密碼由環境變數 `JENKINS_ADMIN_PASSWORD` 指定(未設時 dev 預設 `admin`,與 Grafana 同等級的開發預設)。Jenkins 程序以 `jenkins` 使用者(uid 1000)執行、用 `group_add: ["0"]` 取得 socket(root:root 660)權限,不以 root 跑。`TZ=Asia/Taipei` + `-Duser.timezone=Asia/Taipei`(與 MySQL 容器一致);Jenkins JVM `-Xmx1g`,compose `mem_limit: 4g`(Jenkins + Maven surefire + node 同容器;docker build 在 daemon 端不算在內)。 |
| F-11 | **可追溯**:Jenkins UI 有 stage 檢視與測試報告;Deployment 註記 `kubernetes.io/change-cause`,`kubectl rollout history` 看得到每次上板;image 以 sha 命名可對回 commit。 |
| F-12 | **快取與效能**:Maven `~/.m2`、npm `~/.npm` 落在 `jenkins_home` volume 持久化;docker build 沿用 daemon 的 layer cache。非首次 build 的目標:後端 push → rollout 完成 ≤ 10 分鐘、前端 ≤ 5 分鐘。 |
| F-13 | **文件**:`ci/jenkins/README.md` runbook(bootstrap、啟動、驗證、疑難排解、關閉);`docs/deployment/ci-cd-jenkins.md` 現況 spec;`CLAUDE.md` 與 `k8s/README.md` 的「改程式碼後重新部署」改寫為:**正規路徑 = push main 由 Jenkins 上板**;**手動備援** = `docker build -t ticket-backend:local ./backend` → `kubectl set image deployment/ticket-backend ticket-backend=ticket-backend:local`(Deployment 已被 pipeline 改成 sha tag,單純 `rollout restart` 不會換到新 build;若 Deployment 當下已是 `:local` 才需 `rollout restart`);並說明 `kubectl apply -f k8s/backend.yaml` 會把 image 改回 `:local`(= 最近一次成功上板的內容)而觸發一次同內容 rollout 的副作用。 |
| F-14 | **舊 image 清理腳本** `ci/jenkins/scripts/prune-images.sh <repo> <keep> [protected-tag…]`:只處理該 repo 名下 tag 符合 `^[0-9a-f]{7,12}$` 的 image(`git rev-parse --short=7` 在前綴碰撞時會輸出更長);以 **image ID** 分組、依 image `Created` 時間排序,保留最新 `keep`(=5)個 image ID 的所有 tag;另保護 `local` 與呼叫端傳入的 tag(pipeline 傳入該 Deployment 所有既存 ReplicaSet 引用的 image,`kubectl get rs -l app=<app>`,確保 `rollout undo --to-revision` 任一版都有 image);其餘以 `docker rmi <repo>:<tag>`(不加 `-f`,只解 tag)移除;單筆失敗只印警告、exit 0。腳本可在 Jenkins 外獨立執行以便測試。 |

## 3. API 規格

無新增應用 API,後端/前端程式碼不動。本功能的「介面」是兩個 Jenkins job:

| Job | 來源 | 自動觸發 | 參數 | 產出 |
|-----|------|----------|------|------|
| `ticket-backend` | GitHub `main`,`backend/Jenkinsfile` | 每分鐘輪詢,僅 `backend/.*` 變動;quiet period 0 | `BRANCH`(預設 `main`;非 main 時只切換內容,不影響輪詢) | image `ticket-backend:<sha7>`(成功後同步 `:local`);Deployment `ticket-backend` 換版並 rollout 完成 |
| `ticket-frontend` | GitHub `main`,`frontend/Jenkinsfile` | 每分鐘輪詢,僅 `frontend/.*` 變動;quiet period 0 | `BRANCH`(同上) | image `ticket-frontend:<sha7>`(成功後同步 `:local`);Deployment `ticket-frontend` 換版並 rollout 完成 |

- Jenkins UI:`http://127.0.0.1:8088`(admin / `JENKINS_ADMIN_PASSWORD`)。
- Stage 名稱(固定,文件與驗收用):後端 `Checkout → Preflight → Test → Build Image → Deploy → Cleanup`;前端 `Checkout → Preflight → Install → Test → Build Image → Deploy → Cleanup`。
- 失敗語意:任何 stage 失敗 = build **FAILURE**(不是 UNSTABLE),後續 stage 不執行;`Deploy` 失敗 = 已自動回滾到前一版(build 仍 FAILURE)。
- 時間語意:「push 完成」到「build 開始」最壞 ≈ 輪詢間隔 60 s + fetch/比對數秒(quiet period 0),目標 ≤ 90 s。

### 檔案地圖(本功能新增/修改)

```
ticket-system/
├── docker-compose.yml                 # + profile ci:jenkins(group_add 0、socket、kubeconfig 唯讀、volume jenkins_home、127.0.0.1 綁埠)
├── .gitignore                         # + ci/jenkins/secrets/
├── ci/jenkins/
│   ├── Dockerfile                     # jenkins 2.580.1-lts-jdk21 + temurin-17 + maven/node/docker-cli/kubectl + plugins
│   ├── plugins.txt                    # 鎖版
│   ├── casc.yaml                      # JCasC:admin、authorization、executors、slaveAgentPort -1、location、jobs(Job DSL)
│   ├── jobs.groovy                    # Job DSL:ticket-backend / ticket-frontend(SCM 固定 main、輪詢、路徑過濾、參數)
│   ├── scripts/prune-images.sh        # 舊 image 清理(F-14)
│   ├── scripts/check-redis-suite.sh   # Redis 閘門檢查(F-6):讀 surefire 報告,tests>0 且無 failures/errors
│   ├── secrets/kubeconfig             # bootstrap 產出,不進版控
│   └── README.md                      # runbook
├── backend/Jenkinsfile
├── frontend/Jenkinsfile
├── k8s/ci/jenkins-rbac.yaml           # SA + Role(含 events)+ RoleBinding + token Secret
├── scripts/ci-bootstrap.ps1           # apply RBAC → 等 token → 產 kubeconfig(UTF-8/LF)→ compose --profile ci up --build
└── docs/deployment/ci-cd-jenkins.md   # 現況 spec(開發完成後)
```

## 4. 資料模型變更

無。不動 MySQL/Redis 資料,不動應用設定。

## 5. 邊界情況與錯誤處理

| 情況 | 系統反應 |
|------|----------|
| 測試失敗(後端 surefire 或前端 vitest) | `Test` 的 `sh` 非零 → build FAILURE,不 build image、不部署;線上維持前一版;Jenkins 測試報告頁指出哪顆失敗。 |
| 拋棄式 Redis 起不來(docker 不可用、同名殘留容器、6380 在 Jenkins 命名空間內被佔) | 先 `docker rm -f ci-redis-backend` 清殘留再起;仍起不來或 10 秒內無 PONG → `Test` FAILURE,訊息說明「CI Redis 啟動失敗,請檢查 docker socket 與殘留容器」。 |
| Redis 整合測試被整類跳過(任何原因) | `check-redis-suite.sh` 讀 `TEST-…QuotaRedisRepositoryRedisTest.xml`:檔案不存在或 `tests="0"` 或 failures/errors ≠ 0 → FAILURE。**不允許**靜默跳過(等同改弱測試)。 |
| `docker build` 失敗 | build FAILURE 於 `Build Image`,不部署。 |
| k8s 上沒有目標 Deployment(例如服務全關) | `Preflight` 失敗,訊息提示「請先 `kubectl apply -f k8s/backend.yaml`(或 frontend.yaml)」;pipeline 不負責初次安裝(RBAC 無 create 權限,見決策 #7)。 |
| Docker Desktop k8s 沒開 / kubeconfig token 失效或檔案重產後未重啟 | `Preflight` 失敗(kubectl 連線錯誤或 401/403),runbook 說明重開 k8s、重跑 bootstrap 並 `docker compose --profile ci restart jenkins`。測試與 image 都還沒做,快速失敗。 |
| rollout 在 timeout 內未完成(新版 CrashLoop、readiness 不過) | 自動 `kubectl rollout undo`,等待回滾的 rollout 完成,印出 `kubectl get pods`、失敗 pod 的 `describe`(含 Events)與 `logs --tail`,build FAILURE。undo 本身也失敗 → 同樣印診斷並 FAILURE,不做更多自動處置(人工介入)。`:local` 尚未被改動(成功後才打),故回滾目標若是 `:local` revision(首次接手或手動 apply 後)也拿得到舊版內容。 |
| build 在 `Deploy` 中途被中止 / Jenkins 重啟 | k8s 會自行把已下的 `set image` rollout 跑完(或卡在新 RS 不 Ready);pipeline 不會補 undo。runbook 說明:手動 `kubectl rollout status` 查看,必要時 `kubectl rollout undo`;下一次成功 build 會覆蓋。 |
| 同一個 sha 重跑(手動 Build) | image 重 build(layer cache 快)、`set image` 無變化不觸發 rollout、`rollout status` 立即成功 → build SUCCESS 但沒有新 pod;`annotate` 只覆蓋當前 RS 的 change-cause(無害)。要強制重啟用 `kubectl rollout restart`(手動,非 pipeline 範圍)。 |
| 連續多次 push | 同一 job `disableConcurrentBuilds`:後到者排隊(執行中再觸發 → 一個 queue item,不會疊多個);後端與前端 job 可同時跑(2 executors)。一分鐘內多個 commit 合成一次 build,部署最新 commit。 |
| 只改 `k8s/**`、`docs/**`、`ci/**`、根目錄檔案 | 不觸發任何 job。改 `ci/jenkins/**`(Jenkins 自身設定)要 `docker compose --profile ci up -d --build` 重建 Jenkins 才生效;改 k8s manifests 仍手動 `kubectl apply`。 |
| 同一個 commit 同時改 `backend/**` 與 `frontend/**` | 兩個 job 各自觸發、各自上板,互不等待。 |
| main 以 merge commit 合入 | Git plugin 比對 merge commit 會列出對每個 parent 的差異,可能讓兩個 job 都被觸發(多跑一次同內容 build,無害但浪費)。本專案 main 慣例為單一 commit 直推(fast-forward),列為已知限制寫進 runbook。 |
| Jenkins 第一次啟動或 `jenkins_home` volume 重建 | job 沒有建置紀錄,第一次輪詢視為有變動(Git plugin「No previous build, so forcing an initial build」)→ 兩個 job 各自動跑一次(建立 baseline,並把當下 main 部署上去)。 |
| 手動指定 `BRANCH` 非 main | 內容切到該分支、tag 為該分支 sha、照常測試/部署(會把分支版本上到本機 k8s,直到下次 main 有變動被覆蓋);**輪詢基準不受影響**(SCM 固定 main),之後 main 的 push 照常觸發。`BRANCH` 不符白名單或分支不存在 → `Preflight`/`Checkout` FAILURE。 |
| `kubectl apply -f k8s/backend.yaml`(手動)在 pipeline 接手後執行 | image 字串由 `<sha7>` 變回 `:local` → 觸發一次 rollout,但 `:local` = 最近一次成功上板的 image(同 ID),內容不變;`rollout history` 多一筆 `:local` revision;下一次 pipeline 再改回 sha。寫進 runbook 當已知副作用。 |
| 舊 image 清理 | 只在 build 成功時執行;只動該 repo 名下 7~12 碼 hex tag;取不到 ReplicaSet 清單(kubectl 失敗)時略過本次清理;以 image ID 分組、保留最新 5 個 ID + `local` + Deployment 既存 ReplicaSet 引用的 image;單筆 `docker rmi` 失敗只警告。Deploy 失敗的 build 留下的 image 由下一次成功 build 清掉。 |
| HPA 在 rollout 期間擴容 | 新 pod 冷啟動 CPU 衝高可能讓 HPA 加副本,rollout 時間拉長、同機資源更吃緊;屬正常行為,`rollout status` 300 s 已含此餘裕,不另處理。 |
| Jenkins 容器內 docker socket 權限 | socket 為 `root:root 660`;compose `group_add: ["0"]` 讓 `jenkins`(uid 1000)可讀寫,不以 root 執行 Jenkins。 |
| 主機 8088 被佔 | compose 起不來(port 衝突訊息);runbook 說明設 `JENKINS_HTTP_PORT`(compose 綁埠與 JCasC `location.url` 皆讀它)。 |
| GitHub 連不到(離線) | 輪詢失敗只記在 polling log,不產生 build;手動 Build 會在 `Checkout` 失敗。 |
| 記憶體不足(Jenkins + Maven + node + k8s pods 同機) | compose `mem_limit: 4g` 保護主機;超出時 Maven/node 被 OOM kill → build FAILURE 可見,不會拖垮 k8s。 |

## 6. 非目標(範圍外)

- AWS:ECR push、EKS/EC2 部署、IAM(保留接點,不實作)。
- GitHub webhook 與隧道(ngrok/smee)、多分支/PR 自動建置、通知(email/Slack)。
- Jenkins TLS/反向代理、備份、HA、Docker/Kubernetes 動態 agent、共享函式庫(Shared Library)。
- pipeline 不 `kubectl apply` manifests、不部署監控、不跑 DB migration、不管理應用 Secret。
- 不改後端/前端應用程式碼、測試與 Dockerfile(前端 Dockerfile `npm install` → `npm ci`、Node 20 → 22 升級記為技術債)、不改 `k8s/backend.yaml` / `k8s/frontend.yaml`(image 仍寫 `:local`,由 pipeline 成功後同步加 tag)。
- 不做 Jenkins 自身的監控接入 Prometheus;不做 build 中止時的自動補救。

## 7. 驗收條件

> 驗收前置條件(AC-5 起):`docker compose up -d`(MySQL/Redis)、Docker Desktop k8s 啟用且 metrics-server 就緒、`kubectl apply -f k8s/backend.yaml -f k8s/frontend.yaml` 且 pods Ready、`scripts/ci-bootstrap.ps1` 已跑。執行順序:AC-1~7 → AC-9~13 → **最後**做 AC-8(同時驗證 AC-9/AC-11 用過 `BRANCH` 後 main 輪詢仍有效)→ AC-14/15。

| # | 條件 |
|---|------|
| AC-1 | 執行 `scripts/ci-bootstrap.ps1` 後:k8s default namespace 有 SA `jenkins-deployer`、Role、RoleBinding、token Secret(`.data.token` 非空);產出 `ci/jenkins/secrets/kubeconfig`(UTF-8 無 BOM、LF)且 `git status` 看不到它。以該 kubeconfig 執行 `kubectl auth can-i`:`patch deployments`=yes、`get pods`=yes、`list replicasets`=yes、`get events`=yes、`get pods --subresource=log`=yes、`delete deployments`=no、`create deployments`=no、`get secrets`=no、`get configmaps`=no、`-n monitoring get pods`=no。 |
| AC-2 | `docker compose --profile ci up -d --build` 後 3 分鐘內 `http://127.0.0.1:8088/login` 回 200,admin 可登入;無 setup wizard;job 列表恰為 `ticket-backend`、`ticket-frontend`,各:SCM 分支 `*/main`、每分鐘輪詢、對應 includedRegions、`BRANCH` 參數預設 main、quiet period 0、不並行。`docker compose up -d`(不帶 profile)不會啟動 jenkins。 |
| AC-3 | `netstat` 顯示 Jenkins 綁在 `127.0.0.1:8088`,無 `0.0.0.0:8088`、無 50000;從主機 LAN IP 連 8088 被拒。 |
| AC-4 | Jenkins 容器內:`whoami`=jenkins;`docker ps`、`kubectl get deployment ticket-backend`(經 `KUBECONFIG`)、`JAVA_HOME=/opt/java/temurin-17 mvn -v`(Java 17)、`node -v`(v20)、`kubectl version --client`(v1.34.x)、`date`(Asia/Taipei)皆正確。 |
| AC-5 | 前置條件達成且 Jenkins 首次啟動後 3 分鐘內,兩個 job 各自動**開始**一次 baseline build(無人手動觸發);完成時限與結果依 AC-6 / AC-7。 |
| AC-6 | 後端 build SUCCESS 的證據:JUnit 報告 tests = 53、failures = errors = 0;`TEST-…QuotaRedisRepositoryRedisTest.xml` 的 `tests="13"`;`docker images` 有 `ticket-backend:<sha7>` 且與 `:local` 同 IMAGE ID;`kubectl get deployment ticket-backend -o jsonpath='{.spec.template.spec.containers[0].image}'` = `ticket-backend:<sha7>`;pods 全 Ready;`kubectl rollout history` 的 CHANGE-CAUSE 含 job 名、build 號、sha;整個 build ≤ 10 分鐘(首次含下載依賴 ≤ 15 分鐘)。 |
| AC-7 | 前端 build SUCCESS 的證據:JUnit 報告 tests = 17、failures = 0;`ticket-frontend:<sha7>` 與 `:local` 同 ID;Deployment `ticket-frontend` 的 image = `ticket-frontend:<sha7>`;`http://localhost` 回 200 且 `/api/health` 經 nginx 反代回 200;build ≤ 5 分鐘。 |
| AC-8 | **即時性與分流**(最後執行):push 一個只動 `frontend/**` 的可見小改動到 main,記錄 push 完成時刻 → `ticket-frontend` 的 build 開始時刻 − push 時刻 ≤ 90 秒、`ticket-backend` 無新 build → build 完成後 `http://localhost` 看得到該改動。反向:push 只動 `backend/**` → 只有 `ticket-backend` 跑。push 只動 `docs/**` → 等 2 分鐘兩個 job 都沒有新 build。 |
| AC-9 | **測試閘門**:`Build with Parameters` 以 `BRANCH=ci-test/<x>`(含一顆必敗的後端測試)→ FAILURE 於 `Test`,`Build Image`/`Deploy` 未執行,`docker images` 無該 sha、Deployment image 不變。前端同樣用一顆必敗 vitest 驗證。 |
| AC-10 | **Redis 閘門**:(a) `check-redis-suite.sh` 對樣本報告 `tests="0" skipped="0"`(即 2026-09-24 本機 Redis 未啟動時產生的真實報告格式)回非零、對 `tests="13"` 且無 failures 回 0、對檔案不存在回非零;(b) 真實 build 的該報告 `tests="13"`;(c) 預先建立同名殘留容器 `ci-redis-backend` 後手動 build → pipeline 自行清掉殘留並 SUCCESS。 |
| AC-11 | **自動回滾**:`BRANCH` 指向會讓後端啟動失敗/readiness 不過的一次性分支 → `Deploy` 偵測 rollout 逾時 → 自動 undo → Deployment image 回到前一個 sha、pods 全 Ready、HPA 正常、build FAILURE 且 log 含 `describe` 的 Events 區;`:local` 仍指向前一個成功版本。前端以壞掉的 `nginx.conf` 分支同樣驗證。驗證後一次性分支刪除。 |
| AC-12 | **舊 image 清理**:(a) 以虛擬 repo `ticket-prune-test` 建 7 個內容不同的 image(7 碼 hex tag)+ `:local`,執行 `prune-images.sh ticket-prune-test 5 <最舊的一個 tag>` → 剩最新 5 個 + 被保護的最舊 1 個 + `local`,另 1 個被解 tag;重跑一次無變化(冪等);(b) 真實後端 build 的 `Cleanup` log 顯示腳本被以 `ticket-backend 5 <RS 引用的 image 清單>` 呼叫,`:local` 與 Deployment 使用中的 tag 仍在。 |
| AC-13 | **不並行**:`ticket-backend` 執行中再 `Build with Parameters` 一次 → 第二次進佇列顯示等待,待第一次完成才開始。 |
| AC-14 | **文件與版控**:runbook、現況 spec、`CLAUDE.md`、`k8s/README.md`(含手動備援與 apply 副作用)、頂層需求書目錄更新;本需求書狀態改「已完成」並附驗收紀錄;所有 commit 已 push,`git status` 乾淨;`ci/jenkins/secrets/` 不在版控內(`git ls-files` 查無)。 |
| AC-15 | **既有測試不被改動**:後端 53 顆(含 Redis 類 13 顆)、前端 17 顆 vitest,測試檔案內容在本功能的所有 commit 中無任何修改(`git diff <起點>..HEAD --stat` 不含 `src/test`、`*.test.js(x)`),且都在 pipeline 內實際通過。 |

## 8. 測試要求

本功能是基礎建設,沒有新的應用程式單元測試可寫;「測試」定義為以下項目**實際執行**並把結果(時間、數字、指令輸出摘要)記在本需求書 §10 驗收紀錄:

- **靜態驗證**:JCasC 於 Jenkins 啟動時驗證(無效即起不來,容器 log 可見);兩份 Jenkinsfile 以 Jenkins 內建 declarative linter(`POST /pipeline-model-converter/validate`)檢查通過;`kubectl apply --dry-run=client -f k8s/ci/jenkins-rbac.yaml` 通過;`docker compose --profile ci config` 可解析。
- **腳本層測試**(可在 Jenkins 外跑):`check-redis-suite.sh` 三種輸入(AC-10a)、`prune-images.sh` 虛擬 repo(AC-12a)。
- **端對端**:AC-5 ~ AC-13 逐條執行,三條失敗路徑(測試失敗、Redis 閘門、rollout 回滾)不可省略;AC-8 放最後以同時證明 `BRANCH` 不破壞輪詢。
- **既有測試套件不可改**(AC-15):後端 53 顆、前端 17 顆必須在 **pipeline 內**通過,不是只在本機通過(本機先前的「40 顆」是 Redis 類被跳過的數字);任何一顆在 CI 內失敗都要照根目錄規則第 1 條分類處理(A 實作 / B 測試 / C 規格),不得改弱。
- 失敗路徑一律用一次性分支 `ci-test/<說明>` + `BRANCH` 參數驗證,驗證完刪分支,不把壞 code 推上 main。

## 9. 決策紀錄

| # | 問題 | 決定 | 理由 |
|---|------|------|------|
| 1 | Jenkins 跑哪裡、怎麼建置? | docker compose 旁(k8s 外),控制器 image 內建 Maven/Node/docker CLI/kubectl,pipeline 直接在控制器上建置 | 零件最少、最好除錯;像真實的獨立 CI 主機;掛 Docker socket 讓 image 直接進 k8s 共用 store 免 registry(實測容器內可連 socket 與 `kubernetes.docker.internal:6443`)。「控制器不建置」是正式環境原則,本機單人可接受;sibling 容器 agent / k8s 動態 agent 列為日後選項。(使用者 2026-10-01 選定) |
| 2 | 「即時」怎麼觸發? | 每分鐘輪詢 GitHub main + Git plugin 路徑過濾 | 本機 Jenkins 無公開網址,GitHub webhook 打不進來;輪詢零額外工具、離線不會壞;上雲後改 webhook 即可。(使用者選定) |
| 3 | kubectl 用什麼身分? | 專用 SA `jenkins-deployer` + 最小 Role(default ns 的 deployments patch 等),token kubeconfig 掛進 Jenkins | 與 v0.6 kube-state-metrics 最小權限原則一致;pipeline 腳本拿不到 Secret(JWT/DB 密碼)、刪不了資源、碰不到 monitoring;也是 EKS 標準做法。(使用者選定) |
| 4 | 開發期間可否直接 push main? | 允許分多次直接 push | Jenkins 必須從 GitHub 拉到 Jenkinsfile 才能跑,無法純本機驗證;最後回報 commit 清單。(使用者選定) |
| 5 | Jenkins 用哪個埠、綁哪裡? | `127.0.0.1:8088`,不開 agent 埠 | 主機 8080 已被工作用 Tomcat 佔用;Jenkins 握 docker socket 等同主機 root,絕不可對公司 LAN 開放(`0.0.0.0`)。 |
| 6 | image 怎麼命名? | 不可變 tag `<sha7>`;**rollout 成功後**才同步打 `:local` | sha 可追溯、`rollout undo` 才有不同版本可回;`:local` 延後打,回滾目標若是 `:local` revision 也能拿到舊內容(審查 #4)。 |
| 7 | pipeline 要不要 `kubectl apply` manifests / 負責初次安裝? | 不要,只 `set image`;Deployment 不存在就 Preflight 失敗提示 | RBAC 保持最小(apply 需要 configmaps/secrets/services/hpa 的 create);manifests 變更少、手動 apply 即可;避免 `:local` 與 sha 兩種來源互相覆蓋。 |
| 8 | 失敗怎麼處理? | 測試不過不 build/不部署(FAILURE,非 UNSTABLE);rollout 逾時自動 `rollout undo` | 「即時上板」不能犧牲防超賣核心的穩定;回到前一版比停在半套新舊混跑安全;`junit` 步驟預設只標 UNSTABLE 並續跑,故以 `sh` 非零為準(審查 #11)。 |
| 9 | Redis 整合測試在 CI 怎麼跑、怎麼確保沒被跳過? | pipeline 自起拋棄式 Redis 兄弟容器(`--network container:`)+ `check-redis-suite.sh` 檢查該 testsuite `tests>0` | 測試寫死 `localhost:6380`;compose sidecar 在 Jenkins 容器重啟後命名空間失效(審查 #8),pipeline 自管每次全新;JUnit 5 在 `@BeforeAll` Assumption 失敗時 surefire 寫 `tests="0" skipped="0"`,用 skipped 判斷抓不到(審查 #1,已以既有報告實證)。 |
| 10 | 要不要 `BRANCH` 參數?怎麼做? | 要,預設 main;**SCM 固定 `*/main`**,Jenkinsfile 內 `git fetch`/`checkout FETCH_HEAD` 切內容 | 若把 `${BRANCH}` 放進 SCM 分支,輪詢會用上一次 build 的參數值,跑過一次 ci-test 分支後 main 就不再自動觸發(審查 #2);固定 main 讓輪詢基準不受參數影響,代價是 Jenkinsfile 永遠來自 main(可接受)。 |
| 11 | 舊 image 要不要清、怎麼清? | 清:獨立腳本,以 image ID 分組保留最新 5 個 + `local` + Deployment 既存 RS 引用的 image;僅成功 build 執行 | 防磁碟無限成長;保護 RS 引用的 image 讓任何 `rollout undo` 都有 image(審查 #7);獨立腳本可用虛擬 repo 測試,不必靠 6 次真實 build。 |
| 12 | Jenkins 與 plugin 版本? | base `2.580.1-lts-jdk21` + 另裝 Temurin 17 給 Maven;plugins 在 `plugins.txt` 鎖版 | 官方 jdk17 映像線止於 2.541.3、已 EOL,最新 plugin 的 requiredCore 已追到 2.541.x(審查 #9);Jenkins 的 Java 與 app 的 Java 脫鉤;鎖版才能「砍 volume/重 build 完整重建」。 |
| 13 | Jenkins 以 root 跑? | 否,`jenkins`(uid 1000)+ `group_add: ["0"]` | 非 root 原則;socket 為 root:root 660,加入 gid 0 即可讀寫。 |
| 14 | 通知? | 無 | 本機沒 SMTP;看 Jenkins UI。 |
| 15 | Jenkinsfile 放哪? | `backend/Jenkinsfile`、`frontend/Jenkinsfile`(各自目錄內) | 與「分前後端」一致,路徑過濾自然涵蓋 Jenkinsfile 本身的變更;不用 Shared Library(多一個 repo/多一層抽象,兩份 deploy 邏輯重複 ~20 行可接受)。 |
| 16 | 兩個 job 的 Maven/Node 是否在容器內另起? | 否,直接用 image 內建工具 | 同 #1;`~/.m2`、`~/.npm` 落在 volume 持久化即可快取。 |
| 17 | plugins 清單 | 明列 `junit` | `workflow-aggregator`/`pipeline-model-definition`/`git` 的相依都不含 `junit`(查 plugins.jenkins.io 確認),沒有它 `junit` 步驟不存在(審查 #3)。 |
| 18 | RBAC 要不要 `events`? | 要(core 與 `events.k8s.io` 的 get/list) | `kubectl describe pod` 無權限時靜默省略 Events,回滾診斷會看不到 readiness/CrashLoop 原因(審查 #10)。 |
| 19 | 手動備援怎麼寫? | `docker build :local` + `kubectl set image …:local`;說明 `apply` 會改回 `:local` 的副作用 | pipeline 接手後 Deployment 在 sha tag,單純 `rollout restart` 不會換到新 build(審查 #5)。 |
| 20 | kubeconfig 掛哪? | `/etc/jenkins/kubeconfig:ro` + `KUBECONFIG` 環境變數 | 不放 named volume 內的 `~/.kube`(root 建目錄、單檔 bind 更新不同步)(審查 #17)。 |
| 21 | 時限怎麼寫? | 「約 1~1.5 分鐘內開始」、AC-8 以 push 完成→build 開始 ≤ 90 s 量測、`quietPeriod(0)` | 每分鐘輪詢 + fetch 比對 + quiet period,「一分鐘內」保證不了(審查 #12)。 |
| 22 | 時區/記憶體 | `TZ=Asia/Taipei`、`-Duser.timezone`、JVM `-Xmx1g`、`mem_limit: 4g` | log 時間與 MySQL 一致;同機還有 k8s pods,避免 Jenkins 建置吃垮主機(審查 #20)。 |
| 23 | Node 20 已 EOL 仍用? | 用,對齊 `frontend/Dockerfile` 的 `node:20-alpine`;升級列技術債 | 本功能不改 Dockerfile;CI 的 Node 與 image 內一致比較重要(審查 #19)。 |
| 24 | merge commit 過度觸發 | 接受為已知限制,runbook 註明 main 用單 commit 直推 | Git plugin 對 merge commit 的 path 比對會列對各 parent 的差異(審查 #14);本專案慣例已是單 commit。 |
| 25 | 長效 token Secret 怎麼建? | `kubernetes.io/service-account-token` + annotation 綁 SA,不列入 SA `.secrets`;bootstrap 等 token 填入 | k8s 1.34 仍支援;列入 `.secrets` 會被視為 auto-generated 而受 legacy token 清理影響(審查 #16)。 |
| 26 | Maven 要不要 `clean`? | 要,`mvn -B clean test` | 實測:workspace 重用時,前一個 build(別的分支)留下的 `TEST-*.xml` 會被 `junit` glob 誤收,造成測試報告 failCount 與 stage UNSTABLE 失真(Maven 本身 53/0);`clean` 砍掉 `target/` 根治。 |
| 27 | deploy 邏輯放哪? | 抽成 `ci/jenkins/scripts/deploy.sh` 由兩個 Jenkinsfile 共用(非 Shared Library) | 回滾與診斷邏輯約 50 行,兩份複製易漂移;腳本在 repo 內、可在 Jenkins 外測,仍符合 #15 不另開 Shared Library。 |
| 28 | 第二輪 code review(實作後)的小修正 | Cleanup 取不到 ReplicaSet 清單時略過清理;`deploy.sh` 拒絕 `replicas=0` 的 Deployment、CrashLoop 時補 `logs --previous`;`ci/jenkins/.dockerignore` 排除 `secrets/`;compose 加 `memswap_limit: 4g`;bootstrap 偵測 kubeconfig 被 compose 先建成目錄;tag regex 改 7~12 碼;移除 JCasC `useScriptSecurity: false`(刪掉 volume 內持久化的設定檔後重啟驗證:JCasC 載入的 Job DSL 仍建立 2 個 job,且 job-dsl/JCasC 整合會自行把該設定持久化為 false——顯式宣告是多餘的,效果不變;安全含意寫進 runbook);文件修正「scripts 跟分支走、只有 Jenkinsfile 來自 main」 | code-reviewer 2026-10-01 第二輪 #2~#8、#10、#11,皆低嚴重度,一併處理;高/中嚴重度只有 stale surefire 報告(#26 已修)。 |

## 10. 驗收紀錄(2026-10-01)

環境:Windows 11 + Docker Desktop 4.73.1(Engine 29.4.3、k8s v1.34.1 kubeadm 單節點 `docker-desktop`);Jenkins image `ticket-jenkins:local`(`jenkins/jenkins:2.580.1-lts-jdk21` + Temurin 17.0.20.1、Maven 3.9.16、Node 20.20.2、docker CLI 29.8.2、kubectl v1.34.1,1.8 GB,73 個 plugin 鎖版);app 由 `kubectl apply -f k8s/backend.yaml -f k8s/frontend.yaml` 先部署好。baseline 的 main 為 `9ef77d7`。

### 靜態與腳本層

| 項目 | 結果 |
|------|------|
| `kubectl apply --dry-run=client -f k8s/ci/jenkins-rbac.yaml` | ✅ 4 個物件 created (dry run) |
| `docker compose --profile ci config --services` | ✅ `jenkins mysql redis`;不帶 profile 只有 `mysql redis` |
| `bash -n` 四支腳本、PowerShell parser 解析 `ci-bootstrap.ps1` | ✅(ps1 必須存成 **UTF-8 with BOM**,PS 5.1 讀無 BOM 檔會把中文當 ANSI 而解析失敗,已修) |
| Jenkins declarative linter(`/pipeline-model-converter/validate`) | ✅ `backend/Jenkinsfile`、`frontend/Jenkinsfile` 皆 "successfully validated" |
| JCasC 啟動驗證 | ✅ 第一版 `crumbIssuer.excludeClientIPFromCrumb` 在 2.580 已移除導致 BootFailure → 改 `crumbIssuer: "standard"` 後啟動;Job DSL log `createOrUpdateConfig for ticket-backend / ticket-frontend` |
| `check-redis-suite.sh`(AC-10a) | ✅ 真實 `tests="0" skipped="0"` 報告 → exit 1;`tests="13"` → exit 0;缺檔 → exit 1;`failures="1"` → exit 1 |
| `prune-images.sh`(AC-12a) | ✅ 虛擬 repo `ticket-prune-test` 7 個不同 image + `local`,`keep 5 protect a000001` → 只移除 `a000002`,剩 5 新 + 受保護 1 + `local`;重跑移除 0(冪等) |

### 驗收條件

| AC | 結果 | 證據 / 數字 |
|----|------|-------------|
| AC-1 | ✅ | `scripts/ci-bootstrap.ps1 -SkipCompose`:SA/Role/RoleBinding/Secret created;kubeconfig 產出(UTF-8 無 BOM、0 個 CR、19 行),`git status` 看不到(`.gitignore:44` 命中)。can-i 10/10 符合:patch deployments / get pods / list replicasets / get events / get pods --subresource=log = **yes**;delete deployments / create deployments / get secrets / get configmaps / get pods -n monitoring = **no** |
| AC-2 | ✅ | `docker compose --profile ci up -d --build` 後 `/login` 200;jobs API 恰 `ticket-backend`、`ticket-frontend`;無 setup wizard(`runSetupWizard=false`);job 設定由 Job DSL 建:SCM `*/main`、`pollSCM * * * * *`、includedRegions `backend/.*` / `frontend/.*`、`BRANCH` 預設 main、quietPeriod 0、disableConcurrentBuilds;不帶 profile 的 `docker compose up -d` 只起 mysql/redis |
| AC-3 | ✅ | `netstat`:僅 `127.0.0.1:8088 LISTENING`,無 `0.0.0.0:8088`、無 50000 |
| AC-4 | ✅ | 容器內 `whoami`=jenkins(groups jenkins,root);`docker ps` 可用;`kubectl get deployment ticket-backend` 可用(`KUBECONFIG=/etc/jenkins/kubeconfig`)且 `can-i delete deployments`=no;`mvn -v` Java 17.0.20.1(/opt/java/temurin-17);`node -v` v20.20.2;kubectl Client v1.34.1;`date` CST;JVM `-Xmx1g -Duser.timezone=Asia/Taipei` |
| AC-5 | ✅ | 新 volume `up -d` 12:19:29 → 12:20:47 `ticket-backend #1`、12:20:48 `ticket-frontend #1` 自動開始(≈ 80 秒,無人觸發) |
| AC-6 | ✅ | backend #1(main @ 9ef77d7)SUCCESS **254 s**(含首次 Maven 依賴下載,Maven Total time 43 s;docker build 含拉 base image);JUnit pass 53 / fail 0 / skip 0;Redis 閘門 `tests=13 failures=0 errors=0 skipped=0`;`ticket-backend:9ef77d7` 與 `:local` 同 ID `f6aac7ee7ba2`;Deployment image = `ticket-backend:9ef77d7`;pods 2/2 Running;`rollout history` rev2 CHANGE-CAUSE `jenkins ticket-backend #1 9ef77d7`;`/api/health` 200。暖快取的 main 重建(#4)17 s |
| AC-7 | ✅ | frontend #1 SUCCESS **71.5 s**;vitest 17 passed(JUnit pass 17);`ticket-frontend:9ef77d7` 與 `:local` 同 ID `7a8db71b0f9b`;Deployment image = `ticket-frontend:9ef77d7`;`http://localhost` 200、`/api/health` 200(經 nginx 反代);rollout history rev2 change-cause |
| AC-8 | ✅ | **後端 only**:push `8edfa0a`(只動 `backend/Jenkinsfile`)完成 12:36:56 → `ticket-backend #5` 開始 12:37:29(**33 s**),`ticket-frontend` 未觸發;#5 SUCCESS 88 s,Deployment → `ticket-backend:8edfa0a`。**前端 only**:push `67a4128`(只動 `frontend/index.html` 標題)完成 12:37:36 → `ticket-frontend #4` 開始 12:38:27(**51 s**),`ticket-backend` 仍停在 #5;#4 SUCCESS,Deployment → `ticket-frontend:67a4128`,`curl http://localhost/` 可見 `<title>搶票系統 Ticket System</title>`。**docs only**:push `9563bc9`(只動 `docs/`、`CLAUDE.md`、`k8s/README.md`、`ci/jenkins/README.md`)完成 12:51:59 → 135 s 後(12:54:15)兩個 job 的 lastBuild 仍為 #5 / #4,無新 build。**同 commit 改兩端**(§5 邊界):push `551af74`(backend/Jenkinsfile + frontend/Jenkinsfile + scripts)12:54:47 → 12:54:54 `ticket-backend #6` 與 `ticket-frontend #5` 同時開始、各自 SUCCESS(84.7 s / 58.6 s),Deployment 皆 → `551af74`。所有 push 都在 AC-9/AC-11 用過 `BRANCH` 之後,證明輪詢基準不受參數影響。 |
| AC-9 | ✅ | `BRANCH=ci-test/backend-fail-test`(#2,新增必敗 `CiGateFailingTest`)FAILURE 23.9 s:Checkout/Preflight SUCCESS、**Test FAILED**,Build Image/Deploy/Cleanup 未執行;JUnit fail 1;console `CiGateFailingTest.ciGate_alwaysFails <<< FAILURE!`。`BRANCH=ci-test/frontend-fail-test`(#2,新增必敗 `ci-fail.test.js`)FAILURE 17.7 s:Test FAILED、其後未執行;vitest 1 failed / 17 passed。`docker images` 仍只有 `9ef77d7` + `local`,兩個 Deployment image 不變 |
| AC-10 | ✅ | (a) 見上表腳本層;(b) 真實 build 報告 `tests=13`;(c) 事前 `docker run -d --name ci-redis-backend alpine sleep` 種下殘留容器後跑後端 build → pipeline `docker rm -f` 清掉並正常起 Redis、跑完 53 顆,事後 `docker ps -a` 查無該容器 |
| AC-11 | ✅ | **後端** `BRANCH=ci-test/backend-crash`(#3,`server.port` 改 9099)FAILURE 340 s:Deploy 於 300 s 逾時 → 診斷(describe Events `Startup probe failed: Get "http://10.1.0.92:8099/api/health": dial tcp...`、logs `Tomcat started on port 9099`)→ `rollout undo` → 「已回滾:ticket-backend 現在是 ticket-backend:9ef77d7」;舊 pod 2 個全程 Running,HPA `cpu: 2%/50%` 2/2;`:local` 仍 = 9ef77d7。**前端** `BRANCH=ci-test/frontend-crash`(#3,nginx.conf 無效指令)FAILURE 202 s:180 s 逾時 → Events `Back-off restarting failed container nginx`、logs `nginx: [emerg] unknown directive "ci_test_invalid_directive"` → undo → 9ef77d7;`:local` 仍 = 9ef77d7。兩者 `rollout history` 各多 rev3(壞版)+ rev4(回滾) |
| AC-12 | ✅ | (a) 見腳本層;(b) backend #1 Cleanup log `prune: ticket-backend → 保留 1 個 sha tag、移除 0 個(keep=5,protected=[local 9ef77d7 local])`;#4 時 `protected=[local 9ef77d7 d2dd9fe local]`——RS rev3 引用的壞版 `d2dd9fe` 被保護,符合 F-14 |
| AC-13 | ✅ | #3 執行中再 `buildWithParameters`(BRANCH=main)→ queue API `BlockedItem`,why `Build #3 is already in progress (ETA: 1 min 48 sec)`;#3 結束後該項以 #4 執行(17 s SUCCESS,「== Deploy 9ef77d7 → 9ef77d7」set image 無變化、rollout 立即完成——同 sha 重跑邊界實證) |
| AC-14 | ✅ | runbook `ci/jenkins/README.md`、現況 spec `docs/deployment/ci-cd-jenkins.md`、`CLAUDE.md`、`k8s/README.md`、`docs/deployment/architecture.md`、頂層 `docs/requirements/README.md` 與 `v0.7/README.md` 已更新;本需求書狀態已完成。commit:`9ef77d7`(CI 主體)、`8edfa0a`(clean test)、`67a4128`(前端標題)、`9563bc9`(文件)、`551af74`(review 修正)、收尾文件 commit。`git ls-files ci/jenkins/secrets` 只有 `.gitkeep`,kubeconfig 不在版控;`git status` 乾淨(收尾 push 後確認) |
| AC-15 | ✅ | `git diff --stat 640c4c1..HEAD -- backend/src/test frontend/src/*.test.* frontend/src/test` 為空;應用程式碼自 v0.6 起只動了 `frontend/index.html` 的 `<title>`。後端 53 顆、前端 17 顆都在 pipeline 內實跑通過(backend #1/#5/#6、frontend #1/#4/#5) |

### 過程中發現並修正

1. JCasC `crumbIssuer.excludeClientIPFromCrumb` 在 Jenkins 2.580 已移除 → `crumbIssuer: "standard"`。
2. `ci-bootstrap.ps1` 需 UTF-8 with BOM(PS 5.1)。
3. `jenkins-plugin-cli --list` 在 stdout 非 TTY 時標頭是 `Resulting plugin list:`(非 `Installed plugins:`),`pin-plugins.sh` 兩者都認。
4. **stale surefire 報告**:#3/#4(main)的 Jenkins 測試報告 failCount=1、Test stage UNSTABLE,但 Maven 實際 53/0——是 #2(fail-test 分支)留在 workspace 的 `TEST-com.example.ticket.CiGateFailingTest.xml` 被 `junit` glob 收進去(編譯產物已被 compiler 清掉,只剩報告檔)。修法 `mvn -B -ntp clean test`(決策 #26),由後續 main build 驗證 failCount=0。
5. `docker compose --profile ci down` 會連 MySQL/Redis 容器一起停(資料 volume 保留)→ runbook 改教 `rm -sf jenkins`。
6. 第二輪 code review 的低嚴重度項目(決策 #28)於 `551af74` 修正;backend #6 / frontend #5 驗證通過(`deploy.sh` 印出 `replicas 2`,Cleanup 保護清單含所有 RS 引用版本)。`mvn clean test` 生效後 backend #5/#6 的 Jenkins 測試報告 failCount=0、Test stage SUCCESS。
7. 第三輪 code review(針對 #6 的修正 commit):`deploy.sh` 把「kubectl 失敗」與「replicas=0」分開回報、replicas 非數字也中止;Cleanup 註明 `pipefail` 是 `||` 保護的前提;`useScriptSecurity` 的移除改以「刪除 volume 內持久化設定後重啟」實測(見決策 #28)。其餘項目經實跑確認為誤判:Cleanup 的 `||` 在 `set -uo pipefail` 下可攔到 kubectl 失敗、`--previous` 區塊對未重啟 pod 安靜略過且暫存檔會清掉、ps1 try/catch 行為正確、`memswap_limit` 與 `.dockerignore` 已生效、驗收紀錄數字與 git 一致。
