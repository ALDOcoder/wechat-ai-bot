# ============================================================
# 一键启动 Java 逻辑端
# 自动从用户环境变量（注册表）读取 DEEPSEEK_API_KEY 和
# OBSIDIAN_VAULT_PATH，再启动 Spring Boot。这样无论从哪个
# 终端/窗口运行，都能拿到最新的环境变量，不用手动 setx。
# ============================================================

$key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$vault = [Environment]::GetEnvironmentVariable('OBSIDIAN_VAULT_PATH', 'User')

if ($key) {
    $env:DEEPSEEK_API_KEY = $key
    Write-Host "[OK] DEEPSEEK_API_KEY 已加载（长度 $($key.Length)）"
} else {
    Write-Host "[WARN] 未找到 DEEPSEEK_API_KEY，AI 调用会失败。请先配置用户环境变量。"
}

if ($vault) {
    $env:OBSIDIAN_VAULT_PATH = $vault
    Write-Host "[OK] OBSIDIAN_VAULT_PATH = $vault"
} else {
    Write-Host "[INFO] 未设置 OBSIDIAN_VAULT_PATH，Obsidian 知识问答将不启用。"
}

Write-Host "[INFO] 启动 Spring Boot（mvn spring-boot:run）..."
mvn spring-boot:run
