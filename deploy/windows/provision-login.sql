-- 为应用在 SQL Server 实例与业务库建立对应身份，并授予读写权限。
-- @login 和 @password 由 SqlCommand 参数传入，不拼接调用方提供的原始标识符。
DECLARE @statement nvarchar(max);
-- 在实例上创建应用使用的登录身份，已存在时保留当前身份。
IF SUSER_ID(@login) IS NULL
BEGIN
    SET @statement = N'CREATE LOGIN ' + QUOTENAME(@login) + N' WITH PASSWORD = '
        + QUOTENAME(@password, '''') + N', CHECK_POLICY = ON, CHECK_EXPIRATION = OFF';
    EXEC sys.sp_executesql @statement;
END;
-- 在当前数据库内建立与实例登录关联的数据库用户。
-- 实例登录负责连接 SQL Server，数据库用户负责在指定业务库内识别这个身份。
IF USER_ID(@login) IS NULL
BEGIN
    SET @statement = N'CREATE USER ' + QUOTENAME(@login) + N' FOR LOGIN ' + QUOTENAME(@login);
    EXEC sys.sp_executesql @statement;
END;
-- 分别检查数据库角色成员关系，仅补充尚未具备的权限。
IF NOT EXISTS (SELECT 1 FROM sys.database_role_members r
               WHERE r.role_principal_id = DATABASE_PRINCIPAL_ID(N'db_datareader')
                 AND r.member_principal_id = DATABASE_PRINCIPAL_ID(@login))
BEGIN
    SET @statement = N'ALTER ROLE db_datareader ADD MEMBER ' + QUOTENAME(@login);
    EXEC sys.sp_executesql @statement;
END;
IF NOT EXISTS (SELECT 1 FROM sys.database_role_members r
               WHERE r.role_principal_id = DATABASE_PRINCIPAL_ID(N'db_datawriter')
                 AND r.member_principal_id = DATABASE_PRINCIPAL_ID(@login))
BEGIN
    SET @statement = N'ALTER ROLE db_datawriter ADD MEMBER ' + QUOTENAME(@login);
    EXEC sys.sp_executesql @statement;
END;
