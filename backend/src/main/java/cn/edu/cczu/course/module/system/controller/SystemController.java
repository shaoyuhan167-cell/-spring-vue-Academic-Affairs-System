package cn.edu.cczu.course.module.system.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.system.model.SystemOverview;
import cn.edu.cczu.course.module.system.service.SystemService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 提供系统健康检查和管理员工作台的概览接口。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    // 返回应用和数据库的健康信息，供启动和部署检查。
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        // 启动脚本使用数据库产品名核对正式库连接。
        return ApiResponse.success(Map.of("status", "UP", "application", "学生选课系统",
                "database", systemService.databaseProductName()));
    }

    // 汇总账号、课程及选课等记录数量，供管理员查看。
    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SystemOverview> overview() {
        return ApiResponse.success(systemService.overview());
    }
}
