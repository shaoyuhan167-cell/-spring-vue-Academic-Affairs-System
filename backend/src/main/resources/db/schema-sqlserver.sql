-- SQL Server 2022 建库建表脚本，通过 sqlcmd -v DatabaseName=course_selection 执行。
-- 不覆盖现有表，也不向项目之外的数据库写入数据。
SET NOCOUNT ON;
IF DB_ID(N'$(DatabaseName)') IS NULL
    EXEC(N'CREATE DATABASE [$(DatabaseName)]');
GO
USE [$(DatabaseName)];
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF EXISTS (SELECT 1 FROM sys.tables)
   AND NOT EXISTS (SELECT 1 FROM sys.extended_properties WHERE class = 0 AND name = N'CourseSelectionSystem')
    THROW 51000, N'目标数据库已有其他表且缺少选课系统标识，已停止初始化以保护现有数据。', 1;

IF NOT EXISTS (SELECT 1 FROM sys.extended_properties WHERE class = 0 AND name = N'CourseSelectionSystem')
    EXEC sys.sp_addextendedproperty @name = N'CourseSelectionSystem', @value = N'1';

-- 登录账号集中存放在这里，学生档案和教师档案都通过账号编号关联。
IF OBJECT_ID(N'dbo.sys_user', N'U') IS NULL
CREATE TABLE dbo.sys_user (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    username NVARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name NVARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BIT NOT NULL DEFAULT 1,
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    CONSTRAINT ck_user_role CHECK (role IN ('ADMIN', 'STUDENT', 'TEACHER'))
);

-- 学生档案保存学号、专业和班级等学籍信息。
IF OBJECT_ID(N'dbo.student', N'U') IS NULL
CREATE TABLE dbo.student (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES dbo.sys_user(id),
    student_no NVARCHAR(30) NOT NULL UNIQUE,
    major NVARCHAR(100) NOT NULL,
    class_name NVARCHAR(100) NOT NULL
);

-- 教师档案保存工号和院系，开设教学班时引用这里的教师。
IF OBJECT_ID(N'dbo.teacher', N'U') IS NULL
CREATE TABLE dbo.teacher (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES dbo.sys_user(id),
    teacher_no NVARCHAR(30) NOT NULL UNIQUE,
    department NVARCHAR(100) NOT NULL
);

-- 课程库定义课程名称、学分和学时，一门课程可以对应多个教学班。
IF OBJECT_ID(N'dbo.course', N'U') IS NULL
CREATE TABLE dbo.course (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    course_code NVARCHAR(30) NOT NULL UNIQUE,
    name NVARCHAR(100) NOT NULL,
    credits DECIMAL(4,1) NOT NULL,
    hours INT NOT NULL,
    description NVARCHAR(1000),
    CONSTRAINT ck_course_credits CHECK (credits > 0),
    CONSTRAINT ck_course_hours CHECK (hours > 0)
);

-- 学期同时记录教学日期、允许选退课的时间段和学分上限。
IF OBJECT_ID(N'dbo.semester', N'U') IS NULL
CREATE TABLE dbo.semester (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    name NVARCHAR(50) NOT NULL UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    selection_start DATETIME2 NOT NULL,
    selection_end DATETIME2 NOT NULL,
    max_credits DECIMAL(5,1) NOT NULL DEFAULT 30.0,
    CONSTRAINT ck_semester_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_selection_dates CHECK (selection_end >= selection_start)
);

-- 教学班表示一门课程在某学期的一次具体开设，包含任课教师、容量和发布状态。
IF OBJECT_ID(N'dbo.teaching_class', N'U') IS NULL
CREATE TABLE dbo.teaching_class (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    class_code NVARCHAR(40) NOT NULL UNIQUE,
    course_id BIGINT NOT NULL REFERENCES dbo.course(id),
    teacher_id BIGINT NOT NULL REFERENCES dbo.teacher(id),
    semester_id BIGINT NOT NULL REFERENCES dbo.semester(id),
    capacity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_class_capacity CHECK (capacity > 0),
    CONSTRAINT ck_class_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED'))
);

-- 教学班可以有多段上课安排，每段记录星期、节次、周次和教室。
IF OBJECT_ID(N'dbo.course_schedule', N'U') IS NULL
CREATE TABLE dbo.course_schedule (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    teaching_class_id BIGINT NOT NULL REFERENCES dbo.teaching_class(id),
    weekday INT NOT NULL,
    start_period INT NOT NULL,
    end_period INT NOT NULL,
    start_week INT NOT NULL,
    end_week INT NOT NULL,
    classroom NVARCHAR(100) NOT NULL,
    CONSTRAINT ck_schedule_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_schedule_period CHECK (start_period >= 1 AND end_period >= start_period),
    CONSTRAINT ck_schedule_week CHECK (start_week >= 1 AND end_week >= start_week)
);

-- 连接学生和教学班。退课通过状态和退课时间保留记录，再选时可以恢复。
IF OBJECT_ID(N'dbo.enrollment', N'U') IS NULL
CREATE TABLE dbo.enrollment (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES dbo.student(id),
    teaching_class_id BIGINT NOT NULL REFERENCES dbo.teaching_class(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ENROLLED',
    selected_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    dropped_at DATETIME2 NULL,
    -- 一个学生与教学班的组合只允许出现一次，退课和重新选课都通过更新这条记录完成。
    CONSTRAINT uk_enrollment UNIQUE (student_id, teaching_class_id),
    CONSTRAINT ck_enrollment_status CHECK (status IN ('ENROLLED', 'DROPPED'))
);

-- 成绩关联到一条选课记录，保存平时分、考试分、权重和发布状态。
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
    -- 空分数可保存为草稿，非空分数必须落在有效范围内。发布前还会核对整个教学班的成绩。
    CONSTRAINT ck_grade_scores CHECK ((usual_score IS NULL OR usual_score BETWEEN 0 AND 100) AND (exam_score IS NULL OR exam_score BETWEEN 0 AND 100)),
    CONSTRAINT ck_grade_weight CHECK (usual_weight BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_total CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_status CHECK (status IN ('DRAFT','PUBLISHED'))
);

-- 个人资料单独存放，供用户维护联系方式及个人简介。
IF OBJECT_ID(N'dbo.user_profile', N'U') IS NULL
CREATE TABLE dbo.user_profile (
    user_id BIGINT PRIMARY KEY,
    email NVARCHAR(120) NOT NULL DEFAULT '',
    phone VARCHAR(30) NOT NULL DEFAULT '',
    bio NVARCHAR(500) NOT NULL DEFAULT '',
    updated_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    FOREIGN KEY (user_id) REFERENCES dbo.sys_user(id)
);

-- 公告可以设定面向的角色、显示时间及发布状态。
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

-- 用账号与公告关联已读记录，并记录该用户读过的公告版本。
IF OBJECT_ID(N'dbo.announcement_read', N'U') IS NULL
CREATE TABLE dbo.announcement_read (
    announcement_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notice_revision INT NOT NULL,
    read_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    -- 已读记录的两列共同标识某位读者对某条公告的阅读。公告修改后，查询会按版本重新判断未读。
    PRIMARY KEY (announcement_id,user_id),
    FOREIGN KEY (announcement_id) REFERENCES dbo.announcement(id),
    FOREIGN KEY (user_id) REFERENCES dbo.sys_user(id)
);

COMMIT TRANSACTION;
SELECT DB_NAME() AS database_name, COUNT(*) AS table_count FROM sys.tables;
