package cn.edu.cczu.course.module.audit.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.audit.mapper.AuditMapper;
import cn.edu.cczu.course.module.audit.model.AuditLog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 为管理员提供审计日志查询入口，用于追踪关键操作。 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditMapper mapper;

    public AuditController(AuditMapper mapper) {
        this.mapper = mapper;
    }

    // 按操作类型筛选最近的审计记录，并限制返回条数。
    @GetMapping
    public ApiResponse<List<AuditLog>> latest(@RequestParam(required = false) String action,
                                              @RequestParam(defaultValue = "50") int size) {
        // 后台可按动作类型筛选，返回数量限制在 1 到 200，以避免查询过多日志。
        return ApiResponse.success(mapper.latest(action, Math.clamp(size, 1, 200)));
    }
}
