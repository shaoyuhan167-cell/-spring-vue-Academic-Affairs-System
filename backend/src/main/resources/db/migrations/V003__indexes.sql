-- 为 SQL Server 的教务和公告常用查询补充索引。
-- 增量扩展：补充高频查询索引。
USE [$(DatabaseName)];
GO
SET XACT_ABORT ON;
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties WHERE class=0 AND name=N'CourseSelectionSystem')
    THROW 51000, N'目标数据库缺少选课系统标识，已停止迁移。', 1;
BEGIN TRANSACTION;
-- 下面的索引对应按班级人数、班级排课、学期、课程与教师进行的查询。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_enrollment_class' AND object_id=OBJECT_ID(N'dbo.enrollment'))
    CREATE INDEX ix_enrollment_class ON dbo.enrollment(teaching_class_id, status);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_schedule_class' AND object_id=OBJECT_ID(N'dbo.course_schedule'))
    CREATE INDEX ix_schedule_class ON dbo.course_schedule(teaching_class_id);
-- 该索引支持先按学期缩小范围，再判断教学班发布状态。已存在时跳过，便于重复执行部署脚本。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_class_semester' AND object_id=OBJECT_ID(N'dbo.teaching_class'))
    CREATE INDEX ix_class_semester ON dbo.teaching_class(semester_id, status);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_class_course' AND object_id=OBJECT_ID(N'dbo.teaching_class'))
    CREATE INDEX ix_class_course ON dbo.teaching_class(course_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_class_teacher' AND object_id=OBJECT_ID(N'dbo.teaching_class'))
    CREATE INDEX ix_class_teacher ON dbo.teaching_class(teacher_id);
-- 公告靠状态与受众索引过滤，通过账号索引关联各人的阅读记录。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_notice_visible' AND object_id=OBJECT_ID(N'dbo.announcement'))
    CREATE INDEX ix_notice_visible ON dbo.announcement(status, audience, pinned DESC, created_at DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_notice_read_user' AND object_id=OBJECT_ID(N'dbo.announcement_read'))
    CREATE INDEX ix_notice_read_user ON dbo.announcement_read(user_id);
COMMIT TRANSACTION;
GO
