<#
.SYNOPSIS
    TaskPilot 第 3 周端到端验收：双 scheduler + 双 executor，覆盖 6 个场景。

.DESCRIPTION
    场景（每项失败都会累加到退出码）：
      1. 分片并发计时：4 分片各睡 1s，实测耗时明显小于串行 4s；
      2. 杀执行器 -> failover 成功，且执行记录改指向新执行器（依赖 1.2 / 1.1）；
      3. 退避重试时序：同一条记录上的重试间隔 5s -> 10s（指数）；
      4. 同一计划时间重复触发被幂等去重：execution_key 唯一 + uk_execution_key 拒绝重复键；
      5. 广播任务每台执行器都执行（依赖 0.2，脚本会重启 scheduler 切到 BROADCAST）；
      6. 改 cron 后触发频率不翻倍（依赖 0.1）。

    约定与 scripts/verify-cluster.ps1 一致：按端口定位进程、日志放 .tmp/、
    断言全部通过退出码 0，否则 1（可直接接 CI）。

    前置：MySQL 已执行 deploy/sql/01-init.sql，Redis 可达（6379），
         mvn clean verify 已产出两个 jar。
    用法：powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1
         powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1 -MysqlPort 3307 -CronSeconds 5

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1
    powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1 -MysqlPort 3307 -KeepRunning
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
    [int]$ShardSleepMillis = 1000,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.tmp/verify-week3'
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

function Start-Scheduler($name, $port, $allocation) {
    $args = @("--server.port=$port") + $script:schedulerArgs
    if ($allocation) { $args += "--taskpilot.scheduler.shard-allocation=$allocation" }
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
        description = 'scripts/verify-week3.ps1'
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
        $ErrorActionPreference = 'Continue'
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
    foreach ($p in @($ExecutorPortA, $ExecutorPortB, $SchedulerPortA, $SchedulerPortB)) {
        Assert-PortFree $p
    }

    Write-Step '启动双 executor + 双 scheduler'
    Start-Executor 'executor-a' $ExecutorPortA | Out-Null
    Start-Executor 'executor-b' $ExecutorPortB | Out-Null
    Start-Scheduler 'scheduler-a' $SchedulerPortA 'AVERAGE' | Out-Null
    Start-Scheduler 'scheduler-b' $SchedulerPortB 'AVERAGE' | Out-Null
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
    # 等执行器注册进 tp_executor，否则分片只投给一台。
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        $s = Get-Json "$script:base/dashboard/summary"
        if ($s -and $s.data -and $s.data.onlineExecutorCount -ge 2) { break }
        Start-Sleep -Seconds 1
    }
    Assert-Condition ($s.data.onlineExecutorCount -ge 2) "两台执行器已注册 ONLINE（$($s.data.onlineExecutorCount)）"

    # ---------------------------------------------------------------- ①
    Write-Step '① 分片并发计时'
    $t1 = New-Task (New-Name 'wk3-parallel') '0 0 0 1 1 *' 'sleepHandler' `
        ('{"millis":' + $ShardSleepMillis + '}') 4 30 0 10
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $trigger = Invoke-Api 'Post' "/tasks/$($t1.id)/trigger" $null
    $sw.Stop()
    $parallelMs = [int]$sw.ElapsedMilliseconds
    $execs = @($trigger.data.executions)
    $serialMs = 4 * $ShardSleepMillis
    Assert-Condition ($execs.Count -eq 4) "4 个分片各落一条执行记录（实际 $($execs.Count)）"
    $success = @($execs | Where-Object { $_.status -eq 'SUCCESS' })
    Assert-Condition ($success.Count -eq 4) "4 个分片全部成功（实际 $($success.Count)）"
    Assert-Condition ($parallelMs -lt ($serialMs * 0.8)) `
        "分片并发生效：实测 ${parallelMs}ms < 串行估算 ${serialMs}ms × 0.8"
    $script:metrics['parallelMs'] = $parallelMs
    $script:metrics['serialMs'] = $serialMs
    $script:metrics['speedup'] = [math]::Round($serialMs / [math]::Max($parallelMs, 1), 2)
    Write-Host "  并发加速比：$($script:metrics['speedup'])x（$serialMs ms → $parallelMs ms）"

    # ---------------------------------------------------------------- ⑥
    Write-Step '⑥ 改 cron 后触发频率不翻倍（依赖 0.1）'
    $t6 = New-Task (New-Name 'wk3-cronchange') "*/$CronSeconds * * * * *" 'demoHandler' `
        '{"message":"cron-change"}' 1 30 0 10
    $window1 = $CronSeconds * 3 + 3
    Start-Sleep -Seconds $window1
    $beforeCount = @(Get-TaskRecords $t6.id | Where-Object { $_.triggerType -eq 'CRON' }).Count
    Write-Host "  改 cron 前：$window1 秒触发 $beforeCount 次（周期 ${CronSeconds}s）"
    Assert-Condition ($beforeCount -ge 2) "改 cron 前按原周期正常触发（$beforeCount 次）"
    $detail = Get-Json "$script:base/tasks/$($t6.id)"
    $update = @{
        taskName = $detail.data.taskName
        taskGroup = 'DEFAULT'
        cronExpression = '*/10 * * * * *'
        handlerName = $detail.data.handlerName
        handlerParam = $detail.data.handlerParam
        shardTotal = 1
        timeoutSeconds = 30
        retryMax = 0
        retryIntervalSeconds = 10
        description = 'cron changed by verify-week3'
    }
    Invoke-Api 'Put' "/tasks/$($t6.id)" $update | Out-Null
    $window2 = 22
    Start-Sleep -Seconds $window2
    $all6 = @(Get-TaskRecords $t6.id | Where-Object { $_.triggerType -eq 'CRON' })
    $after = $all6.Count - $beforeCount
    Write-Host "  改 cron 后：$window2 秒新增 $after 次（10s 周期理论约 2 次）"
    Assert-Condition ($after -ge 1 -and $after -le 3) `
        "改 cron 后旧节奏没有残留、频率未翻倍（新增 $after 次，容忍上限 3）"

    # ---------------------------------------------------------------- ④
    Write-Step '④ 同一计划时间重复触发被幂等去重'
    $keys = @($all6 | ForEach-Object { $_.executionKey })
    $uniqueKeys = @($keys | Select-Object -Unique)
    Assert-Condition ($keys.Count -gt 0 -and $uniqueKeys.Count -eq $keys.Count) `
        "定时任务 execution_key 全部唯一（$($keys.Count) 条）"
    $deterministic = @($keys | Where-Object { $_ -match '^\d+:\d{14}:\d+$' })
    Assert-Condition ($deterministic.Count -eq $keys.Count) '键为确定性格式 taskId:计划时间:分片号'
    if ($keys.Count -gt 0) {
        $dupKey = $keys[0]
        $insert = "INSERT INTO tp_execution_log (execution_key, task_id, task_name, trigger_type, " +
                  "trigger_time, status, retry_count, fencing_token) VALUES ('$dupKey', $($t6.id), " +
                  "'verify-dedup', 'CRON', NOW(3), 'FAILED', 0, 0)"
        $r = Invoke-MySql $insert
        if (-not $r.Available) {
            Write-Host '  [SKIP] 找不到 mysql CLI，跳过唯一索引直插校验（键唯一性断言已覆盖）'
        } else {
            Assert-Condition ($r.ExitCode -ne 0 -and ($r.Output -match '1062|Duplicate entry|duplicate')) `
                '重复 execution_key 被 uk_execution_key 拒绝（重复触发只会复用原记录）'
            $count = Invoke-MySql "SELECT COUNT(*) FROM tp_execution_log WHERE execution_key='$dupKey';"
            Assert-Condition ($count.ExitCode -eq 0 -and
                [regex]::IsMatch($count.Output, '(?m)^\s*1\s*$')) `
                '该计划时间点只存在 1 条记录'
        }
    }

    # ---------------------------------------------------------------- ③
    Write-Step '③ 退避重试时序（5s → 10s，依赖指数退避）'
    $t3 = New-Task (New-Name 'wk3-backoff') '*/10 * * * * *' 'httpHandler' `
        '{"url":"http://127.0.0.1:9/unreachable"}' 1 10 3 5
    $first = Wait-ForRecord $t3.id {
        param($r) $r.triggerType -eq 'CRON' -and $r.status -eq 'FAILED' -and $r.nextRetryTime
    } 40
    Assert-Condition ($null -ne $first) '捕获首条失败并排上 next_retry_time 的记录'
    if ($first) {
        # 停掉后续定时触发，只观察这一条记录的退避节奏。
        Invoke-Api 'Post' "/tasks/$($t3.id)/disable" $null | Out-Null
        $seen = @{}
        $deadline = (Get-Date).AddSeconds(50)
        while ((Get-Date) -lt $deadline) {
            $rec = Get-Record $first.id
            if ($rec) {
                $k = [int]$rec.retryCount
                if (-not $seen.ContainsKey($k)) { $seen[$k] = Get-Date }
            }
            if ($seen.Count -ge 3) { break }
            Start-Sleep -Milliseconds 500
        }
        $levels = @($seen.Keys | Sort-Object)
        $gaps = @()
        for ($i = 1; $i -lt $levels.Count; $i++) {
            $gaps += [math]::Round(($seen[$levels[$i]] - $seen[$levels[$i - 1]]).TotalSeconds, 1)
        }
        Write-Host "  观测到的重试间隔：$($gaps -join 's, ')s（retryCount 变化点 $($levels -join ',')）"
        $script:metrics['retryGapsSeconds'] = $gaps
        Assert-Condition ($gaps.Count -ge 2) '至少观测到两次重试（retryCount 递增）'
        if ($gaps.Count -ge 2) {
            Assert-Condition ($gaps[0] -ge 3 -and $gaps[0] -le 9) "第一次退避约 5s（实测 $($gaps[0])s）"
            Assert-Condition ($gaps[1] -ge 8 -and $gaps[1] -le 16) "第二次退避约 10s（实测 $($gaps[1])s）"
            Assert-Condition ($gaps[1] -gt $gaps[0]) '退避间隔按指数增长（第二次 > 第一次）'
        }
    }

    # ---------------------------------------------------------------- ②
    Write-Step '② 杀执行器 → failover 成功且记录指向新执行器（依赖 1.1 / 1.2）'
    $t2 = New-Task (New-Name 'wk3-failover') "*/$CronSeconds * * * * *" 'sleepHandler' `
        '{"millis":12000}' 1 30 5 3
    $running = Wait-ForRecord $t2.id {
        param($r) $r.status -eq 'RUNNING' -and $r.triggerType -eq 'CRON'
    } 40
    Assert-Condition ($null -ne $running) '捕获到在途的 RUNNING 执行记录'
    if ($running) {
        $victimAddr = $running.executorAddress
        $victimPort = [int](($victimAddr -split ':')[1])
        $survivorAddr = if ($victimPort -eq $ExecutorPortA) {
            "127.0.0.1:$ExecutorPortB"
        } else {
            "127.0.0.1:$ExecutorPortA"
        }
        $killAt = Get-Date
        $killedPid = Stop-ProcessOnPort $victimPort
        Write-Host "  已杀 $victimAddr (pid=$killedPid)，存活执行器 $survivorAddr"
        $deadline = (Get-Date).AddSeconds(110)
        $final = $null
        while ((Get-Date) -lt $deadline) {
            $rec = Get-Record $running.id
            if ($rec -and $rec.retryCount -ge 1 -and $rec.status -eq 'SUCCESS' -and
                $rec.executorAddress -eq $survivorAddr) {
                $final = $rec
                break
            }
            Start-Sleep -Milliseconds 500
        }
        $takeover = [int]((Get-Date) - $killAt).TotalSeconds
        $script:metrics['failoverTakeoverSeconds'] = $takeover
        Assert-Condition ($null -ne $final) "failover 重跑成功（耗时 ${takeover}s）"
        if ($final) {
            Write-Host "  记录 id=$($final.id) retryCount=$($final.retryCount) executor=$($final.executorAddress) status=$($final.status)"
            Assert-Condition ($final.executorAddress -eq $survivorAddr) "记录已改指向新执行器 $survivorAddr"
            Assert-Condition ($final.executorAddress -ne $victimAddr) '记录不再指向被杀掉的旧执行器'
            Assert-Condition ($final.retryCount -ge 1) '复用同一条记录重试（retryCount ≥ 1）'
        }
        # 后续 ⑤ 需要两台执行器，把被杀的那台拉回来。
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

    # ---------------------------------------------------------------- ⑤
    Write-Step '⑤ 广播任务每台执行器都执行（依赖 0.2，需重启 scheduler 切 BROADCAST）'
    Disable-CreatedTasks
    foreach ($p in @($SchedulerPortA, $SchedulerPortB)) { Stop-ProcessOnPort $p | Out-Null }
    # 硬杀不会走 @PreDestroy，watchdog 锁还在：清掉让新实例立刻选出 leader。
    $redisCli = Get-Command redis-cli -ErrorAction SilentlyContinue
    if ($redisCli) { & $redisCli.Source -h 127.0.0.1 DEL 'taskpilot:scheduler:leader' | Out-Null }
    Start-Sleep -Seconds 2
    Start-Scheduler 'scheduler-broadcast-a' $SchedulerPortA 'BROADCAST' | Out-Null
    Start-Scheduler 'scheduler-broadcast-b' $SchedulerPortB 'BROADCAST' | Out-Null
    foreach ($p in @($SchedulerPortA, $SchedulerPortB)) {
        if (-not (Wait-ForPort $p 60)) { throw "BROADCAST scheduler :$p 未启动" }
        if (-not (Wait-ForHealth "http://127.0.0.1:$p/actuator/health" 60)) {
            throw "BROADCAST scheduler :$p 健康检查未通过"
        }
    }
    $broadcastLeader = Wait-ForLeaderInLogs @('scheduler-broadcast-a', 'scheduler-broadcast-b') 60
    Assert-Condition ($null -ne $broadcastLeader) "BROADCAST 集群选出 leader：$broadcastLeader"
    Start-Sleep -Seconds 3

    $t5 = New-Task (New-Name 'wk3-broadcast') "*/$CronSeconds * * * * *" 'demoHandler' `
        '{"message":"broadcast"}' 2 30 0 10
    Start-Sleep -Seconds ($CronSeconds * 2 + 5)
    $broadcast = @(Get-TaskRecords $t5.id | Where-Object { $_.triggerType -eq 'CRON' })
    Write-Host "  广播任务共 $($broadcast.Count) 条记录（2 分片 × 2 执行器 = 每个计划时间 4 条）"
    $executorIds = @($broadcast | Select-Object -ExpandProperty executorId -Unique)
    Assert-Condition ($executorIds.Count -eq 2) "两台执行器都参与了执行（$($executorIds -join ',')）"
    $groups = @{}
    foreach ($r in $broadcast) {
        $parts = $r.executionKey -split ':'
        if ($parts.Count -eq 4) {
            $slot = $parts[1]
            if (-not $groups.ContainsKey($slot)) { $groups[$slot] = @() }
            $groups[$slot] += $r
        }
    }
    # 注意：不能把 $groups.Values 直接丢进管道，PowerShell 会把每个数组拆成单条记录。
    $full = @()
    foreach ($g in $groups.Values) {
        if ($g.Count -eq 4) { $full += ,$g }
    }
    Assert-Condition ($full.Count -ge 1) "至少一个计划时间点产出 4 条记录（完整广播组 $($full.Count) 个）"
    if ($full.Count -ge 1) {
        $group = $full[0]
        $shards = @($group | Select-Object -ExpandProperty shardIndex -Unique)
        $groupExecutors = @($group | Select-Object -ExpandProperty executorId -Unique)
        $groupKeys = @($group | Select-Object -ExpandProperty executionKey -Unique)
        Assert-Condition ($shards.Count -eq 2) '同一计划时间覆盖 2 个分片'
        Assert-Condition ($groupExecutors.Count -eq 2) '同一计划时间覆盖 2 台执行器'
        Assert-Condition ($groupKeys.Count -eq 4) '4 条记录的 execution_key 互不重复（键带执行器维度）'
    }
    $script:metrics['broadcastRecords'] = $broadcast.Count
    $script:metrics['broadcastExecutors'] = $executorIds.Count

    Write-Step '汇总'
    Write-Host "  分片并发        : $parallelMs ms（串行估算 $serialMs ms，加速比 $($script:metrics['speedup'])x）"
    Write-Host "  退避重试间隔    : $($script:metrics['retryGapsSeconds'] -join 's / ')s"
    Write-Host "  failover 接管   : $($script:metrics['failoverTakeoverSeconds'])s"
    Write-Host "  广播记录数      : $($broadcast.Count)"
    $summary = $script:metrics | ConvertTo-Json -Depth 4
    Set-Content -Path (Join-Path $logDir 'results.json') -Value $summary -Encoding UTF8
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
