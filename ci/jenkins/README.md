# Jenkins CI/CD(本機 Docker Desktop,v0.7)

push 到 GitHub `main` → Jenkins 每分鐘輪詢發現變動 → 跑測試 → build image(git sha tag)→ `kubectl set image` 滾動更新本機 k8s;測試不過不上板、rollout 失敗自動回滾。前後端各一條 pipeline,只有改到的那端會重新上板。

設計與決策見 [`../../docs/requirements/v0.7/jenkins-cicd.md`](../../docs/requirements/v0.7/jenkins-cicd.md);現況 spec 見 [`../../docs/deployment/ci-cd-jenkins.md`](../../docs/deployment/ci-cd-jenkins.md)。

## 架構一眼看

```
GitHub main ──(每分鐘 poll,路徑過濾)──► Jenkins 容器(docker compose, profile ci, 127.0.0.1:8088)
                                           │  內建 Maven(JDK17) / Node 20 / docker CLI / kubectl
                                           │  ├─ ticket-backend  job:backend/**  → backend/Jenkinsfile
                                           │  └─ ticket-frontend job:frontend/** → frontend/Jenkinsfile
                                           ├─ /var/run/docker.sock ──► 主機 docker daemon(image 直接進 k8s 共用 store)
                                           └─ /etc/jenkins/kubeconfig(jenkins-deployer SA,最小 RBAC)──► Docker Desktop k8s
```

## 檔案

| 檔案 | 用途 |
|------|------|
| `Dockerfile` | Jenkins 控制器 image:官方 LTS(JDK 21)+ Temurin 17(給 Maven)+ Maven/Node/docker CLI/kubectl + 鎖版 plugins |
| `plugins.txt` | 鎖版 plugin 清單(`name:version`),由 `scripts/pin-plugins.sh` 產出 |
| `casc.yaml` | JCasC:admin 帳號、2 executors、關 agent 埠、Jenkins URL、載入 `jobs.groovy` |
| `jobs.groovy` | Job DSL:兩個 pipeline job(SCM 固定 main、每分鐘輪詢、路徑過濾、`BRANCH` 參數、不並行) |
| `scripts/deploy.sh` | 換版:set image → annotate → rollout status;失敗 → 診斷 → rollout undo(兩個 Jenkinsfile 共用) |
| `scripts/check-redis-suite.sh` | Redis 整合測試閘門:報告 `tests>0` 才算真的跑過 |
| `scripts/prune-images.sh` | 舊 image 清理:保留最新 5 個 + `local` + Deployment 既存 ReplicaSet 引用的 |
| `scripts/pin-plugins.sh` | 從已 build 的 image 讀出實際 plugin 版本,產出鎖版 `plugins.txt` |
| `secrets/kubeconfig` | `scripts/ci-bootstrap.ps1` 產出(不進版控) |
| `../../backend/Jenkinsfile`、`../../frontend/Jenkinsfile` | 兩條 pipeline 本體 |
| `../../k8s/ci/jenkins-rbac.yaml` | 部署用 ServiceAccount + 最小 Role |

## 前置條件

1. `docker compose up -d`(MySQL/Redis)。
2. Docker Desktop Kubernetes 已啟用(kubeadm)、metrics-server 就緒(見 [`../../k8s/README.md`](../../k8s/README.md))。
3. **app 已部署**:`kubectl apply -f k8s/backend.yaml -f k8s/frontend.yaml` 且 pods Ready。pipeline 只「換版」,不負責初次安裝(Deployment 不存在時 Preflight 直接失敗)。
4. 主機 `8088` 空閒(預設埠;`8080` 通常被其他服務佔用)。要換埠:設環境變數 `JENKINS_HTTP_PORT`。

## 啟動

```powershell
cd C:\Users\tw24301\Desktop\claudeTest\ticket-system
powershell -ExecutionPolicy Bypass -File scripts/ci-bootstrap.ps1
```

腳本做四件事:apply `k8s/ci/jenkins-rbac.yaml` → 等 token → 寫 `ci/jenkins/secrets/kubeconfig` → 跑 `kubectl auth can-i` 矩陣(前 5 項 yes、其餘 no)→ `docker compose --profile ci up -d --build`(首次 build image 約 5~10 分鐘)。

- Jenkins:**http://127.0.0.1:8088**,帳號 `admin`,密碼 = 環境變數 `JENKINS_ADMIN_PASSWORD`(未設為 `admin`)。
- 啟動後一分鐘內兩個 job 會自動輪詢並各跑一次(沒有建置紀錄時第一次輪詢視為有變動),把當下 main 部署上去。首次後端 build 要下載 Maven 依賴,約 10~15 分鐘;之後約 5 分鐘。
- 只要 RBAC/kubeconfig,不起 Jenkins:`-SkipCompose`;之後手動 `docker compose --profile ci up -d --build`。
- 單純 `docker compose up -d`(不帶 profile)**不會**起 Jenkins。

## 日常使用

| 想做什麼 | 怎麼做 |
|----------|--------|
| 上板 | push 到 `main`。改 `backend/**` 只跑後端 job、改 `frontend/**` 只跑前端 job,改其他路徑(`k8s/`、`docs/`、`ci/`)不觸發。push 完成到 build 開始最多約 60~90 秒。 |
| 看進度 / log | Jenkins UI → job → build → Pipeline Steps / Console;測試結果在 Test Result。 |
| 建某個分支(預覽、驗證) | job → **Build with Parameters** → `BRANCH=<分支名>`。只切換內容(Jenkinsfile 仍來自 main),不影響之後 main 的輪詢;會把該分支部署到本機 k8s,直到下次 main 有變動被覆蓋。 |
| 看現在跑哪一版 | `kubectl get deployment ticket-backend -o jsonpath='{.spec.template.spec.containers[0].image}'`(tag = git sha7);`kubectl rollout history deployment/ticket-backend` 的 CHANGE-CAUSE 有 job 名、build 號、sha。 |
| 手動回上一版 | `kubectl rollout undo deployment/ticket-backend`(image 仍在本機,Cleanup 會保護 ReplicaSet 引用的版本)。 |
| 重跑同一個 commit | Build Now 會重 build 但 `set image` 無變化不會有新 pod;真要重啟用 `kubectl rollout restart deployment/ticket-backend`。 |
| 停 Jenkins | `docker compose --profile ci stop jenkins`;只移除 Jenkins 容器 `docker compose --profile ci rm -sf jenkins`。注意 `docker compose --profile ci down` 會把 **MySQL/Redis 容器也一起停掉**(資料 volume 保留;**千萬不要加 `-v`**,會連資料一起刪)。 |
| 砍掉 Jenkins 重來 | `docker compose --profile ci rm -sf jenkins`,再 `docker volume rm ticket-system_ticket-jenkins-home`,重跑 bootstrap(或 `docker compose --profile ci up -d --build`)。設定全在 image 裡,會原樣重建(含兩個 job)。 |

## 改設定

- 改 `casc.yaml` / `jobs.groovy` / `Dockerfile` / `plugins.txt` → `docker compose --profile ci up -d --build`(重建容器才生效;UI 上手動改的會被覆蓋)。
- 升級 plugin:把 `plugins.txt` 的版本改掉(或暫時去掉版本),build 一次,再用 `bash ci/jenkins/scripts/pin-plugins.sh` 把實際安裝版本寫回 `plugins.txt`,再 build 一次確認可重現。
- 改 `backend/Jenkinsfile`、`frontend/Jenkinsfile`、`ci/jenkins/scripts/*.sh`:push 即生效(main 的 build 用 main 的版本;以 `BRANCH` 建分支時 scripts 跟著該分支走,Jenkinsfile 本身永遠由 main 載入)。注意 `scripts/` 在 `ci/` 下不會觸發 build,但下一次 build 就會用新版。

## 手動備援(Jenkins 掛掉時怎麼上板)

pipeline 接手後 Deployment 的 image 是 `ticket-backend:<sha7>`,**單純 `rollout restart` 不會換到你新 build 的版本**。手動流程:

```powershell
docker build -t ticket-backend:local ./backend
kubectl set image deployment/ticket-backend ticket-backend=ticket-backend:local
# 若 Deployment 當下已經是 :local(例如剛 kubectl apply 過),才需要:
kubectl rollout restart deployment/ticket-backend
```

前端同理(`ticket-frontend:local`、container 名 `nginx`)。已知副作用:`kubectl apply -f k8s/backend.yaml` 會把 image 改回 `:local`(= 最近一次成功上板的 image,pipeline 成功後會同步打這個 tag),觸發一次同內容的 rollout,`rollout history` 多一筆;下一次 pipeline 再改回 sha。

## 疑難排解

| 症狀 | 原因 / 處理 |
|------|-------------|
| push 了但 job 沒跑 | (1) 改的路徑不在 `backend/**` / `frontend/**`;(2) 還沒到下一分鐘;(3) job → **Git Polling Log** 看輪詢結果;(4) main 用 merge commit 合入時路徑比對可能異常(本專案慣例單 commit 直推)。 |
| `Preflight`:docker 不可用 | compose 的 `group_add: ["0"]` 與 socket 掛載有沒有在;`docker compose --profile ci exec jenkins docker ps`。 |
| `Preflight`:沒有 Deployment / 連不到叢集 | app 沒部署 → `kubectl apply -f k8s/...`;k8s 沒開;kubeconfig 失效 → 重跑 `scripts/ci-bootstrap.ps1 -SkipCompose` 後 `docker compose --profile ci restart jenkins`(bind 單檔要重啟容器才讀到新內容)。 |
| `Test`:CI Redis 啟動失敗 | pipeline 自己起 `ci-redis-backend` 容器加入 Jenkins 網路命名空間;看 build log 的 `docker logs` 段;殘留容器會被自動 `rm -f`。 |
| `Test`:Redis 閘門失敗 `tests=0` | `QuotaRedisRepositoryRedisTest` 連不到 `localhost:6380` 被整類跳過(surefire 寫 `tests="0" skipped="0"`)。確認 CI Redis 有起、沒有別的程序佔 6380。 |
| `Deploy` 失敗、自動回滾 | build log 有 `describe`(含 Events)與 `logs`;常見是啟動例外、readiness 不過(`/api/health`)、連不到 DB/Redis。回滾後線上是前一版,修好再 push。 |
| build 中途被中止 / Jenkins 重啟在 Deploy 中 | k8s 會自己把已下的 rollout 跑完或卡住;`kubectl rollout status deployment/ticket-backend` 查,必要時 `kubectl rollout undo`。 |
| 8088 被佔 | `JENKINS_HTTP_PORT=8089` 再 `docker compose --profile ci up -d`(綁埠與 Jenkins URL 都讀這個變數)。 |
| 磁碟變大 | Cleanup 只保留最新 5 個 sha image(+ `local` + RS 引用的);`docker system df` 查;手動 `bash ci/jenkins/scripts/prune-images.sh ticket-backend 5`。 |

## 安全注意

- Jenkins 握有 docker socket = 主機 root 等級權限,**只綁 127.0.0.1**,不要改成 `0.0.0.0`,不要開到公司 LAN。
- 開發預設密碼 `admin/admin`,要改就設 `JENKINS_ADMIN_PASSWORD` 環境變數再 `up`。
- 部署身分是 `jenkins-deployer` SA:只能改 default namespace 的 Deployment image、看 pods/RS/events/logs;不能 create/delete、讀不到 Secret/ConfigMap、碰不到 monitoring。
- 撤銷 Jenkins 手上的 k8s token:`kubectl delete secret jenkins-deployer-token`(secret 型長效 token 不會過期,刪 Secret 才會立即失效),之後重跑 `scripts/ci-bootstrap.ps1 -SkipCompose` 再 `docker compose --profile ci restart jenkins`。
- 能 push 到 `main` 的人等於能在這台機器上跑任意腳本(Jenkinsfile 來自 repo)。
