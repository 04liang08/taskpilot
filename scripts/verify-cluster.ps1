<#
.SYNOPSIS
    TaskPilot 集群验收：两个 scheduler 实例 + 一个 executor，验证「同一任务只触发一次」与故障转移。

.DESCRIPTION
    流程：
      1. 启动 executor 与两个 scheduler（共用同一个 MySQL 与 Redis）；
      2. 等待选出 leader；
      3. 建一个每 N 秒触发的 cron 任务，统计触发次数：必须接近理论值，不能翻倍；
      4. 强杀 leader，等待另一个实例接管（记录接管耗时）；
      5. 校验接管后继续触发，且 fencing token 递增。

    前置：MySQL 已执行 deploy/sql/01-init.sql，Redis 可用（6379）。
    用法：powershell -ExecutionPolicy Bypass -File scripts/verify-cluster.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/verify-cluster.ps1
    powershell -ExecutionPolicy Bypass -File scripts/verify-cluster.ps1 -CronSeconds 5 -WaitSeconds 20
#>
param(
    [string]$TaskName = "verify-cluster-cron",
    [int]$CronSeconds = 10,
    [int]$WaitSeconds = 35,
    [int]$SchedulerPortA = 8080,
    [int]$SchedulerPortB = 8090,
    [int]$ExecutorPort = 8081,
    [int]$FailoverTimeoutSeconds = 90,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.tmp/verify-cluster'
$schedulerJar = Join-Path $root 'taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar'
$executorJar = Join-Path $root 'taskpilot-executor/target/taskpilot-executor-1.0.0-SNAPSHOT.jar'
$baseA = "http://127.0.0.1:$SchedulerPortA/api/v1"
$baseB = "http://127.0.0.1:$SchedulerPortB/api/v1"

$processes = @()
$failures = @()
$taskId = $null

# 优先用真实 JDK（PATH 上的 java 可能是转发程序，pid 对不上）；没有 JAVA_HOME 才退回 PATH。
$script:JavaExe = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    "$env:JAVA_HOME\bin\java.exe"
} else {
    'java'
}

function Write-Step($message) {
    Write-Host "`n=== $message ===" -ForegroundColor Cyan
}

function Write-Ok($message) {
    Write-Host "  [OK]   $message" -ForegroundColor Green
}

function Write-Fail($message) {
    Write-Host "  [FAIL] $message" -ForegroundColor Red
    $script:failures += $message
}

function Assert-Condition($condition, $message) {
    if ($condition) { Write-Ok $message } else { Write-Fail $message }
}

function Get-Json($url) {
    try {
        return Invoke-RestMethod -Uri $url -TimeoutSec 10
    } catch {
        return $null
    }
}

function Wait-ForPort($port, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($conn) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-ForHealth($healthUrl, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $health = Get-Json $healthUrl
        if ($health -and $health.status -eq 'UP') { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Assert-PortFree($port, $label) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        throw "端口 $port 已被占用（pid=$($conn.OwningProcess)）。请先停掉占用进程再跑验收脚本。"
    }
}

# 按端口找真正在监听的进程再停。
# 注意：PATH 上的 java 可能是 Oracle javapath 的转发程序（会再 fork 一个真正的 JVM），
# Start-Process 返回的 pid 并不是 JVM 的 pid，所以一律按端口定位。
function Stop-ProcessOnPort($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
        return $conn.OwningProcess
    }
    return $null
}

function Start-App($name, $jar, $extraArgs) {
    $out = Join-Path $logDir "$name.log"
    $err = Join-Path $logDir "$name.err.log"
    $args = @('-jar', $jar) + $extraArgs
    $process = Start-Process -FilePath $script:JavaExe -ArgumentList $args -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
    return [pscustomobject]@{ Name = $name; Process = $process; Out = $out; Err = $err }
}

function Get-LeaderInstance($base) {
    $summary = Get-Json "$base/dashboard/summary"
    if ($summary -and $summary.data -and $summary.data.leaderInstance) {
        return $summary.data.leaderInstance
    }
    return $null
}

function Get-Executions($base, $taskId) {
    $result = Get-Json "$base/executions?page=1&size=100&taskId=$taskId"
    if (-not $result -or -not $result.data) { return @() }
    return @($result.data.records)
}

function Get-LeaderPidByLog($app) {
    if (-not (Test-Path $app.Out)) { return $null }
    $line = Select-String -Path $app.Out -Pattern 'became leader: instanceId=(\w+)' |
        Select-Object -Last 1
    if (-not $line) { return $null }
    $instanceId = $line.Matches[0].Groups[1].Value
    return [pscustomobject]@{ InstanceId = $instanceId; Pid = $app.Process.Id }
}

try {
    Write-Step '准备'
    foreach ($jar in @($schedulerJar, $executorJar)) {
        if (-not (Test-Path $jar)) {
            throw "找不到构建产物 $jar，请先执行：mvn -DskipTests package"
        }
    }
    Remove-Item -Recurse -Force $logDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $logDir | Out-Null
    Write-Host "  日志目录：$logDir"
    Assert-PortFree $ExecutorPort 'executor'
    Assert-PortFree $SchedulerPortA 'scheduler-a'
    Assert-PortFree $SchedulerPortB 'scheduler-b'

    Write-Step '启动 executor 与两个 scheduler'
    $executor = Start-App 'executor' $executorJar @(
        "--server.port=$ExecutorPort",
        "--taskpilot.executor.address=127.0.0.1:$ExecutorPort",
        "--taskpilot.executor.scheduler-base-url=http://127.0.0.1:$SchedulerPortA"
    )
    $processes += $executor
    $schedA = Start-App 'scheduler-a' $schedulerJar @("--server.port=$SchedulerPortA")
    $processes += $schedA
    $schedB = Start-App 'scheduler-b' $schedulerJar @("--server.port=$SchedulerPortB")
    $processes += $schedB

    if (-not (Wait-ForPort $ExecutorPort 60)) { throw "executor 未在 60 秒内启动" }
    if (-not (Wait-ForPort $SchedulerPortA 60)) { throw "scheduler A 未在 60 秒内启动" }
    if (-not (Wait-ForPort $SchedulerPortB 60)) { throw "scheduler B 未在 60 秒内启动" }
    if (-not (Wait-ForHealth "http://127.0.0.1:$SchedulerPortA/actuator/health" 60)) { throw "scheduler A 健康检查未通过" }
    if (-not (Wait-ForHealth "http://127.0.0.1:$SchedulerPortB/actuator/health" 60)) { throw "scheduler B 健康检查未通过" }
    Write-Ok "executor(:$ExecutorPort) / scheduler-A(:$SchedulerPortA) / scheduler-B(:$SchedulerPortB) 已启动"

    Write-Step '等待选出 leader'
    $leader = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and -not $leader) {
        $leader = Get-LeaderInstance $baseA
        if (-not $leader) { Start-Sleep -Seconds 1 }
    }
    Assert-Condition ($null -ne $leader) "选出 leader：$leader"
    if (-not $leader) { throw '没有 leader，无法继续验证' }

    Write-Step "建 cron 任务（每 $CronSeconds 秒）"
    $cron = "*/$CronSeconds * * * * *"
    # 任务名唯一（服务端会校验重名），所以带上时间戳，重复跑不会撞名。
    $uniqueName = "$TaskName-$([DateTimeOffset]::Now.ToUnixTimeSeconds())"
    $body = @{
        taskName = $uniqueName
        taskGroup = 'DEFAULT'
        cronExpression = $cron
        handlerName = 'demoHandler'
        handlerParam = '{"message":"cluster-verify"}'
        shardTotal = 1
        timeoutSeconds = 30
        retryMax = 0
        retryIntervalSeconds = 10
    } | ConvertTo-Json -Compress
    $created = Invoke-RestMethod -Method Post -Uri "$baseA/tasks" -ContentType 'application/json' -Body $body
    $taskId = $created.data.id
    Write-Ok "任务已创建：id=$taskId name=$uniqueName cron=$cron"

    Write-Step "观察 $WaitSeconds 秒，统计自动触发次数"
    Start-Sleep -Seconds $WaitSeconds
    $records = Get-Executions $baseA $taskId
    $cronRecords = @($records | Where-Object { $_.triggerType -eq 'CRON' })
    $expected = [math]::Floor($WaitSeconds / $CronSeconds)
    $leaders = @($cronRecords | Select-Object -ExpandProperty leaderInstance -Unique)
    Write-Host "  实际触发=$($cronRecords.Count) 次，理论约 $expected 次，leader 实例=$($leaders -join ',')"
    Assert-Condition ($cronRecords.Count -ge 1) "确实触发了（至少 1 次）"
    Assert-Condition ($cronRecords.Count -le ($expected + 2)) "触发次数没有翻倍（实际 $($cronRecords.Count) ≤ 容忍上限 $($expected + 2)）"
    Assert-Condition ($leaders.Count -eq 1) "只有一个实例在触发（leader 实例唯一）"
    Assert-Condition (@($cronRecords | Where-Object { $_.status -eq 'SUCCESS' }).Count -gt 0) "存在成功执行的记录"

    Write-Step '强杀 leader，验证故障转移'
    $leaderApp = $null
    foreach ($app in @($schedA, $schedB)) {
        $info = Get-LeaderPidByLog $app
        if ($info -and $info.InstanceId -eq $leader) { $leaderApp = $app; break }
    }
    Assert-Condition ($null -ne $leaderApp) "定位到 leader 进程"
    if ($null -eq $leaderApp) { throw '无法定位 leader 进程' }

    $beforeCount = $cronRecords.Count
    $killAt = Get-Date
    $leaderPort = if ($leaderApp.Name -eq 'scheduler-a') { $SchedulerPortA } else { $SchedulerPortB }
    $killedPid = Stop-ProcessOnPort $leaderPort
    Write-Host "  已杀掉 leader：$($leaderApp.Name) (port=$leaderPort pid=$killedPid)"

    # 接管情况必须问存活的实例——被杀的那个端口已经没了。
    $survivor = if ($leaderApp.Name -eq 'scheduler-a') { $schedB } else { $schedA }
    $survivorBase = if ($leaderApp.Name -eq 'scheduler-a') { $baseB } else { $baseA }
    Write-Host "  存活实例：$($survivor.Name)"

    $newLeader = $null
    $deadline = (Get-Date).AddSeconds($FailoverTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not $newLeader) {
        $newLeader = Get-LeaderInstance $survivorBase
        if ($newLeader -and $newLeader -eq $leader) { $newLeader = $null }
        if (-not $newLeader) { Start-Sleep -Seconds 1 }
    }
    $takeoverSeconds = [int]((Get-Date) - $killAt).TotalSeconds
    Assert-Condition ($null -ne $newLeader) "另一个实例接管成为 leader：$newLeader（耗时 ${takeoverSeconds}s）"

    Write-Step '接管后继续触发'
    Start-Sleep -Seconds ($CronSeconds + 5)
    $after = Get-Executions $survivorBase $taskId
    $newRecords = @($after | Where-Object { $_.leaderInstance -eq $newLeader })
    Assert-Condition ($newRecords.Count -gt 0) "新 leader 已继续触发（$($newRecords.Count) 条）"
    $tokens = @($newRecords | ForEach-Object { $_.fencingToken })
    if ($tokens.Count -gt 0) {
        Assert-Condition ($tokens[0] -gt 0) "新 leader 的 fencing token 已递增：$($tokens[0])"
    }

    Write-Step '汇总'
    Write-Host "  观察窗口        : $WaitSeconds 秒（cron 每 $CronSeconds 秒）"
    Write-Host "  触发次数        : $($cronRecords.Count)（理论约 $expected，双实例若都触发会翻倍）"
    Write-Host "  触发 leader     : $($leaders -join ',')"
    Write-Host "  故障转移耗时    : ${takeoverSeconds}s"
    Write-Host "  接管后新增记录  : $($newRecords.Count)"
    Write-Host "  执行记录总数    : $($after.Count)"
}
catch {
    Write-Fail $_.Exception.Message
}
finally {
    if ($taskId) {
        try { Invoke-RestMethod -Method Delete -Uri "$baseA/tasks/$taskId" -TimeoutSec 5 | Out-Null } catch { }
    }
    if (-not $KeepRunning) {
        Write-Step '清理进程'
        foreach ($port in @($ExecutorPort, $SchedulerPortA, $SchedulerPortB)) {
            Stop-ProcessOnPort $port | Out-Null
        }
        Write-Host "  已停止全部进程"
    } else {
        Write-Host "  -KeepRunning：进程保留，日志在 $logDir"
    }
}

if ($failures.Count -gt 0) {
    Write-Host "`n验收未通过：" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "`n验收通过" -ForegroundColor Green
exit 0
