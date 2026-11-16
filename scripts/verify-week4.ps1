<#
.SYNOPSIS
    TaskPilot 第 4 周端到端验收：双 scheduler + 双 executor + RabbitMQ，覆盖 5 个场景。

.DESCRIPTION
    场景（每项失败都会累加到退出码）：
      1. cron 长任务（sleep 15s）：记录先 RUNNING，约 15s 后经 RabbitMQ 回传落成 SUCCESS，
         执行期间调度中心仍然可用（dispatch 不再阻塞）；
      2. 手动触发：接口 < 2s 返回 RUNNING，轮询执行记录到终态；
      3. 杀执行器 -> 异步链路 failover 成功，且执行记录改指向新执行器；
      4. 重试预算耗尽 -> tp_dead_letter 有行，且调度中心日志里有告警；
      5. 重复投递同一条结果消息 -> 终态只写一次（幂等）。

    约定与 scripts/verify-week3.ps1 一致：按端口定位进程、日志放 .tmp/、
    断言全部通过退出码 0，否则 1（可直接接 CI）。

    前置：MySQL 已执行 deploy/sql/01-init.sql、Redis 可达（6379）、
         RabbitMQ 可达（5672，管理界面 15672），mvn clean verify 已产出两个 jar。
    用法：powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1
         powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1 -MysqlPort 3307 -CronSeconds 5

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1
    powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1 -MysqlPort 3307 -KeepRunning
#>
param(
    [int]$SchedulerPortA = 8080,
    [int]$SchedulerPortB = 8090,
    [int]$ExecutorPortA = 8081,
    [int]$ExecutorPortB = 8082,
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = 'taskpilot',
    [string]$MysqlPassword = 'taskpilot',
    [string]$MysqlDatabase = 'taskpilot',
    [string]$MySqlExe = 'mysql',
    [int]$CronSeconds = 5,
    [int]$RabbitPort = 5672,
    [int]$RabbitManagementPort = 15672,
    [string]$RabbitUser = 'taskpilot',
    [string]$RabbitPassword = 'taskpilot',
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.tmp/verify-week4'
$schedulerJar = Join-Path $root 'taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar'
$executorJar = Join-Path $root 'taskpilot-executor/target/taskpilot-executor-1.0.0-SNAPSHOT.jar'
$script:baseA = "http://127.0.0.1:$SchedulerPortA/api/v1"
$script:baseB = "http://127.0.0.1:$SchedulerPortB/api/v1"
$script:base = $script:baseA
$script:failures = @()
$script:taskIds = @()
$script:metrics = [ordered]@{}

# 优先用真实 JDK（PATH 上的 java 可能是转发程序，pid 对不上）。
$script:JavaExe = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    "$env:JAVA_HOME\bin\java.exe"
} else {
    'java'
}

$jdbc = "jdbc:mysql://127.0.0.1:$MysqlPort/$MysqlDatabase" +
        '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
# 心跳超时调短只是为了验收不等 30s；重试兜底扫描保持默认值，和生产一致。
$script:schedulerArgs = @(
    "--spring.datasource.url=$jdbc",
    "--spring.datasource.username=$MysqlUser",
    "--spring.datasource.password=$MysqlPassword",
    '--taskpilot.executor.heartbeat-timeout-seconds=10',
    '--taskpilot.executor.health-check-interval-seconds=3'
)
$script:executorSchedulerUrls = "http://127.0.0.1:$SchedulerPortA,http://127.0.0.1:$SchedulerPortB"
$script:rabbitAuth = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes("$RabbitUser`:$RabbitPassword"))
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

function Invoke-Api($method, $path, $body) {
    if ($null -eq $body) {
        return Invoke-RestMethod -Method $method -Uri "$script:base$path" -TimeoutSec 30
    }
    $json = $body | ConvertTo-Json -Compress -Depth 6
    return Invoke-RestMethod -Method $method -Uri "$script:base$path" `
        -ContentType 'application/json' -Body $json -TimeoutSec 30
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

function Assert-PortFree($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        throw "端口 $port 已被占用（pid=$($conn.OwningProcess)）。请先停掉占用进程再跑验收脚本。"
    }
}

# 按端口找真正在监听的进程再停：Start-Process 返回的 pid 不一定是 JVM 的 pid。
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
    $allArgs = @('-jar', $jar) + $extraArgs
    $process = Start-Process -FilePath $script:JavaExe -ArgumentList $allArgs -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
    return [pscustomobject]@{ Name = $name; Process = $process; Out = $out; Err = $err }
}

function Start-Scheduler($name, $port) {
    $args = @("--server.port=$port") + $script:schedulerArgs
    return Start-App $name $schedulerJar $args
}

function Start-Executor($name, $port) {
    return Start-App $name $executorJar @(
        "--server.port=$port",
        "--taskpilot.executor.address=127.0.0.1:$port",
        "--taskpilot.executor.scheduler-base-url=$script:executorSchedulerUrls"
    )
}

function Wait-ForLeaderInLogs($names, $timeoutSeconds) {
    # tp_leader 里可能留着上一轮的 instanceId，dashboard/summary 会返回"陈旧 leader"。
    # 只看本轮新写的日志里有没有 became leader，才是真正活着的 leader。
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($n in $names) {
            $f = Join-Path $logDir "$n.log"
            if (Test-Path $f) {
                $line = Select-String -Path $f -Pattern 'became leader: instanceId=(\w+)' |
                    Select-Object -Last 1
                if ($line) { return $line.Matches[0].Groups[1].Value }
            }
        }
        Start-Sleep -Seconds 1
    }
    return $null
}

function New-Name($prefix) {
    return "$prefix-$([DateTimeOffset]::Now.ToUnixTimeSeconds())-$([guid]::NewGuid().ToString('N').Substring(0,4))"
}

function New-Task($name, $cron, $handler, $param, $shardTotal, $timeout, $retryMax, $retryInterval) {
    $body = @{
        taskName = $name
        taskGroup = 'DEFAULT'
        cronExpression = $cron
        handlerName = $handler
        handlerParam = $param
        shardTotal = $shardTotal
        timeoutSeconds = $timeout
        retryMax = $retryMax
        retryIntervalSeconds = $retryInterval
        description = 'scripts/verify-week4.ps1'
    }
    $r = Invoke-Api 'Post' '/tasks' $body
    $script:taskIds += [long]$r.data.id
    return $r.data
}

function Get-TaskRecords($taskId) {
    $r = Get-Json "$script:base/executions?page=1&size=100&taskId=$taskId"
    if (-not $r -or -not $r.data) { return @() }
    return @($r.data.records)
}

function Get-Record($id) {
    $r = Get-Json "$script:base/executions/$id"
    if (-not $r) { return $null }
    return $r.data
}

function Wait-ForRecord($taskId, $predicate, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($r in (Get-TaskRecords $taskId)) {
            if (& $predicate $r) { return $r }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Invoke-MySql($sql) {
    # mysql 的 "-p口令" 会在 stderr 写一行告警；PowerShell 5.1 在 $ErrorActionPreference=Stop
    # 下会把原生 stderr 当成终止错误抛出。这里把 stderr 单独导到文件，避免误判为 "CLI 不可用"。
    $mysqlArgs = @('--protocol=TCP', '-h', '127.0.0.1', '-P', "$MysqlPort",
                   '-u', $MysqlUser, "-p$MysqlPassword", '-D', $MysqlDatabase,
                   '-N', '-B', '-e', $sql)
    $errFile = Join-Path $logDir 'mysql-stderr.tmp'
    $prev = $ErrorActionPreference
    try {
        # SilentlyContinue：PowerShell 5.1 会把原生 stderr 包装成错误记录打出来，
        # 这里只是 mysql 的 "-p口令" 告警，真正的失败靠 ExitCode 判断。
        $ErrorActionPreference = 'SilentlyContinue'
        $out = & $MySqlExe @mysqlArgs 2>$errFile | Out-String
        $code = $LASTEXITCODE
        $err = ''
        if (Test-Path $errFile) { $err = Get-Content $errFile -Raw }
        return [pscustomobject]@{ Available = $true; Output = "$out`n$err"; ExitCode = $code }
    } catch {
        return [pscustomobject]@{ Available = $false; Output = $_.Exception.Message; ExitCode = -1 }
    } finally {
        $ErrorActionPreference = $prev
        Remove-Item $errFile -Force -ErrorAction SilentlyContinue
    }
}

# 通过 RabbitMQ 管理接口发一条结果消息：用来模拟"同一条结果被重复投递"。
function Publish-ResultMessage($message) {
    $vhost = [uri]::EscapeDataString('/')
    $url = "http://127.0.0.1:$RabbitManagementPort/api/exchanges/$vhost/taskpilot.execution.results/publish"
    $body = @{
        properties = @{ delivery_mode = 2; content_type = 'application/json' }
        routing_key = 'execution-result'
        payload = ($message | ConvertTo-Json -Compress -Depth 6)
        payload_encoding = 'string'
    } | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Method Post -Uri $url -Headers $script:rabbitAuth `
        -ContentType 'application/json' -Body $body -TimeoutSec 15
}

function Disable-CreatedTasks {
    foreach ($id in $script:taskIds) {
        try { Invoke-Api 'Post' "/tasks/$id/disable" $null | Out-Null } catch { }
    }
}

try {
    Write-Step '准备'
    # Windows 环境变量大小写不敏感，但 PowerShell 5.1 的 Start-Process 会用大小写敏感的
    # 字典重建环境：同时存在 HTTP_PROXY / http_proxy 时会抛 "已添加了项"。验收全走
    # 127.0.0.1（NO_PROXY 已覆盖），直接把两组代理变量从本进程清掉即可。
    foreach ($n in @('http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY')) {
        [System.Environment]::SetEnvironmentVariable($n, $null, [System.EnvironmentVariableTarget]::Process)
    }
    foreach ($jar in @($schedulerJar, $executorJar)) {
        if (-not (Test-Path $jar)) { throw "找不到构建产物 $jar，请先执行：mvn clean verify" }
    }
    Remove-Item -Recurse -Force $logDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $logDir | Out-Null
    Write-Host "  日志目录：$logDir"

    # 前置检查：RabbitMQ 必须先起来（第 4 周起结果走 MQ 回来）。
    if (-not (Wait-ForPort $RabbitPort 5)) {
        throw ("端口 $RabbitPort 上没有服务。请先启动 RabbitMQ：" +
               "docker compose -f deploy/docker-compose.yml up -d rabbitmq")
    }
    Write-Host "  RabbitMQ :$RabbitPort 已就绪"
    $rabbitMgmt = $null
    try {
        $rabbitMgmt = Invoke-RestMethod -Uri "http://127.0.0.1:$RabbitManagementPort/api/overview" `
            -Headers $script:rabbitAuth -TimeoutSec 10
    } catch {
        $rabbitMgmt = $null
    }
    $script:metrics['rabbitManagement'] = ($null -ne $rabbitMgmt)
    Write-Host ("  RabbitMQ 管理界面 :$RabbitManagementPort " +
                $(if ($rabbitMgmt) { '已就绪' } else { '不可用（场景 ⑤ 会跳过）' }))

    foreach ($p in @($ExecutorPortA, $ExecutorPortB, $SchedulerPortA, $SchedulerPortB)) {
        Assert-PortFree $p
    }

    Write-Step '启动双 executor + 双 scheduler'
    Start-Executor 'executor-a' $ExecutorPortA | Out-Null
    Start-Executor 'executor-b' $ExecutorPortB | Out-Null
    Start-Scheduler 'scheduler-a' $SchedulerPortA | Out-Null
    Start-Scheduler 'scheduler-b' $SchedulerPortB | Out-Null
    foreach ($p in @($ExecutorPortA, $ExecutorPortB, $SchedulerPortA, $SchedulerPortB)) {
        if (-not (Wait-ForPort $p 60)) { throw "端口 $p 未在 60 秒内启动" }
    }
    foreach ($p in @($SchedulerPortA, $SchedulerPortB)) {
        if (-not (Wait-ForHealth "http://127.0.0.1:$p/actuator/health" 60)) {
            throw "scheduler :$p 健康检查未通过"
        }
    }
    $leader = Wait-ForLeaderInLogs @('scheduler-a', 'scheduler-b') 60
    Assert-Condition ($null -ne $leader) "选出 leader：$leader"
    if (-not $leader) { throw '没有 leader，无法继续验证' }
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        $s = Get-Json "$script:base/dashboard/summary"
        if ($s -and $s.data -and $s.data.onlineExecutorCount -ge 2) { break }
        Start-Sleep -Seconds 1
    }
    Assert-Condition ($s.data.onlineExecutorCount -ge 2) "两台执行器已注册 ONLINE（$($s.data.onlineExecutorCount)）"

    # ---------------------------------------------------------------- ①
    Write-Step '① cron 长任务（sleep 15s）：RUNNING -> SUCCESS 经 RabbitMQ 落库'
    $t1 = New-Task (New-Name 'wk4-longtask') "*/$CronSeconds * * * * *" 'sleepHandler' `
        '{"millis":15000}' 1 40 0 10
    $running = Wait-ForRecord $t1.id {
        param($r) $r.triggerType -eq 'CRON' -and $r.status -eq 'RUNNING'
    } 40
    Assert-Condition ($null -ne $running) '捕获到在途的 RUNNING 执行记录'
    if ($running) {
        # 长任务执行期间调度中心必须还能用：dispatch 不再把 Web / 调度线程占住。
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $health = Get-Json "http://127.0.0.1:$SchedulerPortA/actuator/health"
        $sw.Stop()
        Assert-Condition ($null -ne $health -and $health.status -eq 'UP' -and $sw.ElapsedMilliseconds -lt 2000) `
            "长任务执行期间调度中心仍然可用（健康检查 $([int]$sw.ElapsedMilliseconds) ms）"

        $final = Wait-ForRecord $t1.id {
            param($r) $r.id -eq $running.id -and $r.status -eq 'SUCCESS'
        } 60
        Assert-Condition ($null -ne $final) '长任务经 RabbitMQ 回传后落成 SUCCESS'
        if ($final) {
            Write-Host "  执行耗时 $($final.durationMs) ms（处理器实际跑 15s）"
            Assert-Condition ($final.durationMs -ge 14000) "耗时确实是 15s 量级（$($final.durationMs) ms）"
            $script:metrics['longTaskDurationMs'] = $final.durationMs
        }
    }

    # ---------------------------------------------------------------- ②
    Write-Step '② 手动触发：接口快回 RUNNING，轮询到终态'
    $t2 = New-Task (New-Name 'wk4-manual') '0 0 0 1 1 *' 'sleepHandler' `
        '{"millis":15000}' 1 40 0 10
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $trigger = Invoke-Api 'Post' "/tasks/$($t2.id)/trigger" $null
    $sw.Stop()
    $manualMs = [int]$sw.ElapsedMilliseconds
    $script:metrics['manualTriggerMs'] = $manualMs
    Write-Host "  手动触发耗时 ${manualMs} ms"
    Assert-Condition ($trigger.data.status -eq 'RUNNING') "手动触发立即返回 RUNNING（实际 $($trigger.data.status)）"
    Assert-Condition ($manualMs -lt 2000) "手动触发 < 2s 返回（实测 ${manualMs} ms）"
    $manualId = [long]$trigger.data.executionId
    $final2 = Wait-ForRecord $t2.id {
        param($r) $r.id -eq $manualId -and $r.status -ne 'RUNNING'
    } 60
    Assert-Condition ($null -ne $final2) '轮询到终态'
    if ($final2) {
        Write-Host "  轮询到的终态：$($final2.status)，耗时 $($final2.durationMs) ms"
        Assert-Condition ($final2.status -eq 'SUCCESS') "终态为 SUCCESS（实际 $($final2.status)）"
    }

    # ---------------------------------------------------------------- ⑤
    Write-Step '⑤ 重复投递同一结果消息：终态只写一次'
    if ($null -eq $rabbitMgmt) {
        Write-Host '  [SKIP] RabbitMQ 管理界面不可用，跳过重复投递校验'
    } else {
        $t5 = New-Task (New-Name 'wk4-dup') '0 0 0 1 1 *' 'demoHandler' `
            '{"message":"dup"}' 1 30 0 10
        $trigger5 = Invoke-Api 'Post' "/tasks/$($t5.id)/trigger" $null
        $dupId = [long]$trigger5.data.executionId
        $done = Wait-ForRecord $t5.id {
            param($r) $r.id -eq $dupId -and $r.status -ne 'RUNNING'
        } 40
        Assert-Condition ($null -ne $done) '被重复投递的那条记录先正常跑完'
        if ($done) {
            $before = Get-Record $dupId
            $message = @{
                executionId = $dupId
                taskId = [long]$t5.id
                status = $before.status
                durationMs = [long]$before.durationMs
                errorMessage = $before.errorMessage
                resultSummary = $before.resultSummary
                executorAddress = $before.executorAddress
                reportedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')
            }
            $pub1 = Publish-ResultMessage $message
            $pub2 = Publish-ResultMessage $message
            Assert-Condition ($pub1.routed -and $pub2.routed) '两条重复的结果消息都投递成功'
            Start-Sleep -Seconds 4
            $after = Get-Record $dupId
            Write-Host "  重复投递前 endTime=$($before.endTime)，投递后 endTime=$($after.endTime)"
            Assert-Condition ($after.status -eq $before.status) '重复投递没有改写终态'
            Assert-Condition ($after.endTime -eq $before.endTime) '重复投递没有二次写库（endTime 未变）'
            $skipLogs = Select-String -Path (Join-Path $logDir 'scheduler-*.log') `
                -Pattern 'already finalized, skip terminal overwrite' -ErrorAction SilentlyContinue
            Assert-Condition ($null -ne $skipLogs) '调度中心日志记录了"跳过终态覆写"'
        }
    }

    # ---------------------------------------------------------------- ③
    Write-Step '③ 杀执行器 -> 异步链路 failover 成功且记录指向新执行器'
    $t3 = New-Task (New-Name 'wk4-failover') "*/$CronSeconds * * * * *" 'sleepHandler' `
        '{"millis":12000}' 1 40 5 3
    $running3 = Wait-ForRecord $t3.id {
        param($r) $r.status -eq 'RUNNING' -and $r.triggerType -eq 'CRON'
    } 40
    Assert-Condition ($null -ne $running3) '捕获到在途的 RUNNING 执行记录'
    if ($running3) {
        $victimAddr = $running3.executorAddress
        $victimPort = [int](($victimAddr -split ':')[1])
        $survivorAddr = if ($victimPort -eq $ExecutorPortA) {
            "127.0.0.1:$ExecutorPortB"
        } else {
            "127.0.0.1:$ExecutorPortA"
        }
        $killAt = Get-Date
        $killedPid = Stop-ProcessOnPort $victimPort
        Write-Host "  已杀 $victimAddr (pid=$killedPid)，存活执行器 $survivorAddr"
        # 执行器死了 → 结果消息永远回不来 → 心跳超时下线 → 收尾 + 失败转移 → 换机重跑 →
        # 结果再经 RabbitMQ 回来。整个链路比 Week 3 的同步版多一跳。
        $deadline = (Get-Date).AddSeconds(150)
        $final3 = $null
        while ((Get-Date) -lt $deadline) {
            $rec = Get-Record $running3.id
            if ($rec -and $rec.retryCount -ge 1 -and $rec.status -eq 'SUCCESS' -and
                $rec.executorAddress -eq $survivorAddr) {
                $final3 = $rec
                break
            }
            Start-Sleep -Milliseconds 500
        }
        $takeover = [int]((Get-Date) - $killAt).TotalSeconds
        $script:metrics['failoverTakeoverSeconds'] = $takeover
        Assert-Condition ($null -ne $final3) "failover 经 MQ 重跑成功（耗时 ${takeover}s）"
        if ($final3) {
            Write-Host "  记录 id=$($final3.id) retryCount=$($final3.retryCount) executor=$($final3.executorAddress) status=$($final3.status)"
            Assert-Condition ($final3.executorAddress -eq $survivorAddr) "记录已改指向新执行器 $survivorAddr"
            Assert-Condition ($final3.executorAddress -ne $victimAddr) '记录不再指向被杀掉的旧执行器'
            Assert-Condition ($final3.retryCount -ge 1) '复用同一条记录重试（retryCount ≥ 1）'
        }
        # 后续 ④ 需要两台执行器，把被杀的那台拉回来。
        Start-Executor "executor-restarted-$victimPort" $victimPort | Out-Null
        if (-not (Wait-ForPort $victimPort 60)) { throw "被杀的执行器 :$victimPort 未能重启" }
        $deadline = (Get-Date).AddSeconds(40)
        while ((Get-Date) -lt $deadline) {
            $s = Get-Json "$script:base/dashboard/summary"
            if ($s -and $s.data -and $s.data.onlineExecutorCount -ge 2) { break }
            Start-Sleep -Seconds 1
        }
        Assert-Condition ($s.data.onlineExecutorCount -ge 2) '两台执行器恢复 ONLINE'
    }

    # ---------------------------------------------------------------- ④
    Write-Step '④ 重试预算耗尽 -> 死信表有行 + 告警日志'
    $t4 = New-Task (New-Name 'wk4-deadletter') "*/$CronSeconds * * * * *" 'httpHandler' `
        '{"url":"http://127.0.0.1:9/unreachable"}' 1 10 1 3
    $deadline = (Get-Date).AddSeconds(120)
    $deadRows = ''
    while ((Get-Date) -lt $deadline) {
        $q = Invoke-MySql "SELECT COUNT(*) FROM tp_dead_letter WHERE task_id=$($t4.id);"
        if ($q.Available -and $q.ExitCode -eq 0) {
            $m = [regex]::Match($q.Output, '(?m)^\s*(\d+)\s*$')
            if ($m.Success -and [int]$m.Groups[1].Value -ge 1) {
                $deadRows = $m.Groups[1].Value
                break
            }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $deadRows) {
        $q = Invoke-MySql "SELECT COUNT(*) FROM tp_dead_letter WHERE task_id=$($t4.id);"
        Write-Host "  死信查询结果：$($q.Output)"
    }
    Assert-Condition ([bool]$deadRows) 'tp_dead_letter 里出现该任务的死信行'
    if ($deadRows) {
        $script:metrics['deadLetterRows'] = [int]$deadRows
        # 只查数字与时间，不把中文原因拉回来比对：mysql CLI 的输出编码在
        # Windows 上不一定和脚本一致，中文断言会误判。
        $detail = Invoke-MySql ("SELECT COUNT(*) FROM tp_dead_letter " +
            "WHERE task_id=$($t4.id) AND last_error IS NOT NULL AND last_error <> '';")
        $m = [regex]::Match($detail.Output, '(?m)^\s*(\d+)\s*$')
        Assert-Condition ($m.Success -and [int]$m.Groups[1].Value -ge 1) '死信行记录了失败原因'
        $retry = Invoke-MySql "SELECT SUM(retry_count) FROM tp_dead_letter WHERE task_id=$($t4.id);"
        Write-Host "  死信行数 $($deadRows)，累计重试次数 $($retry.Output -replace "`r?`n", '')"
    }
    $alertLogs = Select-String -Path (Join-Path $logDir 'scheduler-*.log') `
        -Pattern 'dead letter recorded|alert published' -ErrorAction SilentlyContinue
    Assert-Condition ($null -ne $alertLogs) '调度中心日志里有死信 / 告警记录'
    $summary = Get-Json "$script:base/dashboard/summary"
    if ($summary -and $summary.data) {
        Assert-Condition ($summary.data.deadLetterCount -ge 1) "概览里能看到死信数（$($summary.data.deadLetterCount)）"
    }

    Write-Step '汇总'
    Write-Host "  长任务耗时      : $($script:metrics['longTaskDurationMs']) ms"
    Write-Host "  手动触发耗时    : $($script:metrics['manualTriggerMs']) ms"
    Write-Host "  failover 接管   : $($script:metrics['failoverTakeoverSeconds'])s"
    Write-Host "  死信行数        : $($script:metrics['deadLetterRows'])"
    $json = $script:metrics | ConvertTo-Json -Depth 4
    Set-Content -Path (Join-Path $logDir 'results.json') -Value $json -Encoding UTF8
    Write-Host "  结果已写入：$logDir/results.json"
}
catch {
    Write-Fail $_.Exception.Message
}
finally {
    Disable-CreatedTasks
    foreach ($id in $script:taskIds) {
        try { Invoke-Api 'Delete' "/tasks/$id" $null | Out-Null } catch { }
    }
    if (-not $KeepRunning) {
        Write-Step '清理进程'
        foreach ($p in @($ExecutorPortA, $ExecutorPortB, $SchedulerPortA, $SchedulerPortB)) {
            Stop-ProcessOnPort $p | Out-Null
        }
        Write-Host '  已停止全部进程'
    } else {
        Write-Host "  -KeepRunning：进程保留，日志在 $logDir"
    }
}

if ($script:failures.Count -gt 0) {
    Write-Host "`n验收未通过：" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "`n验收通过" -ForegroundColor Green
exit 0
