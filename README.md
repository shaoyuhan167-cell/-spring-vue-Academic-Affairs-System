# 学生选课系统

基于 Spring Boot 与 Vue 的学生选课及教学管理系统，支持 SQL Server 本机部署。

## 本机 SQL Server 部署

已提供 Windows 笔记本部署方案：Vue 静态页面与 Spring Boot 打包为单个应用，连接现有 SQL Server，无需单独运行前端开发服务器。

完成本机数据库初始化后，双击根目录的 `启动选课系统.cmd`。系统就绪时会自动打开浏览器，默认地址为 `http://127.0.0.1:8081`。使用完毕后，双击 `停止选课系统.cmd` 退出后台程序。这两个入口可以直接从资源管理器运行。初始账号信息和首次部署步骤见 [Windows 部署说明](docs/Windows部署说明.md)。

## 当前已实现

- Spring Boot 分层结构：Controller → Service → MyBatis Mapper。
- BCrypt 密码验证、Session 登录、退出、当前身份恢复、角色权限、CSRF 防护。
- 学生、教师、管理员三种角色的前端路由与导航。
- 学生、教师档案新增与编辑、分页搜索、启停和密码重置。
- 基础课程、学期与选课窗口管理。
- 教学班排课、容量、发布、关闭及教师和教室冲突校验。
- 学生选课、退课、重复课程和学分限制、容量与课表冲突校验。
- 个人周课表、选退课历史、教师本人教学班和学生名单。
- 修改本人密码、停用或改密后的旧 Session 失效。
- 成绩中心：教师批量录分、权重计算、草稿、发布和撤回，学生查询已发布成绩及学分汇总。
- 个人联系方式和简介维护；按角色、时间展示公告，支持置顶和已读状态。
- 学生、教师、管理员按权限导出 CSV，可使用 Excel 打开。
- 统一 API 响应、参数校验和全局异常处理。
- 十二张业务表，业务数据持久化到 SQL Server。

操作流程见 [用户使用说明](docs/用户使用说明.md)，扩展功能与验证范围见 [功能完善说明](docs/功能完善说明.md)。批量导入、单双周与节假日调课属于后续扩展。

## 环境

- JDK 21。
- Node.js 20.19+ 或 22.12+；当前项目已在 Node.js 24 下验证。
- Maven 3.9.9：项目自带 Maven Wrapper，首次运行需要网络下载。
- SQL Server 2022 本机实例。

依赖版本锁定在 `backend/pom.xml` 与 `frontend/package-lock.json`。

## 在 IntelliJ IDEA 中启动正式版

运行 `CourseSelectionApplication.main()` 即可启动正式版。应用会读取本机部署配置 `release/windows/deployment.json`，连接 SQL Server 中已有的选课数据库，然后用默认浏览器打开登录页。SQL Server 尚未运行时，按提示确认 Windows 管理员授权。

本机部署默认启用免密登录。登录页可以选择学生、教师或管理员身份。

前端和 API 使用同一端口，默认是 `http://127.0.0.1:8081`。应用会使用 `frontend/dist` 中的页面，首次缺少构建产物时会自动构建。修改前端源码后，可在 `frontend` 中运行 `npm run build` 更新页面。首次使用且尚未准备正式库时，按 [Windows 部署说明](docs/Windows部署说明.md) 完成构建和数据库初始化。

## 构建与验证

在项目根目录运行以下命令，构建包含前端的正式部署包。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Build-Deployment.ps1
```

构建时运行 Maven 自动化回归。H2 依赖和测试数据库资源位于测试范围。正式部署包通过 SQL Server 提供业务数据。

## 项目文档

- [系统架构](docs/系统架构.md)
- [API 接口与后续规划](docs/API接口.md)
- [数据库设计](docs/数据库设计.md)

基础档案、课程、开课、选退课、课表和教师名单已接通。后续可按实习要求补充性能评估、备份恢复演练与答辩材料。
