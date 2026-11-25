# 统一处理 Windows 部署脚本共用的路径、设置、进程和数据库连接。
$ErrorActionPreference = 'Stop'

# 兼容从项目源码脚本和打包后的工具目录运行。
function Get-DeploymentRoot {
    # 源码脚本和部署包内脚本的位置不同。通过上级目录中的 app.jar 判断当前是否在部署包里运行。
    $parentDirectory = Split-Path $PSScriptRoot -Parent
    if (Test-Path -LiteralPath (Join-Path $parentDirectory 'app.jar')) {
        return $parentDirectory
    }
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\release\windows'))
}

# 同一部署的启动和停止串行执行，避免双击时覆盖进程记录。
function Invoke-DeploymentLifecycle {
    param([scriptblock]$Operation)

    # 把同一个部署路径映射到同一把锁，避免同时双击两个 CMD 时相互覆盖进程记录。
    $root = (Get-DeploymentRoot).ToUpperInvariant()
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $key = [Convert]::ToBase64String($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($root))).Replace('/', '_')
    } finally { $hash.Dispose() }
    $mutex = [Threading.Mutex]::new($false, "Local\CCZU-CourseSelection-$key")
    $locked = $false
    try {
        try { $locked = $mutex.WaitOne([TimeSpan]::FromMinutes(2)) }
        # 前一次脚本意外退出时，Windows 会把锁交给正在等待的脚本。随后再按进程记录核对实际运行状态。
        catch [Threading.AbandonedMutexException] { $locked = $true }
        if (-not $locked) { throw '另一个启动或停止操作仍在执行，请稍后重试。' }
        & $Operation
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

# 读取部署参数，供启动、验收和数据库工具使用。
function Read-DeploymentSettings {
    $settingsPath = Join-Path (Get-DeploymentRoot) 'deployment.json'
    if (-not (Test-Path -LiteralPath $settingsPath)) {
        throw '未找到部署配置，请先运行 Build-Deployment.ps1。'
    }
    $settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($settings.databaseName -notmatch '^[A-Za-z][A-Za-z0-9_]{0,62}$') {
        throw '数据库名仅支持英文字母、数字与下划线，且必须以字母开头。'
    }
    if ($settings.hostName -notin @('localhost', '127.0.0.1')) {
        throw '该部署包仅用于本机 SQL Server，不接受远程数据库地址。'
    }
    if ($settings.databaseUserName -notmatch '^course_selection_app_[a-f0-9]{8}$' -or
        [string]::IsNullOrWhiteSpace($settings.databasePassword)) {
        throw '专用数据库账号配置缺失，请重新构建部署包。'
    }
    if ($settings.webPort -lt 1024 -or $settings.webPort -gt 65535 -or
        $settings.databasePort -lt 1 -or $settings.databasePort -gt 65535) {
        throw '部署端口配置不正确。'
    }
    return $settings
}

# 从部署记录找出仍在运行的本项目进程。
function Get-DeploymentProcess {
    $deploymentRoot = Get-DeploymentRoot
    $pidPath = Join-Path $deploymentRoot 'logs\process.json'
    if (-not (Test-Path -LiteralPath $pidPath)) { return $null }
    # 系统重用进程编号时，启动时间会不同。先验证编号和开始时间，再核对 Java 的命令行确实包含本部署的 app.jar。
    $record = Get-Content -LiteralPath $pidPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if (-not $process -or $process.ProcessName -ne 'java') { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks -ne [long]$record.startTimeTicks) { return $null }
    $expectedJar = Join-Path $deploymentRoot 'app.jar'
    $command = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    if ($process.HasExited) { return $null }
    if (-not $command.CommandLine -or $command.CommandLine.IndexOf($expectedJar, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw '进程记录与正在运行的程序不匹配，已停止操作以避免影响其他程序。'
    }
    return $process
}

# 在继续执行依赖数据库的操作前，确认 SQL Server 已运行。
function Assert-SqlServerRunning {
    $service = Get-Service -Name MSSQLSERVER -ErrorAction Stop
    if ($service.Status -ne 'Running') {
        throw 'SQL Server 服务尚未启动。请运行启用 SQL Server 的管理员脚本后重试。'
    }
}

# 定位启动 Java 进程使用的 JDK。优先使用 JAVA_HOME 指定的 JDK，
# 避免 PATH 上存在版本更低的旧版 Java（如 JDK 17）时被误用，
# 导致运行 Java 21 编译的 app.jar 出现 UnsupportedClassVersionError。
function Resolve-Java {
    $javaHome = $env:JAVA_HOME
    if ($javaHome) {
        $candidate = Join-Path $javaHome 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    return (Get-Command java.exe -ErrorAction Stop).Source
}

# 使用本机 Windows 身份调用 sqlcmd，出错时中止当前操作。
function Invoke-LocalSql {
    param([string]$InputFile, [string]$Query, [string]$Database = 'master')
    $settings = Read-DeploymentSettings
    $arguments = @('-S', "tcp:$($settings.hostName),$($settings.databasePort)", '-E', '-C', '-l', '10', '-b', '-f', '65001', '-d', $Database)
    # 建表迁移从脚本文件加载，临时查询使用命令参数。两种调用都使用当前 Windows 身份连接。
    if ($InputFile) {
        $arguments += @('-v', "DatabaseName=$($settings.databaseName)", '-i', $InputFile)
    } else {
        $arguments += @('-Q', $Query)
    }
    & sqlcmd @arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'SQL Server 操作失败，请检查数据库服务与当前 Windows 账号的数据库权限。'
    }
}
