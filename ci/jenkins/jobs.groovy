// Job DSL(v0.7 CI/CD):Jenkins 啟動時由 JCasC 的 jobs: 區段執行,建立兩個 pipeline job。
// 改這裡要重建 Jenkins 容器(docker compose --profile ci up -d --build);UI 上對 job 設定的手動修改會被覆蓋。
//
// 設計重點(規格:docs/requirements/v0.7/jenkins-cicd.md):
// - SCM 分支固定 main(不含 BRANCH 參數):輪詢基準永遠是 main;BRANCH 由 Jenkinsfile 內自行 fetch/checkout 切內容。
// - 每分鐘輪詢 + 路徑過濾(regex 整段比對):只有 backend/.* 或 frontend/.* 有變動才觸發對應 job。
// - quietPeriod 0、同 job 不並行、保留 30 筆 build。
def repoUrl = 'https://github.com/wade50110/ticket-system.git'

def pipelines = [
    [
        name       : 'ticket-backend',
        scriptPath : 'backend/Jenkinsfile',
        region     : 'backend/.*',
        description: '後端 CI/CD:main 的 backend/** 有變動即觸發。Checkout → Preflight → Test(含 Redis 整合測試,不可跳過)→ Build Image(sha7)→ Deploy(kubectl set image + rollout,失敗自動回滾)→ Cleanup。',
    ],
    [
        name       : 'ticket-frontend',
        scriptPath : 'frontend/Jenkinsfile',
        region     : 'frontend/.*',
        description: '前端 CI/CD:main 的 frontend/** 有變動即觸發。Checkout → Preflight → Install → Test(vitest)→ Build Image(sha7)→ Deploy(失敗自動回滾)→ Cleanup。',
    ],
]

pipelines.each { p ->
    pipelineJob(p.name) {
        description(p.description)
        logRotator {
            numToKeep(30)
        }
        quietPeriod(0)
        parameters {
            stringParam('BRANCH', 'main',
                '要建置的分支。預設 main;非 main 時以該分支的內容(含 ci/jenkins/scripts)建置,Jenkinsfile 本身仍由 main 載入,不影響輪詢;會把該分支部署到本機 k8s(驗證/預覽用)。')
        }
        properties {
            disableConcurrentBuilds()
            pipelineTriggers {
                triggers {
                    pollSCM {
                        scmpoll_spec('* * * * *')
                    }
                }
            }
        }
        definition {
            cpsScm {
                lightweight(false)        // 輪詢的路徑過濾需要 workspace(git log 比對),不用 lightweight
                scriptPath(p.scriptPath)
                scm {
                    git {
                        remote {
                            url(repoUrl)
                        }
                        branch('*/main')
                        extensions {
                            pathRestriction {
                                includedRegions(p.region)
                                excludedRegions('')
                            }
                        }
                    }
                }
            }
        }
    }
}
