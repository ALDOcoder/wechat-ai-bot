# ============================================================
# reset-vault-key.ps1 — 设置/更换知识库管理密钥（RAG_VAULT_KEY）并重启后端
#
# 用法：
#   .\reset-vault-key.ps1                    # 自动生成 20 位随机密钥
#   .\reset-vault-key.ps1 -Key "我的密钥"     # 使用指定密钥
#
# 流程：写入用户环境变量（注册表）→ 停掉 8080 上的 Java 进程
#       → 新窗口执行 start-java.ps1 → 等待服务就绪 → 验证门禁生效
# ============================================================
param([string]$Key = "")

$ErrorActionPreference = "Stop"

# 1. 密钥：未指定则自动生成 20 位随机串
if (-not $Key) {
    $chars = (48..57) + (65..90) + (97..122)
    $Key = -join ($chars | Get-Random -Count 20 | ForEach-Object { [char]$_ })
}

# 2. 写入用户环境变量（注册表）—— start-java.ps1 启动时会从这里加载
setx RAG_VAULT_KEY "$Key" | Out-Null
Write-Host "[OK] RAG_VAULT_KEY 已写入用户环境变量"
Write-Host ""
Write-Host "  你的管理密钥：$Key"
Write-Host ""
Write-Host "  （前端「知识库管理」锁屏输入它即可解锁）"

# 3. 停掉当前占用 8080 的 Java 进程（非 Java 进程不碰，防止误杀）
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -like "java*") {
        Write-Host "[INFO] 停止旧后端进程 java（PID $($proc.Id)）..."
        Stop-Process -Id $proc.Id -Force
        Start-Sleep -Seconds 2
    } else {
        $name = if ($proc) { $proc.ProcessName } else { "未知" }
        Write-Host "[WARN] 8080 被非 Java 进程占用（$name），为安全起见不自动结束。"
        Write-Host "       请手动处理后重新运行本脚本。"
        exit 1
    }
} else {
    Write-Host "[INFO] 8080 当前无监听进程，直接启动。"
}

# 4. 新窗口启动后端（start-java.ps1 会阻塞运行 mvn spring-boot:run，必须放独立窗口）
$startScript = Join-Path $PSScriptRoot "start-java.ps1"
if (-not (Test-Path $startScript)) {
    Write-Host "[ERROR] 找不到 $startScript"
    exit 1
}
Write-Host "[INFO] 新窗口启动后端 ..."
Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-File", "`"$startScript`""

# 5. 等待服务就绪（最长 90 秒）
Write-Host "[INFO] 等待服务启动（最长 90 秒）..."
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 3
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/health" -TimeoutSec 3
        if ($h.status -eq "ok") { $ready = $true; break }
    } catch {}
}
if (-not $ready) {
    Write-Host "[ERROR] 90 秒内未就绪，请到后端窗口查看启动日志。"
    exit 1
}
Write-Host "[OK] 后端已就绪"

# 6. 验证门禁：无令牌访问管理面应返回 401
try {
    Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/rag/status" -TimeoutSec 5 | Out-Null
    Write-Host "[WARN] 管理面未上锁（RAG_VAULT_KEY 可能未生效），请看后端窗口的 [WARN] 提示。"
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 401) {
        Write-Host "[OK] 门禁已生效（无令牌访问管理面返回 401）"
    } else {
        Write-Host "[WARN] 门禁校验返回 HTTP $code，请人工确认。"
    }
}

Write-Host ""
Write-Host "========== 完成 =========="
Write-Host " 管理密钥：$Key"
Write-Host " 在前端「知识库管理」锁屏输入它即可解锁。"
Write-Host "（建议把密钥抄到安全的地方，忘了就重跑本脚本）"
Write-Host "=========================="
