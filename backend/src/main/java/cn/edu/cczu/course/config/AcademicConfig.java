package cn.edu.cczu.course.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Properties;

/** 集中提供教务模块使用的公共配置，例如获取当前时间的时钟。 */
@Configuration
public class AcademicConfig {
    // 为教务逻辑提供统一的时间来源，测试可替换成固定时钟。
    @Bean
    Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    // 把数据库产品名映射到 MyBatis 的标识，用于选择对应的 SQL 方言。
    @Bean
    DatabaseIdProvider databaseIdProvider() {
        // MyBatis 按连接识别数据库，Mapper 可为同一方法声明不同方言的实现，例如分页与加锁。
        var provider = new VendorDatabaseIdProvider();
        var properties = new Properties();
        properties.setProperty("Microsoft SQL Server", "sqlserver");
        properties.setProperty("H2", "h2");
        provider.setProperties(properties);
        return provider;
    }
}
