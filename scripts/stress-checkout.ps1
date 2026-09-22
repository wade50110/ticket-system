# 並發壓測：驗證搶票不超賣
#
# 流程：N 個顧客帳號各自加 1 張票券到購物車，然後同時 POST /api/checkout。
# 預期：stock=10、N=50 時，10 個 200、40 個 409（庫存不足）、Redis stock=0、DB stock 最終=0。
#
# 用法：
#   .\stress-checkout.ps1 -TicketId 1 -N 50 -BaseUrl http://localhost:8095
#
# 前置：先用 admin 把 ticket stock 設成想測試的值（例：10）。

param(
    [Parameter(Mandatory = $true)][int]$TicketId,
    [int]$N = 50,
    [string]$BaseUrl = "http://localhost:8095",
    [string]$UsernamePrefix = "stress",
    [string]$Password = "Aa123456!"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param([string]$Method, [string]$Url, [hashtable]$Headers, $Body)
    $params = @{
        Method      = $Method
        Uri         = $Url
        ContentType = "application/json"
    }
    if ($Headers) { $params.Headers = $Headers }
    if ($Body)    { $params.Body = ($Body | ConvertTo-Json -Compress) }
    return Invoke-RestMethod @params
}

function Ensure-User([string]$username) {
    try {
        Invoke-Json -Method POST -Url "$BaseUrl/api/auth/register" -Body @{ username = $username; password = $Password; name = $username } | Out-Null
    } catch {
        # 已存在就忽略
    }
    $login = Invoke-Json -Method POST -Url "$BaseUrl/api/auth/login" -Body @{ username = $username; password = $Password }
    return $login.accessToken
}

Write-Host "==> Preparing $N users and tokens..."
$tokens = @()
for ($i = 1; $i -le $N; $i++) {
    $u = "$UsernamePrefix$i"
    $tokens += Ensure-User $u
}

Write-Host "==> Each user adds ticketId=$TicketId qty=1 to cart..."
foreach ($t in $tokens) {
    try {
        Invoke-Json -Method POST -Url "$BaseUrl/api/cart" `
            -Headers @{ Authorization = "Bearer $t" } `
            -Body @{ ticketId = $TicketId; quantity = 1 } | Out-Null
    } catch {
        Write-Warning "add-to-cart failed: $($_.Exception.Message)"
    }
}

Write-Host "==> Firing $N concurrent /api/checkout requests..."
$start = Get-Date

$jobs = @()
foreach ($t in $tokens) {
    $jobs += Start-ThreadJob -ScriptBlock {
        param($Url, $Token)
        $h = @{ Authorization = "Bearer $Token" }
        try {
            $r = Invoke-WebRequest -Method POST -Uri $Url -Headers $h -ContentType "application/json" -UseBasicParsing
            return [pscustomobject]@{ status = $r.StatusCode; body = $r.Content }
        } catch {
            $resp = $_.Exception.Response
            $code = if ($resp) { [int]$resp.StatusCode } else { 0 }
            $body = ""
            if ($resp) {
                try { $body = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch {}
            }
            return [pscustomobject]@{ status = $code; body = $body }
        }
    } -ArgumentList "$BaseUrl/api/checkout", $t
}

$results = $jobs | Receive-Job -Wait -AutoRemoveJob
$elapsed = (Get-Date) - $start

$success     = ($results | Where-Object { $_.status -eq 200 }).Count
$conflict    = ($results | Where-Object { $_.status -eq 409 }).Count
$forbid      = ($results | Where-Object { $_.status -eq 403 }).Count
$other       = $N - $success - $conflict - $forbid

Write-Host ""
Write-Host "================ Result ================"
Write-Host ("Total requests : {0}" -f $N)
Write-Host ("Success (200)  : {0}" -f $success)
Write-Host ("Conflict (409) : {0}" -f $conflict)
Write-Host ("Forbidden(403) : {0}" -f $forbid)
Write-Host ("Other          : {0}" -f $other)
Write-Host ("Elapsed        : {0:N2}s" -f $elapsed.TotalSeconds)
Write-Host "========================================"
Write-Host ""
Write-Host "請接著驗證："
Write-Host "  - Redis CLI : GET ticket:stock:$TicketId  (應為 0 或剩餘=stock-success)"
Write-Host "  - MySQL     : SELECT stock FROM tickets WHERE id=$TicketId; (數秒內應同步)"
Write-Host "  - orders 表 : SELECT COUNT(*) FROM orders WHERE status='PAID'; (應 = success 數)"
