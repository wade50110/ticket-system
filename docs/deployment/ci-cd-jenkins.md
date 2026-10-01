# CI/CD:Jenkins 即時上板(v0.7 現況)

> 本文件描述「現在是怎麼運作的」;需求與決策歷程見 [`../requirements/v0.7/jenkins-cicd.md`](../requirements/v0.7/jenkins-cicd.md),操作 runbook 見 [`../../ci/jenkins/README.md`](../../ci/jenkins/README.md)。改 Jenkinsfile、部署流程、RBAC 前先讀本文第 3 節。

## 1. 現況規格

### 1.1 一句話

push 到 GitHub `main` → Jenkins(docker compose,k8s 外)每分鐘輪詢;`backend/**` 變動跑 `ticket-backend` job、`frontend/**` 變動跑 `ticket-frontend` job → 測試 → `docker build` 出 `ticket-xxx:<git sha7>` → `kubectl set image` 滾動更新本機 Docker Desktop k8s → rollout 成功才把 `:local` 指到同一 image;測試不過不上板、rollout 逾時自動 `rollout undo`。

```
GitHub main ──poll 每分鐘(路徑過濾)──► Jenkins 容器 ticket-jenkins(127.0.0.1:8088)
                                        │ 內建 Maven(Temurin 17)/ Node 20 / docker CLI / kubectl 1.34
                                        ├─ /var/run/docker.sock ─► 主機 docker daemon(image 直接進 k8s 共用 store,免 registry)
                                        └─ KUBECONFIG=/etc/jenkins/kubeconfig(SA jenkins-deployer,最小 Role)─► k8s default ns
```

### 1.2 兩條 pipeline

| | `ticket-backend` | `ticket-frontend` |
|---|---|---|
| Jenkinsfile | `backend/Jenkinsfile` | `frontend/Jenkinsfile` |
| 觸發路徑(regex) | `backend/.*` | `frontend/.*` |
| 階段 | Checkout → Preflight → Test → Build Image → Deploy → Cleanup | Checkout → Preflight → Install → Test → Build Image → Deploy → Cleanup |
| Test | 起拋棄式 Redis(`ci-redis-backend`,加入 Jenkins 網路命名空間)→ `mvn -B clean test`(JDK 17;`clean` 避免上一個 build 的 surefire 報告殘留被 junit 誤收)→ `junit` 報告 → **Redis 閘門** `check-redis-suite.sh` | `npm ci` → `vitest run --reporter=junit`(報告寫到 `ci-reports/`,在 build context 外)→ `junit` 報告 |
| Build Image | `docker build -t ticket-backend:<sha7> ./backend` | `docker build -t ticket-frontend:<sha7> ./frontend` |
| Deploy | `deploy.sh ticket-backend ticket-backend <image> <change-cause> 300s ticket-backend` | `deploy.sh ticket-frontend nginx <image> <change-cause> 180s ticket-frontend` |
| 成功後 | `docker tag <image> ticket-backend:local` | `docker tag <image> ticket-frontend:local` |
| Cleanup | `prune-images.sh ticket-backend 5 <RS 引用的 tag>` | `prune-images.sh ticket-frontend 5 <RS 引用的 tag>` |
| 其他 | `timeout 30m`、`disableConcurrentBuilds`、`quietPeriod 0`、保留 30 筆 build;參數 `BRANCH`(預設 `main`) | 同左 |

失敗語意:任一 stage 失敗 = build **FAILURE**(測試失敗以 `sh` 的 exit code 判定,`junit` 步驟只發佈報告、不改結果),後續 stage 不執行;`Deploy` 失敗 = 已自動回滾到前一版(build 仍 FAILURE,log 含診斷)。

`deploy.sh`(兩條共用):`kubectl set image` → `annotate kubernetes.io/change-cause="jenkins <job> #<build> <sha7>"` → `rollout status --timeout` → 逾時:印 `get pods`、非 Ready pod 的 `describe`(含 Events)與 `logs` → `rollout undo` → 等回滾完成 → exit 1。

### 1.3 `BRANCH` 參數

job 的 SCM **固定 `*/main`**。Jenkinsfile 先 `checkout scm`(main:載入 Jenkinsfile、維持輪詢基準),`BRANCH != main` 時再 `git fetch origin <BRANCH>` + `git checkout --detach refs/remotes/origin/<BRANCH>` 把**整個工作樹**(含 `ci/jenkins/scripts/*.sh`)切到該分支;只有 Jenkinsfile 本身來自 main;sha7 取切換後 HEAD。信任邊界不變(能 push 分支的人也能 push main)。白名單 `^[A-Za-z0-9._/-]+$`。用途:驗證失敗路徑、預覽分支;會把該分支部署到本機 k8s,直到下次 main 有變動被覆蓋。

### 1.4 Jenkins 部署形態

| 項目 | 現況 |
|------|------|
| 位置 | `docker-compose.yml` 的 `jenkins` 服務,`profiles: ["ci"]`(`docker compose up -d` 不會起;`--profile ci` 才會) |
| image | `ci/jenkins/Dockerfile`:`jenkins/jenkins:2.580.1-lts-jdk21` + Temurin 17(`/opt/java/temurin-17`,`/etc/mavenrc` 讓 `mvn` 固定用它)+ Maven 3.9.16 + Node 20.20.2 + docker CLI/buildx(Docker apt repo)+ kubectl v1.34.1(sha256 驗證)+ `plugins.txt` 鎖版 73 個 plugin |
| 設定 | JCasC `ci/jenkins/casc.yaml`(admin、2 executors、agent 埠關閉、URL 讀 `JENKINS_HTTP_PORT`)+ Job DSL `ci/jenkins/jobs.groovy`;都烤進 image,改設定要 `docker compose --profile ci up -d --build` |
| 埠 / 帳號 | `127.0.0.1:${JENKINS_HTTP_PORT:-8088}`;`admin` / `${JENKINS_ADMIN_PASSWORD:-admin}` |
| 執行身分 | `jenkins`(uid 1000)+ `group_add: ["0"]`(讀寫 docker socket,不以 root 跑) |
| 資源 / 時區 | JVM `-Xmx1g`、`mem_limit: 4g` + `memswap_limit: 4g`(不給 swap)、`TZ=Asia/Taipei` + `-Duser.timezone` |
| 持久化 | volume `ticket-jenkins-home`(build 紀錄、`~/.m2`、`~/.npm`);設定不靠 volume,砍掉可原樣重建 |
| 部署身分 | `k8s/ci/jenkins-rbac.yaml`:SA `jenkins-deployer` + Role(見下表)+ 長效 token Secret;`scripts/ci-bootstrap.ps1` 產出 `ci/jenkins/secrets/kubeconfig`(gitignore),唯讀掛到 `/etc/jenkins/kubeconfig` |

Role(default namespace):

| 資源 | verbs | 用途 |
|------|-------|------|
| `apps/deployments` | get list watch patch update | set image、annotate、rollout status/undo |
| `apps/replicasets` | get list watch | rollout history/undo、Cleanup 保護清單 |
| `pods` | get list watch | 狀態、describe |
| `pods/log` | get | 回滾診斷 |
| `events`(core、`events.k8s.io`) | get list | describe 的 Events 區(無權限時 kubectl 靜默省略) |

沒有 create/delete、碰不到 Secret/ConfigMap/HPA/Service、碰不到其他 namespace。

### 1.5 image 命名與 `:local`

- 每次 build:`ticket-backend:<sha7>` / `ticket-frontend:<sha7>`(不可變);Deployment 的 image 由 pipeline 改成這個 tag。
- rollout 成功後才 `docker tag` 成 `:local`,所以 `:local` 永遠 = 最近一次**成功上板**的內容;`k8s/*.yaml` 仍寫 `:local`,手動 runbook 與 `kubectl apply` 不會壞(apply 會把 image 改回 `:local` 觸發一次同內容 rollout,屬已知副作用)。
- Cleanup:只動 7~12 碼 hex tag;以 image ID 分組、保留最新 5 個 ID + `local` + Deployment 既存 ReplicaSet 引用的 tag;`docker rmi` 不加 `-f`,失敗只警告。

## 2. 檔案地圖

| 檔案 | 說明 |
|------|------|
| `docker-compose.yml` | `jenkins` 服務(profile `ci`)、volume `ticket-jenkins-home` |
| `ci/jenkins/Dockerfile`、`plugins.txt` | 控制器 image 與鎖版 plugins(`scripts/pin-plugins.sh` 產出) |
| `ci/jenkins/casc.yaml`、`jobs.groovy` | JCasC 與 Job DSL |
| `ci/jenkins/scripts/deploy.sh` | 換版 + 自動回滾(兩條 pipeline 共用) |
| `ci/jenkins/scripts/check-redis-suite.sh` | Redis 整合測試閘門 |
| `ci/jenkins/scripts/prune-images.sh` | 舊 image 清理 |
| `ci/jenkins/scripts/pin-plugins.sh` | 從 image 讀 plugin 版本寫回 `plugins.txt` |
| `ci/jenkins/README.md` | runbook(啟動、日常、改設定、手動備援、疑難排解) |
| `backend/Jenkinsfile`、`frontend/Jenkinsfile` | pipeline 本體 |
| `k8s/ci/jenkins-rbac.yaml` | 部署用 SA / Role / RoleBinding / token Secret |
| `scripts/ci-bootstrap.ps1` | apply RBAC → 等 token → 產 kubeconfig(UTF-8 無 BOM、LF)→ can-i 矩陣 → `compose --profile ci up -d --build` |
| `.gitattributes` | `*.sh` / `Jenkinsfile` / `*.groovy` / `ci/jenkins/**` 一律 LF(Windows checkout 不得變 CRLF) |

## 3. 設計意圖(改之前先想清楚)

1. **SCM 固定 main,`BRANCH` 在 Jenkinsfile 內切換**:若把 `${BRANCH}` 放進 job 的 SCM 分支,Git plugin 輪詢會用「上一次 build 的參數值」展開,跑過一次測試分支後 main 就不再自動觸發。固定 main 讓輪詢基準永遠正確,代價是 Jenkinsfile 永遠來自 main。
2. **`:local` 延後到 rollout 成功後才打**:回滾目標若是 `:local` revision(首次接手、手動 apply 後),提前打 tag 會讓回滾拿到壞版。
3. **Redis 閘門看 `tests>0` 而不是 `skipped`**:`QuotaRedisRepositoryRedisTest` 在 `@BeforeAll` 的 Assumption 失敗時整類被跳過,surefire 寫 `tests="0" skipped="0"`;用 skipped 判斷會靜默放行(等同改弱測試)。閘門同時要求該類 failures=errors=skipped=0(類內任一顆被 `@Disabled`/skip 都擋,屬刻意從嚴)。後端全套是 53 顆(含這類 13 顆),本機沒開 Redis 時只會看到 40 顆。搭配 `mvn clean test`:workspace 重用時舊的 `TEST-*.xml` 會被 `junit` 誤收、也可能讓閘門看到上一輪的報告。
4. **測試用 Redis 由 pipeline 自起兄弟容器並加入 Jenkins 網路命名空間**:測試寫死 `localhost:6380`;compose sidecar(`network_mode: service:`)在 Jenkins 容器重啟後會留在失效的命名空間,pipeline 自管每次全新且失敗會喊。
5. **pipeline 只 `set image`,不 `kubectl apply`、不負責初次安裝**:RBAC 才能維持最小(apply 需要 configmaps/secrets/services/hpa 的 create);也避免 `:local` 與 sha 兩種來源互相覆蓋。
6. **測試失敗必須是 FAILURE 不是 UNSTABLE**:`junit` 步驟預設會把有失敗的 build 標 UNSTABLE 並續跑後面的 stage;這裡以 `sh` 的 exit code 為準,`junit` 加 `skipMarkingBuildUnstable`。
7. **只綁 127.0.0.1**:Jenkins 握有 docker socket = 主機 root 等級權限,能 push main 的人等於能在這台機器跑任意腳本;不可對 LAN 開放。
8. **base image 用 jdk21 + 另裝 Temurin 17**:官方 jdk17 映像線止於 2.541.3 已停更,最新 plugin 的 requiredCore 已追上;Jenkins 的 Java 與 app 的 Java 脫鉤,`mvn` 透過 `/etc/mavenrc` 固定用 17。
9. **plugins 鎖版 + base image 鎖版**:砍掉 volume、重 build image 都能原樣重建;升級走 `pin-plugins.sh` 流程。
10. **Cleanup 保護 ReplicaSet 引用的 image**:`rollout undo --to-revision` 任一版都要有 image;只靠「最新 5 個」在多次分支預覽後可能刪到需要的版本。

## 4. 已知邊界情況

| 情況 | 行為 |
|------|------|
| 測試失敗 | FAILURE 於 Test;不 build image、不部署;線上維持前一版 |
| 拋棄式 Redis 起不來 | 先 `docker rm -f` 同名殘留再起;10 秒內無 PONG → Test FAILURE |
| Redis 整合測試被整類跳過 | 閘門 FAILURE(報告缺檔或 `tests="0"`) |
| Deployment 不存在 / k8s 沒開 / kubeconfig 失效 | Preflight FAILURE(快速失敗),訊息提示先 `kubectl apply` 或重跑 bootstrap + 重啟容器 |
| rollout 逾時 | 診斷(describe 含 Events、logs、CrashLoop 時再補 `logs --previous`)→ undo → 等回滾 → FAILURE;undo 也失敗 → 印診斷、人工介入 |
| Deployment `replicas=0`(被人工 scale 掉) | `deploy.sh` 直接 FAILURE、不打 `:local`(否則 `rollout status` 會假成功、把從未啟動過的版本標成 local) |
| Cleanup 取不到 ReplicaSet 清單(kubectl 失敗) | 略過本次清理(不冒險刪到 `rollout undo --to-revision` 需要的 image) |
| build 在 Deploy 中途被中止 / Jenkins 重啟 | k8s 自行完成或卡在新 RS;pipeline 不補 undo,人工 `rollout status` / `rollout undo` |
| 同一 sha 重跑 | image 重 build(cache)、`set image` 無變化不觸發 rollout、SUCCESS 但沒有新 pod;要重啟用 `rollout restart` |
| 連續 push | 同 job 排隊(執行中再觸發只會有一個 queue item);一分鐘內多個 commit 合成一次 build |
| 只改 `k8s/`、`docs/`、`ci/`、根目錄 | 不觸發;改 `ci/jenkins/**` 要重建容器;改 `ci/jenkins/scripts/*.sh` 下次 build 即用新版(pipeline 每次從 main 讀) |
| 同 commit 改兩端 | 兩個 job 各自觸發、互不等待 |
| main 以 merge commit 合入 | 路徑比對會列對各 parent 的差異,可能兩個 job 都觸發(多跑一次同內容,無害);慣例單 commit 直推 |
| 首次啟動 / volume 重建 | 第一次輪詢視為有變動 → 兩個 job 各跑一次 baseline |
| `BRANCH` 非 main | 內容切分支、tag 為分支 sha、照常部署;輪詢基準不受影響;分支不存在或不合白名單 → FAILURE |
| 手動 `kubectl apply -f k8s/backend.yaml` | image 回 `:local`(= 最近成功版,同 ID)觸發一次同內容 rollout;下一次 pipeline 再改回 sha |
| HPA 在 rollout 期間擴容 | 正常行為;timeout 300s 已含餘裕 |
| `docker compose --profile ci down` | 會連 MySQL/Redis 容器一起停(volume 保留);只動 Jenkins 用 `rm -sf jenkins` |

## 5. 已知問題 / 技術債

- 建置跑在 Jenkins 控制器上(正式環境應用 agent);本機單人可接受。
- 觸發靠輪詢(每 job 每分鐘一次 `git fetch`),push 到開始 build 最壞約 60~90 秒;上雲後改 webhook。
- Node 20 已 EOL(對齊 `frontend/Dockerfile` 的 `node:20-alpine`);前端 Dockerfile 的 `npm install` 應改 `npm ci`;兩者一起升級。
- 無通知(email/Slack)、無 Jenkins 備份、無 TLS;plugin 升級需手動走 `pin-plugins.sh`。
- docker CLI 版本由 Docker apt repo 決定(build 當下最新),未鎖版;與 daemon 跨大版時有 API 協商,目前無問題。
- 同一個 sha 重跑時 `docker build` 不是位元組可重現(jar 時間戳),會產生新的 image ID 並把 sha tag 移過去,舊 ID 變成 `<none>` dangling(不影響運行中的 pod);Cleanup 只處理有 tag 的 image,dangling 需偶爾手動 `docker image prune`。
- 不做 AWS(ECR push / EKS):接點是 image 名稱前綴與 kubeconfig。

## 6. 測試現況

- 腳本層:`check-redis-suite.sh` 四種輸入(真實 `tests="0"` 報告、`tests="13"`、缺檔、`failures=1`)、`prune-images.sh` 虛擬 repo 7 個 image(保留 5 + 受保護 + `local`,冪等)——皆已實跑通過。
- 靜態:JCasC 啟動驗證、Jenkins declarative linter(兩份 Jenkinsfile 通過)、`kubectl apply --dry-run=client` RBAC、`docker compose --profile ci config`。
- 端對端(baseline、分流與即時性、測試閘門、Redis 閘門、自動回滾、不並行、清理)的執行紀錄與數字見需求書 [§10 驗收紀錄](../requirements/v0.7/jenkins-cicd.md#10-驗收紀錄)。
- 既有測試套件未改動:後端 53 顆、前端 17 顆都在 pipeline 內實跑通過。
