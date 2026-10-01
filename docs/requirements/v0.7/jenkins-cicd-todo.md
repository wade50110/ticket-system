# Todo:Jenkins CI/CD(前後端分流即時上板)

> 對應需求書:jenkins-cicd.md
> 完成定義:實作完成 + 對應驗收條件實際執行且通過(結果記入需求書 §10 驗收紀錄)
> 建立日期:2026-10-01

- [x] 1. `k8s/ci/jenkins-rbac.yaml`:SA / Role(含 events)/ RoleBinding / 長效 token Secret;`--dry-run=client` 通過(AC-1)
- [x] 2. `scripts/ci-bootstrap.ps1`:apply → 等 token → 產 `ci/jenkins/secrets/kubeconfig`(UTF-8 無 BOM、LF)→ can-i 矩陣;`.gitignore` 排除 secrets(AC-1、AC-14)
- [x] 3. `ci/jenkins/Dockerfile` + `plugins.txt`(鎖版,含 junit):2.580.1-lts-jdk21 + temurin-17 + Maven 3.9 + Node 20 + docker CLI/buildx + kubectl 1.34(AC-4)
- [x] 4. `ci/jenkins/casc.yaml` + `jobs.groovy`:admin、executors 2、agent 埠關閉、location 讀 `JENKINS_HTTP_PORT`;兩個 job SCM 固定 main、每分鐘輪詢、路徑過濾、BRANCH 參數、quietPeriod 0、不並行、buildDiscarder(AC-2)
- [x] 5. `docker-compose.yml` 加 `ci` profile:`jenkins`(127.0.0.1 綁埠、group_add 0、socket、kubeconfig 唯讀、TZ、mem_limit、volume)(AC-2、AC-3)
- [x] 6. 腳本:`ci/jenkins/scripts/check-redis-suite.sh`(三種輸入測試,AC-10a)、`prune-images.sh`(虛擬 repo 測試,AC-12a)、`deploy.sh`(set image / annotate / rollout status / 失敗診斷+undo)
- [x] 7. `backend/Jenkinsfile`:Checkout(BRANCH 切換、sha7)→ Preflight → Test(拋棄式 Redis、mvn test、junit、Redis 閘門)→ Build Image → Deploy(成功後打 :local)→ Cleanup;declarative linter 通過(AC-6)
- [x] 8. `frontend/Jenkinsfile`:Checkout → Preflight → Install → Test(vitest junit 到 build context 外)→ Build Image → Deploy → Cleanup;linter 通過(AC-7)
- [x] 9. 環境就緒:compose MySQL/Redis、k8s app apply、metrics-server;bootstrap 跑過;Jenkins 起來,AC-1~AC-4 逐條驗證
- [x] 10. push CI 檔到 main,Jenkins 乾淨啟動 → 兩個 job 自動 baseline 並 SUCCESS(AC-5、AC-6、AC-7)
- [x] 11. 失敗路徑:測試閘門(AC-9 後端+前端)、Redis 閘門殘留容器(AC-10c)、自動回滾(AC-11 後端+前端),一次性分支驗完刪除
- [x] 12. AC-12b Cleanup log、AC-13 不並行
- [x] 13. code review(Stop hook)並修正;若動到 pipeline 檔則 push 後重驗相關 AC
- [x] 14. AC-8 即時性與分流(最後做):frontend 小改動 ≤ 90 s 觸發且可見、backend-only 只觸發後端、docs-only 不觸發;同時證明 BRANCH 用過後 main 輪詢仍有效
- [x] 15. 文件:`ci/jenkins/README.md` runbook、`docs/deployment/ci-cd-jenkins.md` 現況 spec、`CLAUDE.md`、`k8s/README.md`(手動備援 + apply 副作用)、`docs/deployment/architecture.md` 補 CI 一筆、記憶體檔
- [x] 16. 需求書 §10 驗收紀錄填寫、狀態改「已完成」+ 同步 v0.7 README 與頂層需求書目錄;AC-15 以 `git diff --stat` 確認測試檔未動;最後 commit + push,`git status` 乾淨(AC-14)
