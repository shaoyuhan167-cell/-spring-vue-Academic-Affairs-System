# 读取部署进程记录，仅停止当前部署对应的后台进程。
. (Join-Path $PSScriptRoot 'Common.ps1')
Invoke-DeploymentLifecycle {
    $process = Get-DeploymentProcess
    # 先由共用函数核对 Java 进程和部署包，再发出停止命令并等待退出，避免端口尚未释放就报告成功。
    if ($process) {
        Stop-Process -InputObject $process
        if (-not $process.WaitForExit(10000)) { throw '等待选课系统退出超时。' }
        Write-Host '学生选课系统已停止。'
    } else {
        Write-Host '学生选课系统当前未运行。'
    }
    # 在持有同一部署锁时清理进程记录。下次启动会为新实例写入新的记录。
    $recordPath = Join-Path (Get-DeploymentRoot) 'logs\process.json'
    if (Test-Path -LiteralPath $recordPath) { Remove-Item -LiteralPath $recordPath }
}
