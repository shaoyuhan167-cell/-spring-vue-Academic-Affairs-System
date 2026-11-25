# API 接口文档

## 基本约定

接口前缀为 /api，使用 UTF-8 JSON。私有请求根据服务端 Session 判断身份，写请求必须携带 GET /api/auth/csrf 返回的请求头与令牌。

成功响应：
~~~json
{"code":0,"message":"操作成功","data":{}}
~~~

HTTP 400 表示参数不合法，401 表示未登录或账号密码失效，403 表示权限不足或 CSRF 无效，404 表示数据不存在，409 表示业务冲突、重复编号、容量不足等。失败时 data 为 null，message 提供中文原因。

日期使用 YYYY-MM-DD；日期时间使用上海时区本地 ISO 格式，例如 2026-11-10T08:00:00。学分最多一位小数。角色为 ADMIN、STUDENT、TEACHER。

## 认证与公共查询

| 方法 | URL（省略 /api） | 权限 | 内容 |
|---|---|---|---|
| GET | /system/health | 公开 | 检查数据库连通性，返回 status、application、database |
| GET | /auth/csrf | 公开 | 返回 headerName、token |
| POST | /auth/login | 公开，需 CSRF | 请求 username、password；返回用户 id、username、displayName、role |
| GET | /auth/local-accounts | 本机免密已启用 | 返回功能状态及启用账号的 id、username、displayName、role |
| POST | /auth/local-login | 本机免密已启用，需 CSRF | 请求 `{"username":"20260001"}`，替换当前会话并返回所选用户 |
| GET | /auth/me | 已登录 | 恢复当前身份，不返回密码散列 |
| POST | /auth/logout | 需 CSRF | 销毁 Session |
| POST | /auth/password | 已登录 | 请求 currentPassword、newPassword；成功后原有 Session 全部失效 |
| GET | /semesters | 已登录 | 学期及选退课时间、学分上限，按日期倒序 |
| GET | /courses | 已登录 | 已发布且任课教师已启用的教学班目录 |
| GET | /system/overview | 管理员 | 学生、教师、课程、教学班数量 |

目录返回数组，每条包含 id（教学班 ID）、classCode、courseId、courseCode、courseName、credits、hours、teacherId、teacherName、semesterId、semesterName、capacity、status、selectedCount、selectionOpen 和 schedules。

上课安排包含 weekday（1～7）、startPeriod、endPeriod（1～12）、startWeek、endWeek（1～30）及 classroom。

## 管理员接口

以下 URL 位于 /api/admin 下，只允许管理员访问。

| 资源 | GET 列表 | POST 新增 | PUT 修改 | DELETE 删除 |
|---|---|---|---|---|
| 学生 | /students | /students | /students/{id} | 通过 enabled 停用，保留档案 |
| 教师 | /teachers | /teachers | /teachers/{id} | 通过 enabled 停用，保留档案 |
| 基础课程 | /courses | /courses | /courses/{id} | /courses/{id} |
| 学期 | /semesters | /semesters | /semesters/{id} | /semesters/{id} |
| 教学班 | /classes | /classes | /classes/{id} | /classes/{id} |
| 选退课记录 | /enrollments | 不提供 | 不提供 | 不提供 |

列表参数：page（默认 1）、size（默认 10，最多 100）、keyword。教学班和选课记录额外接受 semesterId；选课记录额外接受 status=ENROLLED 或 DROPPED。返回：

~~~json
{"records":[],"total":0,"page":1,"size":10}
~~~

其他接口：

- GET /api/admin/options：课程、已启用教师和学期选项。
- POST /api/admin/students/{id}/password：请求 newPassword，重置学生密码并使旧登录失效。
- POST /api/admin/teachers/{id}/password：重置教师密码。

### 写入字段

| 资源 | 请求字段 |
|---|---|
| 学生 | studentNo、displayName、major、className、enabled；新增提供 initialPassword |
| 教师 | teacherNo、displayName、department、enabled；新增提供 initialPassword |
| 课程 | courseCode、name、credits、hours、description |
| 学期 | name、startDate、endDate、selectionStart、selectionEnd、maxCredits |
| 教学班 | classCode、courseId、teacherId、semesterId、capacity、status、schedules |

学号、工号创建后不能修改。新密码至少 10 个字符，包含字母和数字，UTF-8 长度不超过 72 字节。

教学班状态为 DRAFT、PUBLISHED、CLOSED。发布前至少排一个时间段，任课教师须启用，且同学期教师和教室不能冲突。有选退课历史后只允许改容量与状态；容量不能小于有效选课人数，有有效选课时不能改回草稿。

基础课程被引用后，仅能改名称和简介。被引用的课程、学期不能删除；有选退课历史的教学班不能删除。

## 学生接口

| 方法 | URL | 请求与返回 |
|---|---|---|
| GET | /api/enrollments?semesterId=... | 本人记录，学期可省略；数组元素为 {info, schedules} |
| POST | /api/enrollments | 请求 {teachingClassId:123}，返回选课记录及上课安排 |
| POST | /api/enrollments/batch | 请求 `{"teachingClassIds":[123,124]}`，接受 1～20 个不同的教学班编号，按提交顺序返回选课结果 |
| DELETE | /api/enrollments/{id} | 退选本人课程，保留历史 |
| GET | /api/timetable?semesterId=...&week=... | 本人有效课程排课；周次可省略，取值 1～30 |

info 包含 id、studentId、studentNo、studentName、teachingClassId、classCode、courseId、courseName、credits、teacherName、semesterId、semesterName、status、selectedAt、droppedAt。

身份从 Session 获取，不能指定其他学生。校验发布状态、账号状态、选课时间、容量、同学期重复课程、总学分及星期/周次/节次冲突。选退课时间边界均包含在允许范围内。重新选课复用退课记录，并更新选课时间。

批量选课会比较同学期内的已选课程和本次待选课程。时间冲突返回 HTTP 409，消息包含两个教学班及重叠的星期、节次和周次。任一教学班校验失败时回滚本次请求中的全部选课。

## 教师接口

| 方法 | URL | 权限与内容 |
|---|---|---|
| GET | /api/teaching/me/classes?semesterId=... | 教师本人，返回授课教学班和人数 |
| GET | /api/teaching/classes/{id}/students | 任课教师或管理员，返回有效选课名单 |

名单包含学生档案 id、studentNo、displayName、major、className、selectedAt。教师不能通过替换教学班 id 读取其他教师的名单。

## 成绩接口

| 方法 | URL | 权限与内容 |
|---|---|---|
| GET | /api/grades/classes?semesterId=... | 管理员所有教学班，教师本人教学班 |
| GET | /api/grades/classes/{id} | 管理员或任课教师；名单、分项成绩、版本、汇总 |
| PUT | /api/grades/classes/{id} | 保存全班成绩草稿 |
| POST | /api/grades/classes/{id}/publish | 发布全班完整成绩 |
| POST | /api/grades/classes/{id}/withdraw | 撤回全班已发布成绩 |
| GET | /api/grades/mine?semesterId=... | 学生本人已发布成绩与学分汇总 |

保存、发布、撤回请求均为 `{usualWeight:30, entries:[{enrollmentId:1,usualScore:80,examScore:90,remark:"",revision:0}]}`。必须包含当前全部有效选课学生，版本须与读取时一致。分项允许 null 作为草稿，发布前须先保存且全班完整；新增记录 revision 为 0，写入后递增。版本冲突、名单变化和已发布状态禁止写入返回 409。分数范围 0～100，最多两位小数，权重为 0～100 整数。

## 个人资料与公告

| 方法 | URL | 内容 |
|---|---|---|
| GET / PUT | /api/profile | 当前账号资料；PUT 可编辑 email、phone、bio |
| GET | /api/announcements?page=1&size=10&keyword=...&unreadOnly=false | 本角色在有效时间内可读公告 |
| GET | /api/announcements/{id} | 可见公告详情，不可见返回 404 |
| POST | /api/announcements/{id}/read | `{revision:1}`，确认当前版本已读 |
| GET / POST | /api/admin/announcements | 管理员分页查询 / 新建 |
| PUT | /api/admin/announcements/{id} | 管理员编辑、发布、撤回 |
| DELETE | /api/admin/announcements/{id}?revision=1 | 仅删除从未发布的草稿 |

公告写入体：`{title,content,audience,status,pinned,visibleFrom,expiresAt,revision}`。audience 为 ALL/ADMIN/TEACHER/STUDENT；status 为 DRAFT/PUBLISHED；时间为 Asia/Shanghai 的 ISO 本地日期时间，可为 null。新建 revision=0，更新须匹配当前版本。正文最多 5000 字，按纯文本显示。已发布过的公告仅可撤回。编辑后旧已读版本重新计为未读。

## CSV 导出

导出接口直接返回 `text/csv;charset=UTF-8`，不套 ApiResponse，错误仍为 JSON。带附件文件名、UTF-8 BOM 和 no-store。最多一万条，超过返回 409。

- 管理员：`GET /api/exports/admin/{students|teachers|courses|semesters|classes|enrollments|grades}`，支持 keyword、semesterId、status 适用筛选；成绩必须传 classId。
- 学生：`GET /api/exports/student/{enrollments|grades}?semesterId=...`；选课额外支持 keyword、status，只导出本人数据。
- 教师或管理员：`GET /api/exports/teaching/{roster|grades}?classId=...`，教师必须为该班任课教师。

资源为服务端白名单，不含密码及其散列。CSV 对危险公式前缀进行文本保护。
