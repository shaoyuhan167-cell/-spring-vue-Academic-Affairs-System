package cn.edu.cczu.course;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** 显式启用时，在独立 SQL Server 验证库中重跑业务与并发测试。 */
// 只有显式启用 SQL Server 测试时，才在专用验证库复用教务和并发用例。
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS", matches="true")
@SpringBootTest(properties={
        "spring.datasource.url=${SQLSERVER_TEST_URL}",
        "spring.datasource.username=${SQLSERVER_TEST_USERNAME}",
        "spring.datasource.password=${SQLSERVER_TEST_PASSWORD}",
        "spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "spring.sql.init.mode=never"
})
class SqlServerAcademicIntegrationTest extends AcademicIntegrationTest { }
