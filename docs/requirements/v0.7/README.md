# v0.7 需求總覽

> 這一版總共改什麼的入口。想看細項,點對應的需求書。
> 建立日期:2026-10-01

## 這版的主題

CI/CD:用 Jenkins 做「push 到 GitHub main → 自動測試 → 建 image → 滾動更新本機 k8s」的**即時上板**,前後端各一條獨立 pipeline、只有改到的那端會重新上板。把 [`ticket-system-plan.md`](../../../../ticket-system-plan.md) 原本「Jenkins 推 ECR、部署 EC2」的規劃落地成本機 Docker Desktop k8s 版本;之後搬雲端只需換 registry 前綴與 kubeconfig。

## 功能一覽

| # | 功能 | 需求書 | 狀態 | 一句話 |
|---|------|--------|------|--------|
| 1 | Jenkins CI/CD(前後端分流即時上板) | [jenkins-cicd.md](jenkins-cicd.md)([todo](jenkins-cicd-todo.md)) | ✅已完成 | Jenkins 跑在 docker compose(k8s 外)、每分鐘輪詢 GitHub main;`backend/**` / `frontend/**` 各自觸發:測試 → docker build(git sha tag)→ `kubectl set image` 滾動更新;測試不過不上板、rollout 失敗自動回滾;Redis 整合測試在 CI 內必跑不可跳過 |

開發順序:單一功能。內部順序:部署用 ServiceAccount/RBAC 與 kubeconfig → Jenkins 容器與設定(JCasC/Job DSL)→ 兩份 Jenkinsfile → 端對端與失敗路徑驗證 → 文件。

## 這版改到的主要範圍

- 新增 `ci/jenkins/`(Jenkins 自製 image、鎖版 plugins、JCasC、Job DSL、`scripts/` 清理與閘門腳本、runbook)、`backend/Jenkinsfile`、`frontend/Jenkinsfile`。
- `docker-compose.yml` 新增 `ci` profile(`jenkins` 服務 + volume `jenkins_home`;測試用 Redis 由 pipeline 自起拋棄式容器,不放 compose);預設 `docker compose up -d` 行為不變。
- 新增 `k8s/ci/jenkins-rbac.yaml`(部署用 ServiceAccount 最小權限)與 `scripts/ci-bootstrap.ps1`(產 kubeconfig、起 Jenkins)。
- 文件:`docs/deployment/ci-cd-jenkins.md` 現況 spec、`CLAUDE.md` / `k8s/README.md` 的重新部署說明。
- **不改**後端/前端應用程式碼、Dockerfile、既有 k8s manifests(`k8s/backend.yaml`、`k8s/frontend.yaml` 原封不動)。
