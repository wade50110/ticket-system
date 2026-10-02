<#
.SYNOPSIS
  一鍵啟動 ticket-system 本機環境(冪等,重跑安全)。

.DESCRIPTION
  -Mode k8s(預設):docker compose 起 MySQL/Redis → 確認 Docker Desktop k8s 與 metrics-server → 確認(必要時 build)image
                   → kubectl apply 前後端 → 等 pods Ready → 健康檢查 http://localhost/api/health
  -Mode infra     :只起 MySQL/Redis(方式一「前後端在 host 本機跑」時用;後端/前端另外以 ..\.claude\run-backend.cmd、npm run dev,
                   或 Claude 的 preview(.claude/launch.json)啟動);此模式忽略 -Monitoring / -Ci / -Build
  -Monitoring     :額外 apply k8s/monitoring/(Prometheus + Grafana → http://localhost:3000,admin/admin)
  -Ci             :額外起 Jenkins(docker compose profile ci → http://127.0.0.1:8088,admin/admin);
                   ci/jenkins/secrets/kubeconfig 不存在時自動跑 scripts/ci-bootstrap.ps1 -SkipCompose
  -Build          :強制重 build ticket-backend:local / ticket-frontend:local(預設只在 image 不存在時 build)

  對應的關閉腳本:scripts/stop-stack.ps1。不會刪任何資料。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1
  powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1 -Monitoring -Ci
  powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1 -Mode infra
#>
[CmdletBinding()]
param(
    [ValidateSet("k8s", "infra")]
    [string]$Mode = "k8s",
    [switch]$Monitoring,
    [switch]$Ci,
    [switch]$Build
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
# native 指令(docker/kubectl)靠 $LASTEXITCODE 判斷,不用 Stop(PS 5.1 會把 stderr 包成 ErrorRecord)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot      # ticket-system/
Set-Location $root
$startedAt = Get-Date

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "[start-stack] 失敗:$msg" -ForegroundColor Red; exit 1 }
function Quiet($cmd) { cmd /c "$cmd >nul 2>&1"; return ($LASTEXITCODE -eq 0) }   # 吞掉 stderr 只看 exit code
function Wait-Until([scriptblock]$Test, [int]$TimeoutSec, [string]$What) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Test) { return $true }
        Start-Sleep -Seconds 2
    }
    Write-Host "[start-stack] 等待逾時($TimeoutSec s):$What" -ForegroundColor Yellow
    return $false
}
function Http200($url) {
    try { $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5; return ($r.StatusCode -eq 200) } catch { return $false }
}

# ---------- 0) Docker ----------
Step "0) Docker Desktop"
if (-not (Quiet "docker info")) { Fail "Docker Desktop 沒在跑(docker info 失敗)。請先開 Docker Desktop。" }

# ---------- 1) MySQL / Redis ----------
Step "1) MySQL(3307)/ Redis(6380):docker compose up -d"
docker compose up -d mysql redis
if ($LASTEXITCODE -ne 0) { Fail "docker compose up 失敗" }
$redisOk = Wait-Until { (cmd /c "docker exec ticket-redis redis-cli ping 2>nul") -eq "PONG" } 60 "Redis PONG"
# -h127.0.0.1 強制走 TCP:全新 volume 首次初始化時的臨時 server 只開 socket(--skip-networking),走 socket 會提早回 OK
$mysqlOk = Wait-Until { Quiet "docker exec ticket-mysql mysqladmin ping -h127.0.0.1 -uroot -proot --silent" } 120 "MySQL mysqladmin ping"
if (-not ($redisOk -and $mysqlOk)) { Fail "MySQL/Redis 未就緒:docker compose logs mysql redis" }
Write-Host "MySQL / Redis 就緒"

if ($Mode -eq "infra") {
    if ($Monitoring -or $Ci -or $Build) { Write-Host "[start-stack] -Mode infra 只起 MySQL/Redis,忽略 -Monitoring / -Ci / -Build(這些屬於 k8s 模式)" -ForegroundColor Yellow }
    Write-Host ""
    Write-Host "完成(infra 模式):MySQL localhost:3307(root/root)、Redis localhost:6380。" -ForegroundColor Green
    Write-Host "後端:..\.claude\run-backend.cmd(或 mvn spring-boot:run,port 8099);前端:cd frontend; npm run dev(port 5173)。"
    Write-Host "關閉:scripts\stop-stack.ps1(加 -KeepInfra 可留著 MySQL/Redis)。"
    exit 0
}

# ---------- 2) Kubernetes ----------
Step "2) Kubernetes(Docker Desktop,context docker-desktop)"
$ctx = (kubectl config current-context 2>&1 | Out-String).Trim()
if ($ctx -ne "docker-desktop") {
    Write-Host "目前 context=$ctx,切換到 docker-desktop"
    kubectl config use-context docker-desktop | Out-Null
    $ctx = (kubectl config current-context 2>&1 | Out-String).Trim()
    # 切不過去就停,絕不能把 app apply 到別的叢集
    if ($ctx -ne "docker-desktop") { Fail "kubectl context 不是 docker-desktop(目前:$ctx),不敢對別的叢集動手;請先 kubectl config use-context docker-desktop" }
}
if (-not (Quiet "kubectl get nodes")) {
    Fail "連不到 Kubernetes。Docker Desktop → Settings → Kubernetes → Enable Kubernetes(provisioning 選 Kubeadm)→ Apply,等它變綠再重跑。"
}
# 不用 jsonpath filter(含雙引號的參數經 PowerShell 傳給 native 指令會被吃掉),直接看 STATUS 欄
$nodeStatus = (kubectl get nodes --no-headers | Out-String).Trim()
if ($nodeStatus -notmatch '\sReady\s') { Fail "k8s node 不是 Ready(目前:$nodeStatus),稍等再試" }

# metrics-server(HPA 的 CPU 指標來源);本機 kubelet 是自簽憑證,必須有 --kubelet-insecure-tls 才抓得到指標
# 注意:PowerShell 把含雙引號的參數交給 native 指令時會把 " 吃掉,JSON patch 要先 -replace 成 \"
$patchJson = '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
$needPatch = $false
if (-not (Quiet "kubectl -n kube-system get deployment metrics-server")) {
    Write-Host "metrics-server 不存在,安裝"
    kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
    if ($LASTEXITCODE -ne 0) { Write-Host "[start-stack] metrics-server 安裝失敗(離線?),HPA 會顯示 <unknown>,其餘不受影響" -ForegroundColor Yellow }
    else { $needPatch = $true }
} else {
    $args = (kubectl -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' | Out-String)
    if ($args -notmatch 'kubelet-insecure-tls') { Write-Host "metrics-server 已存在但缺 --kubelet-insecure-tls,補 patch"; $needPatch = $true }
    else { Write-Host "metrics-server 已存在" }
}
if ($needPatch) {
    kubectl patch deployment metrics-server -n kube-system --type=json -p ($patchJson -replace '"', '\"') | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "[start-stack] metrics-server patch 失敗,HPA 會顯示 <unknown>(手動:見 k8s/README.md 第 3 步)" -ForegroundColor Yellow }
}

# ---------- 3) images ----------
Step "3) image(ticket-backend:local / ticket-frontend:local)"
foreach ($pair in @(@("ticket-backend", "backend"), @("ticket-frontend", "frontend"))) {
    $img = "$($pair[0]):local"; $dir = $pair[1]
    if ($Build -or -not (Quiet "docker image inspect $img")) {
        Write-Host "build $img(首次約 3~10 分鐘)…"
        docker build -t $img "./$dir"
        if ($LASTEXITCODE -ne 0) { Fail "docker build $img 失敗" }
    } else { Write-Host "$img 已存在(要重 build 加 -Build;正式上板走 Jenkins push main)" }
}

# ---------- 4) apply app ----------
Step "4) kubectl apply 前後端"
kubectl apply -f k8s/backend.yaml -f k8s/frontend.yaml
if ($LASTEXITCODE -ne 0) { Fail "kubectl apply 失敗" }
kubectl rollout status deployment/ticket-backend --timeout=300s
if ($LASTEXITCODE -ne 0) { Write-Host "[start-stack] backend rollout 未在 300s 內就緒:kubectl get pods; kubectl logs -l app=ticket-backend(常見:pod 內解析不到 host.docker.internal,見 k8s/README.md 疑難排解)" -ForegroundColor Red; exit 1 }
kubectl rollout status deployment/ticket-frontend --timeout=120s
if ($LASTEXITCODE -ne 0) { Write-Host "[start-stack] frontend rollout 未就緒:kubectl get pods" -ForegroundColor Red; exit 1 }
$appOk = Wait-Until { Http200 "http://localhost/api/health" } 60 "http://localhost/api/health 200"

# ---------- 5) monitoring(選用) ----------
if ($Monitoring) {
    Step "5) 監控:kubectl apply -f k8s/monitoring/"
    $grafanaOk = $false
    kubectl apply -f k8s/monitoring/
    if ($LASTEXITCODE -ne 0) { Write-Host "[start-stack] monitoring apply 失敗" -ForegroundColor Red }
    else {
        kubectl -n monitoring rollout status deployment/prometheus --timeout=180s | Out-Null
        kubectl -n monitoring rollout status deployment/grafana --timeout=180s | Out-Null
        $grafanaOk = Wait-Until { Http200 "http://localhost:3000/api/health" } 90 "Grafana /api/health 200"
    }
}

# ---------- 6) Jenkins(選用) ----------
if ($Ci) {
    Step "6) Jenkins:docker compose --profile ci up -d"
    if (-not (Test-Path "ci/jenkins/secrets/kubeconfig" -PathType Leaf)) {
        Write-Host "kubeconfig 不存在,先跑 scripts/ci-bootstrap.ps1 -SkipCompose(建 jenkins-deployer SA + kubeconfig)"
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "scripts\ci-bootstrap.ps1") -SkipCompose
        if ($LASTEXITCODE -ne 0) { Fail "ci-bootstrap 失敗" }
    }
    if (Quiet "docker image inspect ticket-jenkins:local") { docker compose --profile ci up -d jenkins }
    else { Write-Host "ticket-jenkins:local 不存在,build(約 5~10 分鐘)…"; docker compose --profile ci up -d --build jenkins }
    if ($LASTEXITCODE -ne 0) { Fail "Jenkins 啟動失敗(8088 被佔?設 JENKINS_HTTP_PORT)" }
    $port = if ($env:JENKINS_HTTP_PORT) { $env:JENKINS_HTTP_PORT } else { "8088" }
    $jenkinsOk = Wait-Until { Http200 "http://127.0.0.1:$port/login" } 240 "Jenkins /login 200"
}

# ---------- summary ----------
$elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
Step "完成(${elapsed}s)"
kubectl get pods,hpa --no-headers
Write-Host ""
Write-Host ("前端 + API      http://localhost        {0}" -f $(if ($appOk) { "OK" } else { "尚未回 200,稍後再試" })) -ForegroundColor Green
Write-Host  "MySQL / Redis   localhost:3307 / localhost:6380"
if ($Monitoring) { Write-Host ("Grafana         http://localhost:3000  admin/admin  {0}" -f $(if ($grafanaOk) { "OK" } else { "provision 中,稍等" })) }
if ($Ci) {
    Write-Host ("Jenkins         http://127.0.0.1:$port  admin/admin  {0}" -f $(if ($jenkinsOk) { "OK" } else { "啟動中" }))
    Write-Host "                (若 GitHub main 自上次 build 後有新 commit,Jenkins 一分鐘內會自動 build 並上板)"
}
Write-Host "關閉全部:powershell -ExecutionPolicy Bypass -File scripts/stop-stack.ps1"
