# 准备独立验证库和测试连接，再运行 SQL Server 集成测试。
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runtimeRoot = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$configFile = Join-Path $runtimeRoot 'sqlserver-test-settings.json'
$databaseName = 'course_selection_validation'

# 此库专用于验证，业务测试会清理其中的业务表，不读取已部署选课库。
if (-not (Test-Path -LiteralPath $configFile)) {
    $bytes = New-Object byte[] 24
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    @{ username = 'course_selection_test_' + [Guid]::NewGuid().ToString('N').Substring(0,8); password = 'TestDb!' + [Convert]::ToBase64String($bytes) } |
        ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding UTF8
}
$settings = Get-Content -LiteralPath $configFile -Raw -Encoding UTF8 | ConvertFrom-Json
# 先在独立验证库应用与部署相同的结构，测试再使用独立连接覆盖真实 SQL Server 方言与事务行为。
$schemaFile = Join-Path $projectRoot 'backend\src\main\resources\db\schema-sqlserver.sql'
& sqlcmd -S 'tcp:localhost,1433' -E -C -l 10 -b -f 65001 -v "DatabaseName=$databaseName" -i $schemaFile
if ($LASTEXITCODE -ne 0) { throw '独立验证库创建失败。' }
$migrationRoot = Join-Path (Split-Path $schemaFile -Parent) 'migrations'
Get-ChildItem -LiteralPath $migrationRoot -Filter '*.sql' | Sort-Object Name | ForEach-Object {
    & sqlcmd -S 'tcp:localhost,1433' -E -C -l 10 -b -f 65001 -v "DatabaseName=$databaseName" -i $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "独立验证库迁移失败：$($_.Name)" }
}

$connection = New-Object System.Data.SqlClient.SqlConnection("Server=localhost,1433;Database=$databaseName;Integrated Security=True;Encrypt=True;TrustServerCertificate=True")
try {
    $connection.Open()
    $command = $connection.CreateCommand()
    $command.CommandText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'provision-login.sql'))
    [void]$command.Parameters.Add('@login', [Data.SqlDbType]::NVarChar, 128)
    $command.Parameters['@login'].Value = $settings.username
    [void]$command.Parameters.Add('@password', [Data.SqlDbType]::NVarChar, 128)
    $command.Parameters['@password'].Value = $settings.password
    [void]$command.ExecuteNonQuery()
} finally { $connection.Dispose() }

$values = @{
    RUN_SQLSERVER_TESTS = 'true'
    SQLSERVER_TEST_URL = "jdbc:sqlserver://localhost:1433;databaseName=$databaseName;encrypt=true;trustServerCertificate=true;lockTimeout=10000"
    SQLSERVER_TEST_USERNAME = $settings.username
    SQLSERVER_TEST_PASSWORD = $settings.password
}
# 在启动测试前暂存相关环境变量，测试结束后恢复。
$previous = @{}
Push-Location (Join-Path $projectRoot 'backend')
try {
    foreach ($name in $values.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
    }
    & .\mvnw.cmd -B -ntp test '-Dtest=SqlServer*IntegrationTest'
    if ($LASTEXITCODE -ne 0) { throw 'SQL Server 业务验证失败，请查看测试报告。' }
    Copy-Item -LiteralPath 'target\surefire-reports\TEST-cn.edu.cczu.course.SqlServerAcademicIntegrationTest.xml' -Destination (Join-Path $runtimeRoot 'sqlserver-business-tests.xml') -Force
    Copy-Item -LiteralPath 'target\surefire-reports\TEST-cn.edu.cczu.course.SqlServerLearningIntegrationTest.xml' -Destination (Join-Path $runtimeRoot 'sqlserver-learning-tests.xml') -Force
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
