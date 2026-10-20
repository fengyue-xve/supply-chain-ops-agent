# 供应链表单流转 Agent —— 离线一键验证脚本（阶段 1-4：Java 服务 + Pi runtime + 跨进程 E2E）
# 运行方式: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify.ps1
# 不访问网络、不调用真实模型/飞书；仅使用本地 Maven 缓存、H2 内存库与 fixtures。
# Pi 侧使用 pi-agent-runtime 内 npm ci 安装的固定版本 SDK 和本地工具；首次安装需联网。
# 跨进程 E2E 需要 8081 端口空闲（fault 注入计数按启动一次性消耗，必须先停掉旧实例）。

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$root = Split-Path -Parent $PSScriptRoot
$svc = Join-Path $root "java-flow-service"
$runtime = Join-Path $root "pi-agent-runtime"
$tsx = Join-Path $runtime "node_modules\.bin\tsx.cmd"
$tsc = Join-Path $runtime "node_modules\.bin\tsc.cmd"
$tsconfig = Join-Path $runtime "tsconfig.json"
$target = Join-Path $svc "target"
# 全新检出可能没有 target 目录；先建好，否则各段的日志重定向会失败
New-Item -ItemType Directory -Force -Path $target | Out-Null
$failed = $false

function Write-Section($text) {
    Write-Host ""
    Write-Host ("=" * 72)
    Write-Host $text
    Write-Host ("=" * 72)
}

function Section-Fail($message) {
    Write-Host ("RESULT: " + $message)
    $script:failed = $true
}

# ---------- 1/7 离线测试（Java） ----------
Write-Section "[1/7] Java service offline tests (mvn -o test)"
Push-Location $svc
try {
    cmd /c "mvn -o test > target\verify-mvn.log 2>&1"
    $testExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($testExit -ne 0) {
    Write-Host "RESULT: TEST FAILURE (exit=$testExit). Tail of target\verify-mvn.log:"
    Get-Content (Join-Path $svc "target\verify-mvn.log") -Tail 30
    $failed = $true
} else {
    Write-Host "RESULT: tests passed (exit=0)"
}

# ---------- 2/7 汇总 surefire 计数 ----------
Write-Section "[2/7] Test counts (target/surefire-reports)"
$tests = 0; $failures = 0; $errors = 0; $skipped = 0
$reports = Get-ChildItem (Join-Path $svc "target\surefire-reports") -Filter "*.txt" -ErrorAction SilentlyContinue
foreach ($r in $reports) {
    $line = (Get-Content $r.FullName -TotalCount 6 | Select-String "Tests run:" | Select-Object -First 1).Line
    if ($line -match "Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)") {
        $tests += [int]$Matches[1]; $failures += [int]$Matches[2]
        $errors += [int]$Matches[3]; $skipped += [int]$Matches[4]
        Write-Host ("  " + $r.BaseName + ": run=" + $Matches[1] + " fail=" + $Matches[2] + " err=" + $Matches[3] + " skip=" + $Matches[4])
    }
}
Write-Host ("TOTAL: tests=$tests failures=$failures errors=$errors skipped=$skipped")
if ($failures -ne 0 -or $errors -ne 0) { $failed = $true }

# ---------- 3/7 运行汇总 + 写入/跳过/复核计数 + 轨迹 ----------
Write-Section "[3/7] Java run summary (target/verify-summary.json)"
$summaryPath = Join-Path $svc "target\verify-summary.json"
$traceDir = Join-Path $svc "target\trace"
if (Test-Path $summaryPath) {
    $summary = Get-Content $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host ("run_count          = " + $summary.stats.run_count)
    $byStatus = ($summary.stats.runs_by_status.PSObject.Properties | ForEach-Object { $_.Name + "=" + $_.Value }) -join ", "
    Write-Host ("runs_by_status     = " + $byStatus)
    Write-Host ("total_write_count  = " + $summary.stats.total_write_count + "   (表单实际写入次数)")
    Write-Host ("total_skip_count   = " + $summary.stats.total_skip_count + "   (幂等/同值跳过次数)")
    Write-Host ("total_manual_review= " + $summary.stats.total_manual_review + "   (人工复核中的 run 数)")
    Write-Host ("trace_dir          = " + $summary.trace_dir)
    Write-Host ("audit rows scanned = " + $summary.redaction_scan.scanned_audit_rows + "   (Java 侧脱敏扫描)")
    if (-not $summary.redaction_scan.ok) { $failed = $true }
} else {
    Write-Host "MISSING: $summaryPath (tests may not have run)"
    $failed = $true
}

# ---------- 4/7 Java 轨迹文件独立二次脱敏扫描 ----------
Write-Section "[4/7] Independent redaction re-scan of Java trace files"
# 全局禁项：任何位置都不得出现（token 请求头凭据 / Authorization / Bearer）
$globalForbidden = @("token-op-1", "token-ap-1", "token-agent-1", "Bearer ", "Authorization:")
# 审计条目禁项：自由文本备注与完整 record_id 只允许出现在审计 detail 之外的地方
$auditForbidden = @("canary", "机密备注")
$hits = @()
$traces = @()
if (Test-Path $traceDir) { $traces = Get-ChildItem $traceDir -Filter "*.json" }
foreach ($t in $traces) {
    $raw = Get-Content $t.FullName -Raw -Encoding UTF8
    foreach ($p in $globalForbidden) {
        if ($raw.Contains($p)) { $hits += ("{0}: global pattern {1}" -f $t.Name, $p) }
    }
    $j = $raw | ConvertFrom-Json
    foreach ($entry in $j.audit) {
        $hay = ($entry.detail | ConvertTo-Json -Compress -Depth 8) + " " + $entry.actor_id
        foreach ($p in $auditForbidden) {
            if ($hay.Contains($p)) { $hits += ("{0}: audit pattern {1} (seq=" -f $t.Name, $p) + $entry.seq + ")" }
        }
        if ([regex]::IsMatch($hay, "R-\d{4}")) { $hits += ("{0}: raw record id in audit seq=" -f $t.Name) + $entry.seq }
    }
}
Write-Host ("trace files scanned = " + $traces.Count)
if ($hits.Count -eq 0) {
    Write-Host "redaction scan      = OK (0 hits)"
} else {
    Write-Host "redaction scan      = FAILED"
    $hits | ForEach-Object { Write-Host ("  HIT: " + $_) }
    $failed = $true
}

# ---------- 5/7 Pi 侧（Node）类型检查 + 离线测试 ----------
Write-Section "[5/7] Pi runtime offline: tsc type-check + node:test"
$piReady = (Test-Path $tsx) -and (Test-Path $tsc)
if (-not $piReady) {
    Section-Fail "runtime tools missing; run npm ci in pi-agent-runtime before offline verification"
} else {
    $tscOut = Join-Path $target "verify-tsc.log"
    $tscProc = Start-Process -FilePath $tsc -ArgumentList @("--noEmit", "-p", $tsconfig) -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $tscOut -RedirectStandardError (Join-Path $target "verify-tsc-err.log")
    if ($tscProc.ExitCode -ne 0) {
        Get-Content $tscOut -Tail 20 | ForEach-Object { Write-Host ("  " + $_) }
        Section-Fail "tsc type-check failed (exit=$($tscProc.ExitCode))"
    } else {
        Write-Host "RESULT: tsc type-check passed (exit=0)"
    }

    $testFiles = @("tools-contract.test.ts", "idem.test.ts", "trace-redaction.test.ts", "runtime-offline.test.ts") |
        ForEach-Object { Join-Path $runtime ("test\" + $_) }
    $nodeLog = Join-Path $target "verify-node-test.log"
    $nodeProc = Start-Process -FilePath $tsx -ArgumentList (@("--tsconfig", $tsconfig, "--test") + $testFiles) -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $nodeLog -RedirectStandardError (Join-Path $target "verify-node-test-err.log")
    if (Test-Path $nodeLog) {
        $countLine = [regex]::Match((Get-Content $nodeLog -Raw), "tests (\d+)")
        if ($countLine.Success) { Write-Host ("  node tests run = " + $countLine.Groups[1].Value) }
    }
    if ($nodeProc.ExitCode -ne 0) {
        Get-Content $nodeLog -Tail 30 | ForEach-Object { Write-Host ("  " + $_) }
        Section-Fail "Pi runtime offline tests failed (exit=$($nodeProc.ExitCode))"
    } else {
        Write-Host "RESULT: node:test passed (exit=0)"
    }
}

# ---------- 6/7 跨进程 E2E 演示 ----------
Write-Section "[6/7] Cross-process E2E demo (fresh java-flow-service + Pi CLI)"
Write-Host "(Fake Model / official Faux provider; offline; no real model calls; no real Feishu)"
$javaUrl = "http://127.0.0.1:8081"
$opsHeaders = @{ "X-Demo-Token" = "token-op-1" }
function Test-StatsUp() {
    try {
        $r = Invoke-WebRequest -Uri "$javaUrl/api/flow/stats" -Headers $opsHeaders -TimeoutSec 2 -UseBasicParsing
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

if (-not $piReady) {
    Section-Fail "E2E skipped: Pi runtime not runnable (tsx missing)"
} elseif (Test-StatsUp) {
    Section-Fail "port 8081 already serving; stop the running instance first (fresh boot required: fault counters are single-use)"
} else {
    Push-Location $svc
    try {
        if (-not (Test-Path "target\cp.txt")) {
            cmd /c "mvn -o -q compile dependency:build-classpath -Dmdep.outputFile=target\cp.txt > target\verify-cp.log 2>&1"
        }
    } finally {
        Pop-Location
    }
    $cp = (Get-Content (Join-Path $target "cp.txt") -Raw).Trim()
    $javaOut = Join-Path $target "verify-java-out.log"
    $javaErr = Join-Path $target "verify-java-err.log"
    $proc = Start-Process -FilePath "java" -ArgumentList @("-cp", ("target\classes;" + $cp), "com.demo.flowagent.FlowAgentApplication") -WorkingDirectory $svc -PassThru -WindowStyle Hidden -RedirectStandardOutput $javaOut -RedirectStandardError $javaErr
    try {
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            if (Test-StatsUp) { $ready = $true; break }
            Start-Sleep -Milliseconds 1000
        }
        if (-not $ready) {
            Get-Content $javaErr -Tail 20 | ForEach-Object { Write-Host ("  " + $_) }
            Section-Fail "java-flow-service did not become ready within 60s"
        } else {
            Write-Host "java-flow-service ready (fresh boot, fixtures seeded)"
            $e2eLog = Join-Path $target "verify-e2e.log"
            $cli = Join-Path $runtime "src\cli.ts"
            $cliProc = Start-Process -FilePath $tsx -ArgumentList @("--tsconfig", $tsconfig, $cli) -WorkingDirectory $root -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $e2eLog -RedirectStandardError (Join-Path $target "verify-e2e-err.log")
            Get-Content $e2eLog -Tail 16 -Encoding UTF8 | ForEach-Object { Write-Host ("  " + $_) }
            if ($cliProc.ExitCode -ne 0) {
                Section-Fail "E2E demo failed (exit=$($cliProc.ExitCode); full log: target\verify-e2e.log)"
            } else {
                $demo = Get-Content (Join-Path $runtime "traces\demo-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
                $acceptance = [ordered]@{
                    run_count            = 6
                    total_write_count    = 3
                    total_skip_count     = 2
                    apply_endpoint_hits  = 6
                    replay_endpoint_hits = 3
                }
                foreach ($key in $acceptance.Keys) {
                    $actual = $demo.stats_final.$key
                    if ($actual -ne $acceptance[$key]) {
                        Section-Fail ("acceptance " + $key + ": expected " + $acceptance[$key] + " actual " + $actual)
                    }
                }
                $st = $demo.stats_final.runs_by_status
                if ($st.SUCCEEDED -ne 4 -or $st.REJECTED -ne 1 -or $st.RECEIVED -ne 1) {
                    Section-Fail ("runs_by_status mismatch: " + ($st | ConvertTo-Json -Compress))
                }
                if ($demo.result -ne "PASSED") { Section-Fail "demo summary result = $($demo.result)" }
                Write-Host ("RESULT: E2E acceptance numbers checked against demo-summary.json (result=" + $demo.result + ")")
            }
        }
    } finally {
        if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        for ($i = 0; $i -lt 15; $i++) { if (-not (Test-StatsUp)) { break }; Start-Sleep -Milliseconds 1000 }
        Write-Host "java-flow-service stopped; port 8081 released"
    }
}

# ---------- 7/7 Pi 侧轨迹 + E2E 日志独立二次脱敏扫描 ----------
Write-Section "[7/7] Independent redaction re-scan of Pi-side traces and E2E log"
$piTraceDir = Join-Path $runtime "traces"
# 轨迹禁项（与 Node 侧 Trace.write 的 fail-closed 扫描同一口径，独立复检）
$piTraceForbidden = @("token-op-1", "token-ap-1", "token-agent-1", "Bearer ", "Authorization:", "canary", "机密备注", "LZL04", "BA99", "HACK01")
# 日志只禁凭据类（脱敏业务值 LZL04/BA99 是演示载荷，允许出现在控制台日志）
$logForbidden = @("token-op-1", "token-ap-1", "token-agent-1", "Bearer ", "Authorization:")
$piHits = @()
$piTraces = @()
if (Test-Path $piTraceDir) { $piTraces = Get-ChildItem $piTraceDir -Filter "*.json" }
foreach ($t in $piTraces) {
    $raw = Get-Content $t.FullName -Raw -Encoding UTF8
    foreach ($p in $piTraceForbidden) {
        if ($raw.Contains($p)) { $piHits += ("{0}: pattern {1}" -f $t.Name, $p) }
    }
    if ([regex]::IsMatch($raw, "R-\d{4}")) { $piHits += ("{0}: raw record id pattern" -f $t.Name) }
}
$e2eLog = Join-Path $target "verify-e2e.log"
if (Test-Path $e2eLog) {
    $raw = Get-Content $e2eLog -Raw -Encoding UTF8
    foreach ($p in $logForbidden) {
        if ($raw.Contains($p)) { $piHits += ("verify-e2e.log: credential pattern {0}" -f $p) }
    }
}
Write-Host ("pi trace files scanned = " + $piTraces.Count)
if ($piTraces.Count -eq 0) {
    Section-Fail "no Pi traces found (E2E demo must have run in step 6)"
} elseif ($piHits.Count -eq 0) {
    Write-Host "redaction scan      = OK (0 hits)"
} else {
    Write-Host "redaction scan      = FAILED"
    $piHits | ForEach-Object { Write-Host ("  HIT: " + $_) }
    $failed = $true
}

# ---------- 边界声明 ----------
Write-Host ""
Write-Host "NOTE: E2E 由官方 Faux provider（脚本化 Fake Model）+ 本地 H2 fixtures 离线驱动；"
Write-Host "      未接真实飞书、未做真实模型线上验证；Fake Model 通过不代表真实模型质量。"

if ($failed) {
    Write-Host ""
    Write-Host "VERIFY RESULT: FAILED"
    exit 1
}
Write-Host ""
Write-Host "VERIFY RESULT: PASSED (offline: Java tests + Pi offline tests + cross-process E2E + redaction scans)"
exit 0
