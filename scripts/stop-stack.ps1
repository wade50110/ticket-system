<#
.SYNOPSIS
  一鍵關閉 ticket-system 本機環境(冪等,重跑安全)。

.DESCRIPTION
  順序:host 上的 dev server(8099 java / 5173 node)→ k8s 監控(若有)→ k8s 前後端 → Redis SAVE
        → docker compose down(Jenkins / MySQL / Redis 容器;資料 volume 保留)。
  不會:刪 volume(MySQL 資料、Redis 庫存、Jenkins build 紀錄都在)、關 Docker Desktop 的 k8s 叢集、動 metrics-server。
  -KeepInfra:只關 app / 監控 / Jenkins / dev server,留著 MySQL/Redis(開發中換模式時常用)。

  對應的啟動腳本:scripts/start-stack.ps1。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/stop-stack.ps1
  powershell -ExecutionPolicy Bypass -File scripts/stop-stack.ps1 -KeepInfra
#>
[CmdletBinding()]
param(
    [switch]$KeepInfra
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot      # ticket-system/
Set-Location $root

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Quiet($cmd) { cmd /c "$cmd >nul 2>&1"; return ($LASTEXITCODE -eq 0) }

# ---------- 1) host dev servers ----------
Step "1) host 上的 dev server(8099 後端 java / 5173 前端 node)"
$killed = 0
foreach ($port in 8099, 5173) {
    $pids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -match '^(java|node|javaw)$') {
            Write-Host "停止 $($p.ProcessName)(PID $procId,port $port)"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            $killed++
        } elseif ($p) {
            Write-Host "port $port 由 $($p.ProcessName)(PID $procId)佔用,不是 java/node,略過不殺" -ForegroundColor Yellow
        }
    }
}
if ($killed -eq 0) { Write-Host "沒有 host dev server 在跑" }

# ---------- 2) kubernetes ----------
$dockerUp = Quiet "docker info"
if (-not $dockerUp) {
    Write-Host "`nDocker Desktop 沒在跑:容器與 k8s 本來就都停了,無事可做。" -ForegroundColor Yellow
    exit 0
}

Step "2) Kubernetes:監控 + 前後端"
$ctx = (kubectl config current-context 2>&1 | Out-String).Trim()
if ($ctx -ne "docker-desktop") {
    Write-Host "kubectl context 是 $ctx(不是 docker-desktop),為避免刪到別的叢集,略過 k8s 段落;要處理請先 kubectl config use-context docker-desktop" -ForegroundColor Yellow
} elseif (Quiet "kubectl get nodes") {
    if (Quiet "kubectl get ns monitoring") {
        Write-Host "刪除監控(k8s/monitoring/)"
        kubectl delete -f k8s/monitoring/ --ignore-not-found --wait=false | Out-Null
    } else { Write-Host "監控未部署" }
    kubectl delete -f k8s/backend.yaml -f k8s/frontend.yaml --ignore-not-found --wait=false
    Write-Host "等待 pods 終止(backend 有 graceful shutdown,最多約 60s)…"
    kubectl wait --for=delete pod -l app=ticket-backend --timeout=120s 2>&1 | Out-Null
    kubectl wait --for=delete pod -l app=ticket-frontend --timeout=60s 2>&1 | Out-Null
    if (Quiet "kubectl get ns monitoring") { kubectl wait --for=delete ns/monitoring --timeout=120s 2>&1 | Out-Null }
} else {
    Write-Host "連不到 k8s(未啟用或已停),略過" -ForegroundColor Yellow
}

# ---------- 3) compose ----------
Step "3) docker compose:Jenkins / MySQL / Redis"
$running = @(docker ps --format '{{.Names}}')
$jenkinsPort = if ($env:JENKINS_HTTP_PORT) { $env:JENKINS_HTTP_PORT } else { "8088" }
if ($running -contains "ticket-jenkins") {
    # Jenkins 匿名不可讀(allowAnonymousRead: false),要帶 admin 的 Basic auth 才查得到 busyExecutors
    $pw = if ($env:JENKINS_ADMIN_PASSWORD) { $env:JENKINS_ADMIN_PASSWORD } else { "admin" }
    $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$pw"))
    $busy = ""
    try {
        $busy = (Invoke-WebRequest -Uri "http://127.0.0.1:$jenkinsPort/computer/api/json?tree=busyExecutors" `
            -Headers @{ Authorization = "Basic $auth" } -UseBasicParsing -TimeoutSec 5).Content
    } catch { Write-Host "(查不到 Jenkins 是否有 build 在跑:$($_.Exception.Message))" -ForegroundColor Yellow }
    if ($busy -match '"busyExecutors":([1-9][0-9]*)') {
        Write-Host "注意:Jenkins 還有 $($Matches[1]) 個 build 在跑,會被中止(k8s 上已下的 rollout 會自行完成或卡住,之後用 kubectl rollout status 查看)" -ForegroundColor Yellow
    }
}
if ($running -contains "ticket-redis") {
    Write-Host "Redis SAVE(庫存正源落盤)"
    docker exec ticket-redis redis-cli SAVE | Out-Null
}
if ($KeepInfra) {
    Write-Host "-KeepInfra:只移除 Jenkins 容器,MySQL/Redis 留著"
    docker compose --profile ci rm -sf jenkins | Out-Null
} else {
    docker compose --profile ci down
}

# ---------- 4) verify ----------
Step "結果"
$left = docker ps --format "{{.Names}}`t{{.Status}}" | Where-Object { $_ -match '^ticket-' }
if ($left) { Write-Host "仍在跑的 ticket-* 容器:"; $left | ForEach-Object { Write-Host "  $_" } } else { Write-Host "ticket-* 容器:無" }
if (Quiet "kubectl get nodes") {
    $pods = kubectl get pods -n default --no-headers 2>&1 | Out-String
    if ($pods.Trim() -and $pods -notmatch "No resources found") { Write-Host "default namespace pods:`n$pods" } else { Write-Host "k8s app pods:無" }
}
$listening = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 80, 3000, 3307, 6380, [int]$jenkinsPort, 8099, 5173 } |
    Select-Object -ExpandProperty LocalPort -Unique | Sort-Object
if ($listening) { Write-Host "仍在監聽的埠:$($listening -join ', ')(80/3000 由 Docker Desktop LB 持有時會延遲幾秒釋放)" } else { Write-Host "埠 80/3000/3307/6380/$jenkinsPort/8099/5173:全部釋放" }
Write-Host ""
Write-Host "資料 volume 保留:$((docker volume ls --format '{{.Name}}' | Where-Object { $_ -match 'ticket-' }) -join ', ')" -ForegroundColor Green
Write-Host "Docker Desktop 的 k8s 叢集與 metrics-server 維持運行(要省資源可在 Docker Desktop 關掉 Kubernetes)。"
Write-Host "重新啟動:powershell -ExecutionPolicy Bypass -File scripts/start-stack.ps1 [-Monitoring] [-Ci]"
