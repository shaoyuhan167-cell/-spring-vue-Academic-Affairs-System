# 启动 SQL Server 正式版并在页面就绪之后打开浏览器。
# OpenBrowser 控制就绪后是否打开默认浏览器。BuildIfMissing 允许项目根目录入口在缺少部署包时自动构建。
param([switch]$OpenBrowser, [switch]$BuildIfMissing)

. (Join-Path $PSScriptRoot 'Common.ps1')

Invoke-DeploymentLifecycle {
    $deploymentRoot = Get-DeploymentRoot
    # 部署包里已经包含 Vue 页面。现有 JAR 可以直接运行，缺包时才调用 Node 和 Maven。
    $jarPath = Join-Path $deploymentRoot 'app.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        if (-not $BuildIfMissing) { throw '缺少部署包，请先运行 Build-Deployment.ps1。' }
        Write-Host '未找到部署包，正在构建前端和后端。'
        $vitePath = Join-Path $PSScriptRoot '..\..\frontend\node_modules\.bin\vite.cmd'
        & (Join-Path $PSScriptRoot 'Build-Deployment.ps1') -SkipInstall:(Test-Path -LiteralPath $vitePath) -SkipTests
    }

    $settings = Read-DeploymentSettings
    $url = "http://127.0.0.1:$($settings.webPort)"
    # 先确认是否是本部署已经启动的进程。有记录的旧实例可以复用，其他程序占用端口时给出提示。
    $process = Get-DeploymentProcess
    $started = $false
    $logDirectory = Join-Path $deploymentRoot 'logs'
    $recordPath = Join-Path $logDirectory 'process.json'
    $outputFile = $logDirectory

    try {
        if (-not $process) {
            if (Get-NetTCPConnection -State Listen -LocalPort $settings.webPort -ErrorAction SilentlyContinue) {
                throw "端口 $($settings.webPort) 已被占用。请先停止占用该端口的程序，再启动选课系统。"
            }
            # 启动 Java 前先确认数据库可用。只有 SQL Server 未运行时才执行需要权限的启动辅助脚本。
            if ((Get-Service -Name MSSQLSERVER).Status -ne 'Running') {
                $helper = Join-Path $PSScriptRoot 'Enable-SqlServer.ps1'
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $helper -Elevate
                if ($LASTEXITCODE -ne 0) { throw 'SQL Server 启动失败，请检查管理员授权和服务状态。' }
            }
            Assert-SqlServerRunning

            $java = Resolve-Java
            New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
            # 每次启动各自留一份标准输出和错误日志，文件名精确到毫秒，方便分别排查连续启动的结果。
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
            $outputFile = Join-Path $logDirectory "application-$stamp.log"
            $errorFile = Join-Path $logDirectory "error-$stamp.log"
            $localAccountSwitch = $settings.localAccountSwitchEnabled -is [bool] -and $settings.localAccountSwitchEnabled

            # 将正式库连接和本机账号选择配置传给后台 Java 进程。
            $environmentValues = @{
                SPRING_PROFILES_ACTIVE = 'sqlserver'
                DB_HOST = $settings.hostName
                DB_PORT = [string]$settings.databasePort
                DB_NAME = $settings.databaseName
                DB_USERNAME = $settings.databaseUserName
                DB_PASSWORD = $settings.databasePassword
                DB_TRUST_CERTIFICATE = 'true'
                SERVER_PORT = [string]$settings.webPort
                APP_SAMPLE_DATA_ENABLED = ([bool]$settings.seedSampleData).ToString().ToLowerInvariant()
                APP_LOCAL_ACCOUNT_SWITCH_ENABLED = $localAccountSwitch.ToString().ToLowerInvariant()
                SAMPLE_DATA_PASSWORD = $settings.samplePassword
                APP_BOOTSTRAP_ADMIN_ENABLED = 'false'
            }
            # 本次部署设置只临时写入脚本进程的环境。新 Java 进程继承它们后，立即恢复脚本原值。
            $previousEnvironment = @{}
            try {
                foreach ($name in $environmentValues.Keys) {
                    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                    [Environment]::SetEnvironmentVariable($name, $environmentValues[$name], 'Process')
                }
                $javaArguments = @('-Xms128m', '-Xmx768m', '-jar', ('"' + $jarPath + '"'))
                $process = Start-Process -FilePath $java -ArgumentList $javaArguments -WorkingDirectory $deploymentRoot -WindowStyle Hidden -RedirectStandardOutput $outputFile -RedirectStandardError $errorFile -PassThru
                $started = $true
                # 进程编号与启动时间共同作为本实例的标识，停止脚本据此识别这一个进程。
                @{ processId = $process.Id; startTimeTicks = $process.StartTime.ToUniversalTime().Ticks } |
                    ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding UTF8
            } finally {
                foreach ($name in $previousEnvironment.Keys) {
                    [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
                }
            }
        }

        # 新启动和重复启动都先确认 SQL Server 接口与前端页面可用。
        # 轮询等待后端报告 SQL Server 已连通，再请求登录页确认打包进 JAR 的前端也能访问。
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $ready = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) { throw "选课系统进程已退出，请查看日志：$outputFile" }
            try {
                $health = Invoke-RestMethod -Uri "$url/api/system/health" -TimeoutSec 3
                if ($health.code -eq 0 -and $health.data.status -eq 'UP' -and $health.data.database -eq 'Microsoft SQL Server') {
                    $ready = $true
                    break
                }
            } catch { }
            Start-Sleep -Milliseconds 750
        }
        if (-not $ready) { throw "等待选课系统就绪超时，请查看日志：$outputFile" }
        $null = Invoke-WebRequest -Uri "$url/login" -TimeoutSec 5 -UseBasicParsing

        # 保留部署包首次初始化的行为，日常启动直接读取已有数据。
        if ($started -and $settings.seedSampleData) {
            $settings.seedSampleData = $false
            $settings | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $deploymentRoot 'deployment.json') -Encoding UTF8
        }
    # 启动或就绪检查出错时，只清理由本次调用新建的进程。复用的旧实例交由原进程记录继续管理。
    } catch {
        if ($started) {
            if (-not $process.HasExited) { Stop-Process -InputObject $process -ErrorAction SilentlyContinue }
            if ($process.WaitForExit(5000) -and (Test-Path -LiteralPath $recordPath)) {
                Remove-Item -LiteralPath $recordPath -ErrorAction SilentlyContinue
            }
        }
        throw
    }

    if ($started) {
        Write-Host "系统已启动：$url"
        Write-Host "数据库：SQL Server / $($settings.databaseName)。日志：$outputFile"
    } else {
        Write-Host "系统已在运行：$url"
    }
    if ($OpenBrowser) {
        try { Start-Process $url }
        catch { Write-Warning "未能打开默认浏览器，请访问 $url" }
    }
}
