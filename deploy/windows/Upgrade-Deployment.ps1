# 为部署包更新程序与数据库结构，用升级前后的数据摘要检查业务记录。
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$deploymentRoot = Get-DeploymentRoot
$rollbackRoot = Join-Path $projectRoot '.runtime\rollback'
New-Item -ItemType Directory -Path $rollbackRoot -Force | Out-Null

# 提取关键业务数据和表统计，供升级前后比较。
function Read-BusinessSignature {
    $connection = New-Object System.Data.SqlClient.SqlConnection('Server=localhost,1433;Database=course_selection;Integrated Security=True;Encrypt=True;TrustServerCertificate=True')
    # 升级前后同时比较主业务表的行数和内容摘要。即使行数相同，也可以发现字段值变化。
    $rows = @()
    try {
        $connection.Open()
        $command = $connection.CreateCommand()
        $tables = @('sys_user','student','teacher','course','semester','teaching_class','course_schedule','enrollment')
        $parts = foreach ($table in $tables) { "SELECT '$table' AS table_name, COUNT_BIG(*) AS record_count, CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS fingerprint FROM dbo.[$table]" }
        $command.CommandText = $parts -join ' UNION ALL '
        $reader = $command.ExecuteReader()
        while ($reader.Read()) {
            $rows += [pscustomobject]@{ table = $reader.GetString(0); count = $reader.GetInt64(1); fingerprint = if ($reader.IsDBNull(2)) { $null } else { $reader.GetInt32(2) } }
        }
        $reader.Close()
    } finally { $connection.Dispose() }
    return ($rows | ConvertTo-Json -Compress)
}

# 更新前备份数据库与旧程序，正常更新不重新初始化业务表。
$before = Read-BusinessSignature
$before | Set-Content -LiteralPath (Join-Path $rollbackRoot 'business-before.json') -Encoding UTF8
& (Join-Path $PSScriptRoot 'Backup-Database.ps1')
Copy-Item -LiteralPath (Join-Path $deploymentRoot 'app.jar') -Destination (Join-Path $rollbackRoot 'app-before-business.jar') -Force
& (Join-Path $PSScriptRoot 'Stop-System.ps1')
try {
    & (Join-Path $PSScriptRoot 'Build-Deployment.ps1') -SkipInstall -SkipTests:$SkipTests
    # 仅创建扩展表；不清空或重建已有业务数据。
    $migrationRoot = Join-Path $deploymentRoot 'sql\migrations'
    Get-ChildItem -LiteralPath $migrationRoot -Filter '*.sql' | Sort-Object Name | ForEach-Object {
        Invoke-LocalSql -InputFile $_.FullName
    }
    & (Join-Path $PSScriptRoot 'Start-System.ps1')
    # 迁移完成后先比对数据再继续启动。发现已有记录变化时中止升级流程并报告问题。
    $after = Read-BusinessSignature
    if ($before -ne $after) { throw '更新期间业务数据发生变化，请检查更新日志与数据库；未自动覆盖数据。' }
    @{ checkedAt = (Get-Date).ToString('o'); dataPreserved = $true; before = ($before | ConvertFrom-Json); after = ($after | ConvertFrom-Json) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $deploymentRoot 'logs\upgrade-verification.json') -Encoding UTF8
} catch {
    $failure = $_
    & (Join-Path $PSScriptRoot 'Stop-System.ps1')
    Copy-Item -LiteralPath (Join-Path $rollbackRoot 'app-before-business.jar') -Destination (Join-Path $deploymentRoot 'app.jar') -Force
    & (Join-Path $PSScriptRoot 'Start-System.ps1')
    throw $failure
}
Write-Host '应用已更新，八张业务表的数据校验一致。'
