# 先创建选课数据库，再运行迁移并配置应用使用的数据库账号。
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-SqlServerRunning
# 先按配置的数据库名准备基础表，再按版本顺序补充学习功能、索引和审计表。
$schemaPath = Join-Path (Get-DeploymentRoot) 'sql\schema.sql'
Invoke-LocalSql -InputFile $schemaPath
# 按文件顺序应用迁移，逐步补齐业务表和索引。
$migrationRoot = Join-Path (Get-DeploymentRoot) 'sql\migrations'
Get-ChildItem -LiteralPath $migrationRoot -Filter '*.sql' | Sort-Object Name | ForEach-Object {
    Invoke-LocalSql -InputFile $_.FullName
}
$settings = Read-DeploymentSettings

# 建库使用当前 Windows 身份；应用运行只授予本项目数据库的读写权限。
$builder = New-Object System.Data.SqlClient.SqlConnectionStringBuilder
$builder['Data Source'] = "$($settings.hostName),$($settings.databasePort)"
$builder['Initial Catalog'] = $settings.databaseName
$builder['Integrated Security'] = $true
$builder['Encrypt'] = $true
$builder['TrustServerCertificate'] = $true
$connection = New-Object System.Data.SqlClient.SqlConnection($builder.ConnectionString)
try {
    $connection.Open()
    $command = $connection.CreateCommand()
    # 应用的数据库登录通过参数传递给授权脚本。创建实例登录和业务库权限由 SQL Server 在这条连接中完成。
    $command.CommandText = [IO.File]::ReadAllText((Join-Path (Get-DeploymentRoot) 'sql\provision-login.sql'))
    [void]$command.Parameters.Add('@login', [Data.SqlDbType]::NVarChar, 128)
    $command.Parameters['@login'].Value = $settings.databaseUserName
    [void]$command.Parameters.Add('@password', [Data.SqlDbType]::NVarChar, 128)
    $command.Parameters['@password'].Value = $settings.databasePassword
    [void]$command.ExecuteNonQuery()
} finally { $connection.Dispose() }
Write-Host 'SQL Server 数据库、业务表和增量迁移已准备完成。'
Write-Host '本项目专用数据库账号已准备完成，仅授予选课库读写权限。'
