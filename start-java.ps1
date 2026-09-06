# ============================================================
# 一键启动 Java 逻辑端
# 自动从用户环境变量（注册表）读取 ZHIPU_API_KEY、DEEPSEEK_API_KEY、
# OBSIDIAN_VAULT_PATH 和 MySQL 账号（MYSQL_USER / MYSQL_PASSWORD），再启动 Spring Boot。这样无论从哪个
# 终端/窗口运行，都能拿到最新的环境变量，不用手动 setx。
# ============================================================

$key = [Environment]::GetEnvironmentVariable('ZHIPU_API_KEY', 'User')
$deepseekKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$vault = [Environment]::GetEnvironmentVariable('OBSIDIAN_VAULT_PATH', 'User')
$mysqlUser = [Environment]::GetEnvironmentVariable('MYSQL_USER', 'User')
$mysqlPass = [Environment]::GetEnvironmentVariable('MYSQL_PASSWORD', 'User')
$vaultKey = [Environment]::GetEnvironmentVariable('RAG_VAULT_KEY', 'User')

if ($key) {
    $env:ZHIPU_API_KEY = $key
    Write-Host "[OK] ZHIPU_API_KEY 已加载（长度 $($key.Length)）"
} else {
    Write-Host "[WARN] 未找到 ZHIPU_API_KEY，AI 调用会失败。请先配置用户环境变量。"
}

if ($deepseekKey) {
    $env:DEEPSEEK_API_KEY = $deepseekKey
    Write-Host "[OK] DEEPSEEK_API_KEY 已加载（长度 $($deepseekKey.Length)）"
} else {
    Write-Host "[WARN] 未找到 DEEPSEEK_API_KEY，DeepSeek 付费通道不可用（智谱免费通道不受影响）。"
}

if ($vault) {
    $env:OBSIDIAN_VAULT_PATH = $vault
    Write-Host "[OK] OBSIDIAN_VAULT_PATH = $vault"
} else {
    Write-Host "[INFO] 未设置 OBSIDIAN_VAULT_PATH，Obsidian 知识问答将不启用。"
}

if ($mysqlUser) {
    $env:MYSQL_USER = $mysqlUser
    Write-Host "[OK] MYSQL_USER = $mysqlUser"
} else {
    Write-Host "[WARN] 未设置 MYSQL_USER，数据库连接会失败。"
}
if ($mysqlPass) {
    $env:MYSQL_PASSWORD = $mysqlPass
    Write-Host "[OK] MYSQL_PASSWORD 已加载（长度 $($mysqlPass.Length)）"
} else {
    Write-Host "[WARN] 未设置 MYSQL_PASSWORD，数据库连接会失败。"
}

if ($vaultKey) {
    $env:RAG_VAULT_KEY = $vaultKey
    Write-Host "[OK] RAG_VAULT_KEY 已加载（知识库管理面门禁已启用）"
} else {
    Write-Host "[WARN] 未设置 RAG_VAULT_KEY，知识库管理面（/api/vault、/api/rag）【未上锁】。"
    Write-Host "       建议执行: setx RAG_VAULT_KEY \"你的管理密钥\" 后重新打开终端启动。"
}

Write-Host "[INFO] 启动 Spring Boot（mvn spring-boot:run）..."
mvn spring-boot:run
