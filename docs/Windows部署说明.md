# Windows 笔记本部署（SQL Server）

## 运行结构

浏览器 → `http://127.0.0.1:8081` → Spring Boot 单个应用 → 本机 SQL Server 2022。

前端已经编译为静态文件并装入 JAR，部署运行不需要 Vite、Node.js 或 Nginx。构建时仍需 Node.js 与 Maven；运行时需要 JDK 21 和 SQL Server。

Web 应用只监听本机地址，不修改 Windows 防火墙。建库使用当前 Windows 账号，应用运行使用自动创建的专用 SQL 登录，只授予选课数据库的读写权限。随机生成的数据库密码保存在本机部署配置中。

## 日常使用

1. 双击项目根目录的 `启动选课系统.cmd`。
2. 系统就绪后会自动打开浏览器，默认地址为 `http://127.0.0.1:8081`。
3. 初始账号与随机生成的密码位于 `release/windows/账号信息.txt`。
4. 使用结束后，双击 `停止选课系统.cmd`。

根目录 CMD 自行定位部署目录。从其他工作目录运行时也可以使用。若部署包缺失，启动入口会先构建前后端，再使用本机部署配置连接 SQL Server。首次部署仍需完成下方的数据库初始化步骤。停止入口按启动时记录的进程退出后台应用。

管理员账号为 `admin`，样例学生为 `20260001`，样例教师为 `T2026001`。已有账号的密码不会因重新启动或构建而改变。

本机部署默认启用免密登录。在登录页点击「选择用户免密登录」，即可按学生、教师或管理员选择账号。登录后可用右上角的「切换身份」更换账号。免密入口仅接受本机回环连接。

SQL Server 若未运行，启动脚本会申请 Windows 管理员权限以启动现有服务；系统 UAC 窗口需要电脑使用者确认。数据库服务设置为手动启动，不自动修改为开机常驻。

## 首次构建与初始化

在项目根目录执行以下 PowerShell 命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Build-Deployment.ps1
```

若 SQL Server 服务尚未启动，以管理员身份执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Enable-SqlServer.ps1
```

然后执行：

```powershell
.\release\windows\initialize.cmd
.\release\windows\start.cmd
.\release\windows\check.cmd
```

初始化为独立的 `course_selection` 库建表，并按版本顺序应用扩展表、查询索引和审计表迁移。已有数据库通过项目标识校验，迁移可重复执行并保留业务数据。

首次启动显式写入三个样例账号与三门课程，成功后自动关闭样例初始化开关。后续启动读取 SQL Server 中的持久化数据。

## 部署包结构

```text
release/windows/
  app.jar                 包含前端的 Spring Boot 应用
  deployment.json         本机部署配置，含数据库与初始样例账号密码
  账号信息.txt             供电脑使用者查看的初始账号信息
  sql/schema.sql          SQL Server 专用建库建表脚本
  tools/                  启停、初始化、检查、备份脚本
  logs/                   运行日志、进程记录与检查结果
  start.cmd               启动并打开浏览器
  stop.cmd                停止本项目应用
  check.cmd               实际 HTTP 登录和权限检查
  backup.cmd              备份数据库并执行可读性检查
  initialize.cmd          首次建库建表
```

`release/` 已被 Git 忽略，其中含本机配置和初始密码，请勿直接分享整个目录。重新构建会保留配置与密码文件；更新运行中的系统时，应先停止应用、再重新构建、最后启动。

## 数据库设置

- 实例：本机默认实例 `MSSQLSERVER`，TCP 端口 1433。
- 数据库：`course_selection`。
- 驱动：Microsoft JDBC 12.10.2。
- 中文字段使用 `NVARCHAR`，自增键使用 `IDENTITY`，日期时间使用 `DATETIME2`。
- 初始化与备份：当前 Windows 用户；首次建库需要该用户拥有建库及创建应用登录的权限。
- 应用认证：专用 SQL 登录，仅属于选课数据库的 `db_datareader` 和 `db_datawriter` 角色，不授予服务器管理权限。
- `deployment.json` 与账号文件的访问权限限定为当前用户、系统和本机管理员。

本机 SQL Server 通常使用自签名 TLS 证书；启动脚本仅针对 `localhost` 数据库连接设置 `trustServerCertificate=true`，仍启用传输加密。改为远程服务器时应配置受信任证书并单独设计认证和访问权限。

Web 服务仅在回环地址上使用 HTTP，因此此配置的 Session Cookie 不设置 Secure；未来部署到公网 HTTPS 时需使用相应的安全配置。

## 备份与运行检查

双击 `release/windows/backup.cmd`，脚本将原生 `.bak` 文件写到 SQL Server 默认备份目录，并执行 `RESTORE VERIFYONLY ... WITH CHECKSUM`。这会验证备份文件的可读性，不会还原或覆盖数据库；它不能替代实际的恢复演练。

双击 `release/windows/check.cmd`，会验证：

- 应用连接 SQL Server 正式库。
- 前端页面、构建资源、直接访问和刷新路由可用。
- 匿名用户不能读取课程接口。
- 三种账号能够登录，Session 身份能恢复。
- 学生与教师无法读取管理员统计。
- 退出后原 Session 不再有效。

检查脚本使用首次初始化的样例账号。未来修改账号密码或移除样例账号后，应同步调整检查方式。

## 常见问题

- **端口被占用**：修改 `deployment.json` 中的 `webPort`，重启后按新端口访问。
- **连接数据库失败**：确认 `MSSQLSERVER` 已运行，TCP/IP 已启用且端口为 1433。
- **Windows 账号登录失败**：使用 SQL Server 管理工具授予当前 Windows 账号相应数据库访问权限；不要因此更改其他数据库的认证方式。
- **启动失败**：查看 `release/windows/logs/` 中最近的 `application-*.log` 和 `error-*.log`。

## 功能边界

当前版本已实现档案、课程、学期、开课排课、选退课、个人课表、教师名单和账号安全。使用流程见用户使用说明。成绩、资料、公告和 CSV 导出已完成，见 [功能完善说明](功能完善说明.md)。Excel 批量导入、单双周与节假日调课尚未实现。

## 独立业务验证

从项目根目录运行 `deploy/windows/Run-SqlServerTests.ps1`，会为 `course_selection_validation` 库应用迁移并执行 SQL Server 业务测试。回归测试清理该专用验证库并写入测试数据。

构建更新前先停止运行中的部署应用。已另行执行完整测试时，可向 Build-Deployment.ps1 传入 SkipTests 开关以仅打包；默认仍执行测试。

后续更新可从源码目录运行 deploy/windows/Upgrade-Deployment.ps1。该脚本先备份数据库和旧 JAR，再更新应用，最后比较八张业务表的记录数与校验值；更新失败时恢复旧 JAR，不自动覆盖数据库。
