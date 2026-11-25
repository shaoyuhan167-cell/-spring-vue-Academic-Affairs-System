# 对当前部署使用的 SQL Server 业务库创建带时间戳的备份。
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-SqlServerRunning
$settings = Read-DeploymentSettings
$database = $settings.databaseName
# 备份名称含数据库名和当前时间，方便区分升级前后的备份文件。
$fileName = $database + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.bak'

# 使用 SQL Server 自己的默认备份目录，避免额外修改文件夹访问权限。
# SQL 在数据库服务器自身的备份目录生成文件，避免依赖调用终端的工作目录。
$query = @"
SET NOCOUNT ON;
DECLARE @folder nvarchar(4000) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(4000));
IF @folder IS NULL THROW 51001, N'未找到 SQL Server 默认备份目录。', 1;
IF RIGHT(@folder, 1) <> N'\' SET @folder = @folder + N'\';
DECLARE @path nvarchar(4000) = @folder + N'$fileName';
BACKUP DATABASE [$database] TO DISK = @path WITH COPY_ONLY, CHECKSUM;
RESTORE VERIFYONLY FROM DISK = @path WITH CHECKSUM;
PRINT @path;
"@
Invoke-LocalSql -Query $query
Write-Host '数据库备份及可读性检查已完成，备份文件路径见上方。'
