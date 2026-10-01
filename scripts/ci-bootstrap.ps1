<#
.SYNOPSIS
  v0.7 CI/CD bootstrap:建立 Jenkins 部署用 ServiceAccount(最小 RBAC)、產出 kubeconfig、啟動 Jenkins。

.DESCRIPTION
  1) kubectl apply -f k8s/ci/jenkins-rbac.yaml
  2) 等待長效 token Secret(jenkins-deployer-token)由 controller 填入 token
  3) 以 Secret 的 ca.crt + token 寫出 ci/jenkins/secrets/kubeconfig(UTF-8 無 BOM、LF;已 gitignore)
  4) 用該 kubeconfig 跑 kubectl auth can-i 矩陣(對應需求書 AC-1)
  5) 除非 -SkipCompose,執行 docker compose --profile ci up -d --build 啟動 Jenkins(127.0.0.1:8088)

  重跑是安全的(apply 冪等、kubeconfig 覆寫)。重新產生 kubeconfig 後要重啟 Jenkins 容器才會讀到新檔。

.PARAMETER Server
  Jenkins 容器內連 API server 的位址。預設 https://kubernetes.docker.internal:6443(Docker Desktop 的容器內可解析,實測可通)。

.PARAMETER SkipCompose
  只做 RBAC + kubeconfig,不啟動 Jenkins。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/ci-bootstrap.ps1
#>
[CmdletBinding()]
param(
    [string]$Server = "https://kubernetes.docker.internal:6443",
    [switch]$SkipCompose
)

# 不用 Stop:native 指令(kubectl/docker)的 stderr 在 PS 5.1 會被包成 ErrorRecord,改以 $LASTEXITCODE 判斷。
# 讓中文訊息在 PowerShell 主控台正確顯示(檔案本身為 UTF-8 with BOM)
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot      # ticket-system/
Set-Location $root

function Fail($msg) { Write-Host "[bootstrap] 失敗:$msg" -ForegroundColor Red; exit 1 }

Write-Host "== 1) apply RBAC(k8s/ci/jenkins-rbac.yaml)"
kubectl apply -f k8s/ci/jenkins-rbac.yaml
if ($LASTEXITCODE -ne 0) { Fail "kubectl apply 失敗(Docker Desktop k8s 有開嗎?context 是 docker-desktop 嗎?)" }

Write-Host "== 2) 等待 token Secret 填入"
$tokenB64 = ""
for ($i = 0; $i -lt 30; $i++) {
    $tokenB64 = kubectl get secret jenkins-deployer-token -o jsonpath='{.data.token}'
    if ($LASTEXITCODE -eq 0 -and $tokenB64) { break }
    Start-Sleep -Seconds 1
}
if (-not $tokenB64) { Fail "token 30 秒內未產生(kubectl describe secret jenkins-deployer-token 查看)" }
$caB64 = kubectl get secret jenkins-deployer-token -o jsonpath='{.data.ca\.crt}'
if ($LASTEXITCODE -ne 0 -or -not $caB64) { Fail "讀不到 ca.crt" }
$token = [System.Text.Encoding]::ASCII.GetString([Convert]::FromBase64String($tokenB64))

Write-Host "== 3) 寫 kubeconfig → ci/jenkins/secrets/kubeconfig"
$secretsDir = Join-Path $root "ci\jenkins\secrets"
New-Item -ItemType Directory -Force -Path $secretsDir | Out-Null
$kubeconfigPath = Join-Path $secretsDir "kubeconfig"
$lines = @(
    "# 由 scripts/ci-bootstrap.ps1 產生;Jenkins 容器以 KUBECONFIG=/etc/jenkins/kubeconfig 唯讀掛載。不進版控。",
    "apiVersion: v1",
    "kind: Config",
    "clusters:",
    "- name: docker-desktop",
    "  cluster:",
    "    server: $Server",
    "    certificate-authority-data: $caB64",
    "users:",
    "- name: jenkins-deployer",
    "  user:",
    "    token: $token",
    "contexts:",
    "- name: jenkins-deployer@docker-desktop",
    "  context:",
    "    cluster: docker-desktop",
    "    user: jenkins-deployer",
    "    namespace: default",
    "current-context: jenkins-deployer@docker-desktop"
)
# UTF-8 無 BOM + LF(kubectl 讀含 BOM 的 YAML 會失敗)
[System.IO.File]::WriteAllText($kubeconfigPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "== 4) 以 jenkins-deployer 身分檢查權限(預期:前 5 項 yes,其餘 no)"
$checks = @(
    @("patch",  "deployments",            @()),
    @("get",    "pods",                   @()),
    @("list",   "replicasets",            @()),
    @("get",    "events",                 @()),
    @("get",    "pods",                   @("--subresource=log")),
    @("delete", "deployments",            @()),
    @("create", "deployments",            @()),
    @("get",    "secrets",                @()),
    @("get",    "configmaps",             @()),
    @("get",    "pods",                   @("-n", "monitoring"))
)
$unexpected = 0
for ($i = 0; $i -lt $checks.Count; $i++) {
    $c = $checks[$i]
    $args = @("--kubeconfig", $kubeconfigPath, "auth", "can-i", $c[0], $c[1]) + $c[2]
    $answer = (& kubectl @args | Out-String).Trim()
    $expect = if ($i -lt 5) { "yes" } else { "no" }
    $mark = if ($answer -eq $expect) { "OK " } else { "!! "; $unexpected++ }
    Write-Host ("  {0}{1,-7}{2,-13}{3,-22}{4}" -f $mark, $c[0], $c[1], ($c[2] -join " "), $answer)
}
if ($unexpected -gt 0) { Fail "can-i 結果與預期不符($unexpected 項),請檢查 k8s/ci/jenkins-rbac.yaml" }

if ($SkipCompose) {
    Write-Host "== 5) 略過 compose(-SkipCompose)。之後執行:docker compose --profile ci up -d --build"
    exit 0
}

Write-Host "== 5) 啟動 Jenkins:docker compose --profile ci up -d --build(首次要 build image,約 5~10 分鐘)"
docker compose --profile ci up -d --build
if ($LASTEXITCODE -ne 0) { Fail "docker compose 失敗" }

$port = if ($env:JENKINS_HTTP_PORT) { $env:JENKINS_HTTP_PORT } else { "8088" }
Write-Host ""
Write-Host "Jenkins:http://127.0.0.1:$port(帳號 admin;密碼為環境變數 JENKINS_ADMIN_PASSWORD,未設為 admin)"
Write-Host "狀態:docker compose --profile ci logs -f jenkins;job 會在啟動後一分鐘內自動輪詢 GitHub main 並建置一次(baseline)。"
Write-Host "前提:k8s 上要先有 app(kubectl apply -f k8s/backend.yaml -f k8s/frontend.yaml),否則 Preflight 會失敗。"
