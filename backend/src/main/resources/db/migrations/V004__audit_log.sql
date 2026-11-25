-- 为旧版数据库补建审计表，保存关键教务操作的执行记录。
-- 增量扩展：增加操作审计表。
USE [$(DatabaseName)];
GO
SET XACT_ABORT ON;
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties WHERE class=0 AND name=N'CourseSelectionSystem')
    THROW 51000, N'目标数据库缺少选课系统标识，已停止迁移。', 1;
-- 把创建日志表和添加时间索引放进同一次数据库事务。迁移失败时由事务回滚这一组修改。
BEGIN TRANSACTION;
-- 记录操作人、事由、被操作的对象及请求来源。
IF OBJECT_ID(N'dbo.audit_log', N'U') IS NULL
    CREATE TABLE dbo.audit_log (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        actor_id BIGINT NOT NULL,
        actor_role VARCHAR(20) NOT NULL,
        action VARCHAR(50) NOT NULL,
        target_type VARCHAR(30) NOT NULL,
        target_id BIGINT NULL,
        detail NVARCHAR(1000) NOT NULL DEFAULT N'',
        client_ip VARCHAR(45) NOT NULL DEFAULT '',
        created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
        FOREIGN KEY (actor_id) REFERENCES dbo.sys_user(id)
    );
-- 按操作时间建立索引，便于后台优先加载最新记录。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'ix_audit_created' AND object_id=OBJECT_ID(N'dbo.audit_log'))
    CREATE INDEX ix_audit_created ON dbo.audit_log(created_at DESC);
COMMIT TRANSACTION;
GO
