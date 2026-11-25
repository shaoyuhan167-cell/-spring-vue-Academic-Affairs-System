-- 旧版数据库补齐成绩、个人资料、公告和已读记录四张表。
-- 增量扩展：成绩、个人资料和公告，不修改既有八张业务表。
USE [$(DatabaseName)];
GO
SET XACT_ABORT ON;
-- 先检查数据库是否有选课系统的部署标识。标识缺失就中断，避免对其他数据库执行选课迁移。
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties WHERE class=0 AND name=N'CourseSelectionSystem')
    THROW 51000, N'目标数据库缺少选课系统标识，已停止迁移。', 1;
-- 把本次补表操作放在同一事务中。
BEGIN TRANSACTION;
-- 成绩绑定到选课记录，通过版本号防止旧表单覆盖新成绩。
IF OBJECT_ID(N'dbo.student_grade', N'U') IS NULL
CREATE TABLE dbo.student_grade (
    enrollment_id BIGINT PRIMARY KEY,
    usual_score DECIMAL(5,2),
    exam_score DECIMAL(5,2),
    usual_weight INT NOT NULL DEFAULT 30,
    total_score DECIMAL(5,2),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    remark NVARCHAR(500) NOT NULL DEFAULT '',
    updated_by BIGINT NOT NULL,
    updated_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    published_at DATETIME2 NULL,
    revision INT NOT NULL DEFAULT 1,
    FOREIGN KEY (enrollment_id) REFERENCES dbo.enrollment(id),
    FOREIGN KEY (updated_by) REFERENCES dbo.sys_user(id),
    CONSTRAINT ck_grade_scores CHECK ((usual_score IS NULL OR usual_score BETWEEN 0 AND 100) AND (exam_score IS NULL OR exam_score BETWEEN 0 AND 100)),
    CONSTRAINT ck_grade_weight CHECK (usual_weight BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_total CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_status CHECK (status IN ('DRAFT','PUBLISHED'))
);

-- 个人联系方式依附于账号，与学籍和任职字段分开保存。
IF OBJECT_ID(N'dbo.user_profile', N'U') IS NULL
CREATE TABLE dbo.user_profile (
    user_id BIGINT PRIMARY KEY,
    email NVARCHAR(120) NOT NULL DEFAULT '',
    phone VARCHAR(30) NOT NULL DEFAULT '',
    bio NVARCHAR(500) NOT NULL DEFAULT '',
    updated_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    FOREIGN KEY (user_id) REFERENCES dbo.sys_user(id)
);

-- 公告支持按角色和时间展示，并保留自己的发布状态及内容版本。
IF OBJECT_ID(N'dbo.announcement', N'U') IS NULL
CREATE TABLE dbo.announcement (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    title NVARCHAR(150) NOT NULL,
    content NVARCHAR(MAX) NOT NULL,
    audience VARCHAR(20) NOT NULL DEFAULT 'ALL',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    pinned BIT NOT NULL DEFAULT 0,
    visible_from DATETIME2 NULL,
    expires_at DATETIME2 NULL,
    published_at DATETIME2 NULL,
    author_id BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    updated_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    revision INT NOT NULL DEFAULT 1,
    FOREIGN KEY (author_id) REFERENCES dbo.sys_user(id),
    CONSTRAINT ck_notice_audience CHECK (audience IN ('ALL','ADMIN','TEACHER','STUDENT')),
    CONSTRAINT ck_notice_status CHECK (status IN ('DRAFT','PUBLISHED'))
);

-- 记录每位读者看到的公告版本，改版后可重新显示未读。
IF OBJECT_ID(N'dbo.announcement_read', N'U') IS NULL
CREATE TABLE dbo.announcement_read (
    announcement_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notice_revision INT NOT NULL,
    read_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY (announcement_id,user_id),
    FOREIGN KEY (announcement_id) REFERENCES dbo.announcement(id),
    FOREIGN KEY (user_id) REFERENCES dbo.sys_user(id)
);
COMMIT TRANSACTION;
PRINT N'成绩、资料和公告扩展表已准备完成。';
