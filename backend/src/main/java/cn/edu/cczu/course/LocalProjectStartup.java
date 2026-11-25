package cn.edu.cczu.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 直接从源码运行正式版时，准备本机数据库和前端页面。 */
final class LocalProjectStartup {
    private static final Logger log = LoggerFactory.getLogger(LocalProjectStartup.class);
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private LocalProjectStartup() {
    }

    // 在正式应用创建前注册初始化步骤，在应用就绪后注册打开浏览器的步骤。
    // 通过 java -jar 启动的部署包由部署脚本准备参数，这里只处理源码运行。
    static void configure(SpringApplication application) {
        var home = new ApplicationHome(CourseSelectionApplication.class);
        // 打包后的 JAR 由原来的部署脚本准备环境和打开浏览器。
        if (home.getSource() == null || !home.getSource().isDirectory()) {
            return;
        }
        // 优先从编译产物所在位置向上查找项目。找不到时再检查当前工作目录，兼容不同 IDE 的运行目录。
        Path project = findProject(home.getDir().toPath());
        if (project == null) {
            project = findProject(Path.of("").toAbsolutePath());
        }
        if (project == null) {
            return;
        }

        Path root = project;
        // 沿用系统是否具备桌面环境的判断，让 IDE 在有桌面时可打开浏览器。
        application.setHeadless(GraphicsEnvironment.isHeadless());
        application.addInitializers(context -> {
            var environment = context.getEnvironment();
            if (environment.matchesProfiles("sqlserver")) {
                prepare(root, environment);
            }
        });
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) LocalProjectStartup::openBrowser);
    }

    // 目录同时含有 Maven 后端和 npm 前端的入口文件时，才把它视为项目根目录。
    private static Path findProject(Path start) {
        for (Path path = start; path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("backend/pom.xml"))
                    && Files.isRegularFile(path.resolve("frontend/package.json"))) {
                return path;
            }
        }
        return null;
    }

    private static void prepare(Path project, ConfigurableEnvironment environment) {
        // 使用现有部署配置补齐连接参数。命令行和环境变量仍可覆盖这些值。
        Path settings = project.resolve("release/windows/deployment.json");
        String datasourceUrl = environment.getRequiredProperty("spring.datasource.url");
        if (Files.isRegularFile(settings) && (datasourceUrl.startsWith("jdbc:sqlserver://localhost:")
                || datasourceUrl.startsWith("jdbc:sqlserver://127.0.0.1:"))) {
            environment.getPropertySources().addLast(new MapPropertySource("localDeployment", readSettings(settings)));
            // 解析最终传给 JDBC 的地址。只有目标仍是这套本机部署时，才尝试启动 Windows 数据库服务。
            String endpoint = environment.getProperty("DB_HOST") + ":" + environment.getProperty("DB_PORT");
            String url = environment.getRequiredProperty("spring.datasource.url");
            if (WINDOWS && url.startsWith("jdbc:sqlserver://" + endpoint + ";")) {
                run(project, "SQL Server 启动",
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                        project.resolve("deploy/windows/Enable-SqlServer.ps1").toString(), "-Elevate");
            }
        }

        // 本地已有构建产物时直接提供静态页面。缺少入口页时才需要 npm，且依赖未安装时先按锁文件安装。
        Path frontend = project.resolve("frontend");
        Path dist = frontend.resolve("dist");
        if (!Files.isRegularFile(dist.resolve("index.html"))) {
            if (!Files.isDirectory(frontend.resolve("node_modules/vite"))) {
                runNpm(frontend, "安装前端依赖", "ci");
            }
            runNpm(frontend, "构建前端", "run", "build");
        }

        // Vue 页面和 API 由同一端口提供，前端请求可以继续使用相对路径。
        String frontendLocation = dist.toUri().toASCIIString();
        environment.getPropertySources().addLast(new MapPropertySource("localFrontend", Map.of(
                "spring.web.resources.static-locations", frontendLocation + ",classpath:/static/")));
    }

    // 将部署 JSON 里的字段翻译成 application-sqlserver.yml 使用的配置键。
    // 这些值在内存中提供给 Spring，供当前启动实例连接正式库。
    private static Map<String, Object> readSettings(Path settings) {
        try (var input = Files.newInputStream(settings)) {
            var config = new ObjectMapper().readTree(input);
            String host = config.required("hostName").asText();
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                throw new IllegalArgumentException();
            }
            // 和部署脚本一样，只有配置中明确写了布尔值 true 才启用本机账号选择。
            var localAccountSwitch = config.path("localAccountSwitchEnabled");
            return Map.of(
                    "DB_HOST", host,
                    "DB_PORT", config.required("databasePort").asText(),
                    "DB_NAME", config.required("databaseName").asText(),
                    "DB_USERNAME", config.required("databaseUserName").asText(),
                    "DB_PASSWORD", config.required("databasePassword").asText(),
                    "DB_TRUST_CERTIFICATE", "true",
                    "APP_LOCAL_ACCOUNT_SWITCH_ENABLED", localAccountSwitch.isBoolean() && localAccountSwitch.booleanValue(),
                    "SERVER_PORT", config.required("webPort").asText());
        } catch (IOException | IllegalArgumentException exception) {
            // 解析异常可能携带文件片段，启动日志只提示检查配置文件。
            throw new IllegalStateException("无法读取本机部署配置，请检查 " + settings);
        }
    }

    // Windows 下经 cmd 执行 npm.cmd，其他平台直接执行 npm。命令在前端目录中读取对应的 package.json。
    private static void runNpm(Path frontend, String action, String... arguments) {
        var command = new ArrayList<>(WINDOWS ? List.of("cmd.exe", "/d", "/c", "npm.cmd") : List.of("npm"));
        command.addAll(List.of(arguments));
        run(frontend, action, command.toArray(String[]::new));
    }

    // 等待子进程退出并沿用当前控制台输出。数据库或前端准备失败时中止源码启动，避免随后打开不可用页面。
    private static void run(Path directory, String action, String... command) {
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(action + "失败，请检查上方输出。");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法执行" + action + "，请检查所需程序是否已安装。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + "已中断。", exception);
        }
    }

    // 仅在实际 Web 应用就绪且允许自动打开时启动浏览器。
    // 地址使用本次应用实际绑定的端口，所以改端口时也会打开正确页面。
    private static void openBrowser(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext context)) {
            return;
        }
        var environment = context.getEnvironment();
        if (!environment.matchesProfiles("sqlserver")
                || !environment.getProperty("app.open-browser", Boolean.class, true)) {
            return;
        }

        var url = URI.create("http://127.0.0.1:" + context.getWebServer().getPort()
                + context.getServletContext().getContextPath() + "/");
        log.info("学生选课系统已就绪：{}", url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            log.warn("无法自动打开浏览器，请访问 {}", url);
        }
    }
}
