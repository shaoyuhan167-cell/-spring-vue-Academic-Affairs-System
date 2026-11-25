# 通过 HTTP 验证已部署系统的静态资源、角色访问权限和常用接口。
. (Join-Path $PSScriptRoot 'Common.ps1')
$settings = Read-DeploymentSettings
$url = "http://127.0.0.1:$($settings.webPort)"
$results = New-Object 'System.Collections.Generic.List[string]'

# 逐项记录验证结果，遇到失败时停止并指出未通过的项目。
function Assert-Check {
    param([bool]$Condition, [string]$Description)
    if (-not $Condition) { throw "部署检查未通过：$Description" }
    $results.Add($Description)
    Write-Host "通过：$Description"
}

# 执行一条请求并核对返回的 HTTP 状态码。
function Assert-HttpStatus {
    param([string]$Path, [int]$ExpectedStatus, $Session)
    $status = 0
    try {
        $response = Invoke-WebRequest -Uri "$url$Path" -WebSession $Session -UseBasicParsing -ErrorAction Stop
        $status = [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        else { throw }
    }
    Assert-Check ($status -eq $ExpectedStatus) "$Path 返回 HTTP $ExpectedStatus"
}

# 为指定角色登录，得到后续检查复用的 Web 会话。
function New-LoginSession {
    param([string]$Username, [string]$Role)
    $csrf = Invoke-RestMethod -Uri "$url/api/auth/csrf" -SessionVariable session
    $headers = @{}
    $headers[$csrf.data.headerName] = $csrf.data.token
    $body = @{ username = $Username; password = $settings.samplePassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Uri "$url/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    Assert-Check ($login.code -eq 0 -and $login.data.role -eq $Role) "$Role 账号登录"
    $identity = Invoke-RestMethod -Uri "$url/api/auth/me" -WebSession $session
    Assert-Check ($identity.data.username -eq $Username) "$Role Session 身份恢复"
    return $session
}

# 先确认应用和数据库正常，再开始验证各角色操作。
$health = Invoke-RestMethod -Uri "$url/api/system/health"
Assert-Check ($health.code -eq 0 -and $health.data.status -eq 'UP' -and
    $health.data.database -eq 'Microsoft SQL Server') '连接 SQL Server 正式库'

# 用独立匿名会话检查公开页面与静态资源，再核对受限 API 对未登录请求的响应。
$anonymous = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$index = Invoke-WebRequest -Uri "$url/" -UseBasicParsing
Assert-Check ($index.StatusCode -eq 200 -and $index.Content.Contains('<div id="app"></div>')) '单个 Spring Boot 应用提供前端页面'
$assets = [regex]::Matches($index.Content, '(?:src|href)="(/assets/[^\"]+)"')
Assert-Check ($assets.Count -ge 2) 'HTML 引用前端构建资源'
foreach ($asset in $assets) {
    Assert-HttpStatus -Path $asset.Groups[1].Value -ExpectedStatus 200 -Session $anonymous
}
Assert-HttpStatus -Path '/dashboard' -ExpectedStatus 200 -Session $anonymous
Assert-HttpStatus -Path '/courses' -ExpectedStatus 200 -Session $anonymous
foreach ($route in @('/grades','/profile','/announcements','/announcement-management')) {
    Assert-HttpStatus -Path $route -ExpectedStatus 200 -Session $anonymous
}
Assert-HttpStatus -Path '/api/courses' -ExpectedStatus 401 -Session $anonymous

# 检查管理员能读取概览和课程，后续再分别检查学生和教师。
$admin = New-LoginSession -Username 'admin' -Role 'ADMIN'
$overview = Invoke-RestMethod -Uri "$url/api/system/overview" -WebSession $admin
Assert-Check ($overview.data.studentCount -ge 0 -and $overview.data.courseCount -ge 0) '管理员读取持久化统计数据'
$catalog = Invoke-RestMethod -Uri "$url/api/courses" -WebSession $admin
Assert-Check ($null -ne $catalog.data -and $catalog.data -is [Array]) '读取课程与教学班目录'
foreach ($resource in @('students','teachers','courses','semesters','classes','enrollments')) {
    $response = Invoke-RestMethod -Uri "$url/api/admin/$($resource)?page=1&size=5" -WebSession $admin
    Assert-Check ($response.code -eq 0 -and $response.data.page -eq 1 -and $response.data.size -eq 5 -and $response.data.total -ge 0) "管理员 $resource 分页接口"
}

# 逐个创建学生和教师的登录会话，按各角色核对课程、课表和教学班接口。
$student = New-LoginSession -Username '20260001' -Role 'STUDENT'
Assert-HttpStatus -Path '/api/grades/mine' -ExpectedStatus 200 -Session $student
Assert-HttpStatus -Path '/api/profile' -ExpectedStatus 200 -Session $student
Assert-HttpStatus -Path '/api/announcements' -ExpectedStatus 200 -Session $student
Assert-HttpStatus -Path '/api/exports/student/grades' -ExpectedStatus 200 -Session $student
Assert-HttpStatus -Path '/api/exports/admin/students' -ExpectedStatus 403 -Session $student
Assert-HttpStatus -Path '/api/system/overview' -ExpectedStatus 403 -Session $student
Assert-HttpStatus -Path '/api/admin/students' -ExpectedStatus 403 -Session $student
Assert-HttpStatus -Path '/api/enrollments' -ExpectedStatus 200 -Session $student
Assert-HttpStatus -Path '/api/timetable' -ExpectedStatus 200 -Session $student
$teacher = New-LoginSession -Username 'T2026001' -Role 'TEACHER'
Assert-HttpStatus -Path '/api/grades/classes' -ExpectedStatus 200 -Session $teacher
Assert-HttpStatus -Path '/api/admin/announcements' -ExpectedStatus 403 -Session $teacher
Assert-HttpStatus -Path '/api/system/overview' -ExpectedStatus 403 -Session $teacher
Assert-HttpStatus -Path '/api/teaching/me/classes' -ExpectedStatus 200 -Session $teacher
$teachingClasses = Invoke-RestMethod -Uri "$url/api/teaching/me/classes" -WebSession $teacher
if ($teachingClasses.data.Count -gt 0) {
    Assert-HttpStatus -Path "/api/teaching/classes/$($teachingClasses.data[0].id)/students" -ExpectedStatus 200 -Session $teacher
}

$csrf = Invoke-RestMethod -Uri "$url/api/auth/csrf" -WebSession $admin
$headers = @{}
$headers[$csrf.data.headerName] = $csrf.data.token
$null = Invoke-RestMethod -Uri "$url/api/auth/logout" -Method Post -WebSession $admin -Headers $headers
Assert-HttpStatus -Path '/api/auth/me' -ExpectedStatus 401 -Session $admin

@{ checkedAt = (Get-Date).ToString('o'); checks = $results.ToArray(); database = $settings.databaseName; url = $url } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path (Get-DeploymentRoot) 'logs\verification.json') -Encoding UTF8
Write-Host "部署检查通过，共 $($results.Count) 项。"
