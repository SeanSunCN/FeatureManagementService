param(
    [string]$AdminApi = "http://localhost:8080",
    [string]$EvalService = "http://localhost:8081",
    [string]$IngestService = "http://localhost:8082",
    [string]$WorkerService = "http://localhost:8083",
    [int]$WaitSyncSeconds = 3
)

$PASSED = 0
$FAILED = 0
$SKIPPED = 0

function Write-Header {
    param([string]$Title)
    Write-Host "`n=======================================" -ForegroundColor Cyan
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host "=======================================" -ForegroundColor Cyan
}

function Write-Pass {
    param([string]$TestName)
    $script:PASSED++
    Write-Host "  [PASS] $TestName" -ForegroundColor Green
}

function Write-Fail {
    param([string]$TestName, [string]$Detail = "")
    $script:FAILED++
    Write-Host "  [FAIL] $TestName" -ForegroundColor Red
    if ($Detail) { Write-Host "         $Detail" -ForegroundColor DarkRed }
}

function Invoke-Api {
    param([string]$Method, [string]$Url, [object]$Body = $null)
    $params = @{
        Method = $Method
        Uri = $Url
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 5 -Compress)
    }
    try {
        $response = Invoke-WebRequest @params -ErrorAction Stop
        $content = $response.Content | ConvertFrom-Json
        return @{ Success = $true; StatusCode = [int]$response.StatusCode; Data = $content }
    } catch {
        $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $errorMsg = if ($_.Exception.Message) { $_.Exception.Message } else { "Unknown error" }
        return @{ Success = $false; StatusCode = $statusCode; Error = $errorMsg }
    }
}

# ============================================================
# 1. Health Check
# ============================================================
Write-Header "1/9  Health Check"

$checks = @(
    @{ Name = "Admin API (8080)"; Url = "$AdminApi/actuator/health" },
    @{ Name = "Eval Service (8081)"; Url = "$EvalService/actuator/health" },
    @{ Name = "Ingest Service (8082)"; Url = "$IngestService/actuator/health" },
    @{ Name = "Metrics Worker (8083)"; Url = "$WorkerService/actuator/health" }
)

foreach ($c in $checks) {
    $r = Invoke-Api -Method GET -Url $c.Url
    if ($r.Success -and $r.Data.status -eq "UP") { Write-Pass $c.Name }
    else { Write-Fail $c.Name -Detail "status=$($r.Data.status) err=$($r.Error)" }
}

# ============================================================
# 2. Create App
# ============================================================
Write-Header "2/9  Create App"

$appBody = @{ appId = "inttest-app"; appName = "IntTest App"; description = "auto created"; appType = "BACKEND" }
$r = Invoke-Api -Method POST -Url "$AdminApi/api/v1/apps" -Body $appBody
if ($r.Success) { Write-Pass "Create App" } else { Write-Fail "Create App" -Detail $r.Error }

$r = Invoke-Api -Method GET -Url "$AdminApi/api/v1/apps/inttest-app"
if ($r.Success -and $r.Data.data.appId -eq "inttest-app") { Write-Pass "Verify App created" }
else { Write-Fail "Verify App created" -Detail $r.Error }

# ============================================================
# 3. Create Feature Flags
# ============================================================
Write-Header "3/9  Create Feature Flags"

$flags = @(
    @{ flagKey = "flag-a"; name = "Flag A"; description = "full rollout"; enabled = $true; ruleConfig = '{"strategy":"full_rollout"}' },
    @{ flagKey = "flag-b"; name = "Flag B"; description = "50% rollout"; enabled = $true; ruleConfig = '{"strategy":"gradual_rollout","percentage":50}' },
    @{ flagKey = "flag-c"; name = "Flag C"; description = "disabled"; enabled = $false; ruleConfig = '{}' }
)

foreach ($f in $flags) {
    $r = Invoke-Api -Method POST -Url "$AdminApi/api/v1/apps/inttest-app/flags" -Body $f
    if ($r.Success) { Write-Pass "Create $($f.flagKey)" } else { Write-Fail "Create $($f.flagKey)" -Detail $r.Error }
}

$r = Invoke-Api -Method GET -Url "$AdminApi/api/v1/apps/inttest-app/flags"
$cnt = if ($r.Success) { $r.Data.data.Count } else { 0 }
if ($r.Success -and $cnt -eq 3) { Write-Pass "Verify 3 flags created" } else { Write-Fail "Verify 3 flags" -Detail "count=$cnt" }

# ============================================================
# 4. Wait for EvalService sync
# ============================================================
Write-Header "4/9  Wait for Redis Pub/Sub sync"
Write-Host "     Waiting ${WaitSyncSeconds}s..." -ForegroundColor Gray
Start-Sleep -Seconds $WaitSyncSeconds

$r = Invoke-Api -Method GET -Url "$EvalService/api/v1/eval/flags?appId=inttest-app"
$cnt = if ($r.Success) { $r.Data.data.Count } else { 0 }
if ($r.Success -and $cnt -eq 3) { Write-Pass "EvalService synced 3 flags" } else { Write-Fail "EvalService sync" -Detail "count=$cnt" }

# ============================================================
# 5. Evaluate
# ============================================================
Write-Header "5/9  Evaluate"

$r = Invoke-Api -Method POST -Url "$EvalService/api/v1/eval/evaluate" -Body @{ appId = "inttest-app"; flagKey = "flag-a"; userId = "user-001" }
$enabled = if ($r.Success) { $r.Data.data.enabled } else { $null }
if ($enabled -eq $true) { Write-Pass "flag-a = true (full rollout)" } else { Write-Fail "flag-a" -Detail "enabled=$enabled" }

$r = Invoke-Api -Method POST -Url "$EvalService/api/v1/eval/evaluate" -Body @{ appId = "inttest-app"; flagKey = "flag-c"; userId = "user-001" }
$enabled = if ($r.Success) { $r.Data.data.enabled } else { $null }
if ($enabled -eq $false) { Write-Pass "flag-c = false (disabled)" } else { Write-Fail "flag-c" -Detail "enabled=$enabled" }

$batchBody = @(
    @{ flagKey = "flag-a"; userId = "user-001" },
    @{ flagKey = "flag-b"; userId = "user-002" },
    @{ flagKey = "flag-c"; userId = "user-003" }
)
$r = Invoke-Api -Method POST -Url "$EvalService/api/v1/eval/evaluate/batch?appId=inttest-app" -Body $batchBody
$cnt = if ($r.Success) { $r.Data.data.Count } else { 0 }
if ($cnt -eq 3) { Write-Pass "Batch evaluate 3 flags" } else { Write-Fail "Batch evaluate" -Detail "count=$cnt" }

# ============================================================
# 6. Ingest metrics / audit log
# ============================================================
Write-Header "6/9  Ingest Metrics and Audit Log"

$metricsBody = @{ appId = "inttest-app"; flagHitCounts = @{ "flag-a" = 10; "flag-b" = 5; "flag-c" = 0 } }
$r = Invoke-Api -Method POST -Url "$IngestService/api/v1/ingest/metrics" -Body $metricsBody
if ($r.Success) { Write-Pass "Report metrics" } else { Write-Fail "Report metrics" -Detail $r.Error }

$auditBody = @{ appId = "inttest-app"; flagKey = "flag-a"; userId = "user-001"; enabled = $true; clientIp = "192.168.1.100"; evalCostMs = 5 }
$r = Invoke-Api -Method POST -Url "$IngestService/api/v1/ingest/audit-log" -Body $auditBody
if ($r.Success) { Write-Pass "Report audit log (single)" } else { Write-Fail "Report audit log" -Detail $r.Error }

$batchAudit = @(
    @{ appId = "inttest-app"; flagKey = "flag-a"; userId = "user-001"; enabled = $true; clientIp = "10.0.0.1"; evalCostMs = 3 },
    @{ appId = "inttest-app"; flagKey = "flag-b"; userId = "user-002"; enabled = $true; clientIp = "10.0.0.2"; evalCostMs = 7 },
    @{ appId = "inttest-app"; flagKey = "flag-c"; userId = "user-003"; enabled = $false; clientIp = "10.0.0.3"; evalCostMs = 1 }
)
$r = Invoke-Api -Method POST -Url "$IngestService/api/v1/ingest/audit-log/batch" -Body $batchAudit
if ($r.Success) { Write-Pass "Report audit log (batch 3)" } else { Write-Fail "Report audit log batch" -Detail $r.Error }

$r = Invoke-Api -Method GET -Url "$IngestService/api/v1/ingest/drop-total"
$drop = if ($r.Success) { $r.Data.data } else { -1 }
if ($drop -eq 0) { Write-Pass "Drop total = 0" } else { Write-Fail "Drop total" -Detail "drop=$drop" }

# ============================================================
# 7. Wait for ClickHouse flush
# ============================================================
Write-Header "7/9  Wait for ClickHouse flush"
Write-Host "     Waiting 12s for flush interval..." -ForegroundColor Gray
Start-Sleep -Seconds 12

$r = Invoke-Api -Method GET -Url "$WorkerService/actuator/health"
if ($r.Success -and $r.Data.status -eq "UP") { Write-Pass "Worker still UP" } else { Write-Fail "Worker" -Detail $r.Error }

# ============================================================
# 8. Toggle / Update / Delete
# ============================================================
Write-Header "8/9  Control Plane Operations"

$r = Invoke-Api -Method PATCH -Url "$AdminApi/api/v1/apps/inttest-app/flags/flag-a/enabled" -Body @{ enabled = $false }
if ($r.Success -and $r.Data.data.enabled -eq $false) { Write-Pass "Disable flag-a" } else { Write-Fail "Disable flag-a" -Detail $r.Error }

Start-Sleep -Seconds $WaitSyncSeconds

$r = Invoke-Api -Method POST -Url "$EvalService/api/v1/eval/evaluate" -Body @{ appId = "inttest-app"; flagKey = "flag-a"; userId = "user-001" }
$enabled = if ($r.Success) { $r.Data.data.enabled } else { $null }
if ($enabled -eq $false) { Write-Pass "EvalService sync: flag-a disabled" } else { Write-Fail "EvalService sync" -Detail "enabled=$enabled" }

$r = Invoke-Api -Method PATCH -Url "$AdminApi/api/v1/apps/inttest-app/flags/flag-a/enabled" -Body @{ enabled = $true }
if ($r.Success) { Write-Pass "Enable flag-a" } else { Write-Fail "Enable flag-a" -Detail $r.Error }
Start-Sleep -Seconds $WaitSyncSeconds

$updateBody = @{ flagKey = "flag-b"; name = "Flag B"; description = "30% rollout"; enabled = $true; ruleConfig = '{"strategy":"gradual_rollout","percentage":30}' }
$r = Invoke-Api -Method PUT -Url "$AdminApi/api/v1/apps/inttest-app/flags/flag-b" -Body $updateBody
if ($r.Success) { Write-Pass "Update flag-b" } else { Write-Fail "Update flag-b" -Detail $r.Error }
Start-Sleep -Seconds $WaitSyncSeconds

$r = Invoke-Api -Method DELETE -Url "$AdminApi/api/v1/apps/inttest-app/flags/flag-c"
if ($r.StatusCode -eq 200 -or $r.StatusCode -eq 204) { Write-Pass "Delete flag-c" } else { Write-Fail "Delete flag-c" -Detail $r.Error }
Start-Sleep -Seconds $WaitSyncSeconds

$r = Invoke-Api -Method GET -Url "$EvalService/api/v1/eval/flags?appId=inttest-app"
$cnt = if ($r.Success) { $r.Data.data.Count } else { 0 }
if ($cnt -eq 2) { Write-Pass "EvalService: 2 flags remain" } else { Write-Fail "EvalService count" -Detail "count=$cnt" }

$r = Invoke-Api -Method POST -Url "$AdminApi/api/v1/apps/inttest-app/flags/reload"
if ($r.Success) { Write-Pass "Reload flags" } else { Write-Fail "Reload" -Detail $r.Error }

# ============================================================
# 9. Cleanup
# ============================================================
Write-Header "9/9  Cleanup"

$r = Invoke-Api -Method DELETE -Url "$AdminApi/api/v1/apps/inttest-app"
if ($r.StatusCode -eq 200 -or $r.StatusCode -eq 204 -or ($r.Success -and $null -eq $r.Data.data)) { Write-Pass "Delete App" } else { Write-Fail "Delete App" -Detail $r.Error }

$r = Invoke-Api -Method GET -Url "$AdminApi/api/v1/apps/inttest-app"
if ($r.StatusCode -eq 404) { Write-Pass "Verify App deleted (404)" } else { Write-Fail "Verify App deleted" -Detail "HTTP=$($r.StatusCode)" }

# ============================================================
# Summary
# ============================================================
Write-Host "`n=======================================" -ForegroundColor Cyan
Write-Host "  Summary" -ForegroundColor Cyan
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  PASSED : $PASSED" -ForegroundColor Green
Write-Host "  FAILED : $FAILED" -ForegroundColor $(if ($FAILED -gt 0) { "Red" } else { "Green" })
Write-Host "  SKIPPED: $SKIPPED" -ForegroundColor Yellow
Write-Host "  TOTAL  : $($PASSED + $FAILED + $SKIPPED)" -ForegroundColor White
Write-Host ""

if ($FAILED -eq 0) {
    Write-Host "  ALL PASSED!" -ForegroundColor Green
} else {
    Write-Host "  $FAILED tests failed, check logs." -ForegroundColor Red
    exit 1
}
