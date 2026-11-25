package cn.edu.cczu.course;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 后端启动入口。启动 Spring Boot 后，由框架加载接口、服务和数据访问层。 */
@SpringBootApplication
@MapperScan("cn.edu.cczu.course.module.**.mapper")
public class CourseSelectionApplication {
    // 入口先建立 Spring Boot 启动器，再交由 LocalProjectStartup 识别源码运行场景。
    // 命令行参数仍交给 Spring 解析，例如指定正式应用的监听端口。
    public static void main(String[] args) {
        var application = new SpringApplication(CourseSelectionApplication.class);
        LocalProjectStartup.configure(application);
        application.run(args);
    }
}
