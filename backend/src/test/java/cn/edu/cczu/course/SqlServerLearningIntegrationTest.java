package cn.edu.cczu.course;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** 在独立 SQL Server 验证库复验扩展功能，运行库保持独立。 */
// 只有显式启用 SQL Server 测试时，才使用独立验证库运行学习模块用例。
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=${SQLSERVER_TEST_URL}",
    "spring.datasource.username=${SQLSERVER_TEST_USERNAME}",
    "spring.datasource.password=${SQLSERVER_TEST_PASSWORD}",
    "spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver",
    "spring.sql.init.mode=never"
})
class SqlServerLearningIntegrationTest extends LearningIntegrationTest { }
