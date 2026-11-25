# 构建前端和后端，整理程序、SQL 脚本与启动入口到 Windows 部署目录。
param([switch]$SkipInstall, [switch]$SkipTests)

$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    # 命令行直接运行时，从 PATH 上的 JDK 为 Maven Wrapper 推导 JAVA_HOME。
    $java = (Get-Command java.exe).Source
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$deploymentRoot = Join-Path $projectRoot 'release\windows'
New-Item -ItemType Directory -Path $deploymentRoot -Force | Out-Null
if (Test-Path -LiteralPath (Join-Path $deploymentRoot 'app.jar')) {
    . (Join-Path $PSScriptRoot 'Common.ps1')
    if (Get-DeploymentProcess) {
        throw '部署中的应用正在运行，请先执行停止选课系统.cmd，再重新构建。'
    }
}

# 先生成前端 dist，后端打包步骤会把它作为静态文件一起放入应用 JAR。
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not $SkipInstall) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
} finally { Pop-Location }

# local-deploy 配置负责打包前端。手动构建通常同时运行测试，缺包自动构建可按调用方参数跳过。
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($SkipTests) { & .\mvnw.cmd -B -ntp clean package '-Plocal-deploy' '-DskipTests' }
    else { & .\mvnw.cmd -B -ntp clean verify '-Plocal-deploy' }
    if ($LASTEXITCODE -ne 0) { throw '后端测试或部署包构建失败。' }
} finally { Pop-Location }

# 将重打包后的可执行 JAR 放到部署目录，再把日常启停、数据库脚本和验收工具集中到同一目录。
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\target\course-selection-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $deploymentRoot 'app.jar') -Force
New-Item -ItemType Directory -Path (Join-Path $deploymentRoot 'tools'), (Join-Path $deploymentRoot 'sql'), (Join-Path $deploymentRoot 'logs') -Force | Out-Null
Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' |
    Where-Object Name -ne 'Run-SqlServerTests.ps1' |
    Copy-Item -Destination (Join-Path $deploymentRoot 'tools') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\src\main\resources\db\schema-sqlserver.sql') -Destination (Join-Path $deploymentRoot 'sql\schema.sql') -Force
New-Item -ItemType Directory -Path (Join-Path $deploymentRoot 'sql\migrations') -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend\src\main\resources\db\migrations') -Filter '*.sql' | Copy-Item -Destination (Join-Path $deploymentRoot 'sql\migrations') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'provision-login.sql') -Destination (Join-Path $deploymentRoot 'sql\provision-login.sql') -Force

# 已有部署配置继续沿用，首次打包时生成本地设置。
$settingsPath = Join-Path $deploymentRoot 'deployment.json'
if (-not (Test-Path -LiteralPath $settingsPath)) {
    $randomBytes = New-Object byte[] 18
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
    $password = 'Cczu!' + [Convert]::ToBase64String($randomBytes)
    @{
        hostName = 'localhost'; databasePort = 1433; databaseName = 'course_selection'
        webPort = 8081; seedSampleData = $true; samplePassword = $password
        localAccountSwitchEnabled = $true
    } | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding UTF8
    @"
学生选课系统本机部署账号
访问地址：http://127.0.0.1:8081
管理员：admin
学生：20260001
教师：T2026001
以上三个初始账号使用同一密码：$password

此文件仅供本机使用，不要提交到代码仓库或分享部署目录。
启动脚本不会重置已有账号密码。
"@ | Set-Content -LiteralPath (Join-Path $deploymentRoot '账号信息.txt') -Encoding UTF8
}

$settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $settings.PSObject.Properties['databaseUserName']) {
    $randomBytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
    $settings | Add-Member -NotePropertyName databaseUserName -NotePropertyValue ('course_selection_app_' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    $settings | Add-Member -NotePropertyName databasePassword -NotePropertyValue ('Db!' + [Convert]::ToBase64String($randomBytes))
    $settings | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding UTF8
}

# 包含凭据的本机配置只向当前用户、系统和本机管理员开放。
$ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
foreach ($privatePath in @($settingsPath, (Join-Path $deploymentRoot '账号信息.txt'))) {
    # 仅更新 DACL，避免 Set-Acl 在重复构建时要求审计权限。
    & icacls.exe $privatePath '/inheritance:r' '/grant:r' ("*$($ownerSid.Value):(F)") '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '本机凭据文件访问权限设置失败。' }
}

$utf8Bom = New-Object Text.UTF8Encoding($true)
Get-ChildItem -LiteralPath (Join-Path $deploymentRoot 'tools') -Filter '*.ps1' | ForEach-Object {
    [IO.File]::WriteAllText($_.FullName, [IO.File]::ReadAllText($_.FullName), $utf8Bom)
}

# 将常用 PowerShell 操作封装为可双击的 CMD 启动入口。
$launchers = @{
    'start.cmd' = 'Start-System.ps1 -OpenBrowser'
    'stop.cmd' = 'Stop-System.ps1'
    'check.cmd' = 'Test-Deployment.ps1'
    'backup.cmd' = 'Backup-Database.ps1'
    'initialize.cmd' = 'Initialize-Database.ps1'
}
foreach ($entry in $launchers.GetEnumerator()) {
    $parts = $entry.Value.Split(' ', 2)
    $extraArguments = if ($parts.Length -gt 1) { ' ' + $parts[1] } else { '' }
    $content = "@echo off`r`nchcp 65001 >nul`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0tools\$($parts[0])`"$extraArguments`r`nif errorlevel 1 pause`r`n"
    [IO.File]::WriteAllText((Join-Path $deploymentRoot $entry.Key), $content, [Text.UTF8Encoding]::new($false))
}
Write-Host "部署包已生成：$deploymentRoot"
