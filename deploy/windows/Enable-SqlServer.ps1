# 尝试启动指定的 SQL Server 服务，并记录执行结果。
param([string]$ServiceName = 'MSSQLSERVER', [switch]$Elevate)

$ErrorActionPreference = 'Stop'
if ($Elevate) {
    # 从 IDE 启动时先检查服务，仅在需要启动且当前权限不足时请求 UAC。
    if ((Get-Service -Name $ServiceName).Status -eq 'Running') { return }
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        # 需要管理员权限时通过 UAC 启动同一份脚本。新进程不再带 Elevate 开关，因此不会反复申请权限。
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-ServiceName', $ServiceName)
        $helper = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
        exit $helper.ExitCode
    }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$packageRoot = Split-Path $PSScriptRoot -Parent
# 运行结果与调用方式关联。部署包把结果写进日志目录，源码启动把结果写进项目运行目录。
$runtimeDirectory = if (Test-Path -LiteralPath (Join-Path $packageRoot 'app.jar')) {
    Join-Path $packageRoot 'logs'
} else {
    Join-Path $projectRoot '.runtime'
}
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$resultFile = Join-Path $runtimeDirectory 'sqlserver-service.json'

try {
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '启用数据库服务需要管理员权限。'
    }
    $service = Get-Service -Name $ServiceName -ErrorAction Stop
    if ($service.StartType -eq 'Disabled') {
        Set-Service -Name $ServiceName -StartupType Manual
    }
    if ($service.Status -ne 'Running') {
        Start-Service -Name $ServiceName
    }
    # 发出启动命令后等待服务真正进入运行态，再把结果交给启动程序判断。
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(45))
    @{ success = $true; service = $ServiceName; status = 'Running' } |
        ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding UTF8
} catch {
    @{ success = $false; service = $ServiceName; message = $_.Exception.Message } |
        ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding UTF8
    exit 1
}
