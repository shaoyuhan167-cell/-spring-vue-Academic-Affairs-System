package cn.edu.cczu.course.module.academic.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.academic.service.TeachingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 提供教学班和学生名单查询接口。 */
@RestController
@RequestMapping("/api/teaching")
public class TeachingController {
    private final TeachingService service;
    public TeachingController(TeachingService service) { this.service = service; }

    // 根据登录教师的用户名和选定学期查询本人授课安排。
    @GetMapping("/me/classes") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<?> mine(Authentication authentication, @RequestParam(required=false) Long semesterId) { return ApiResponse.success(service.mine(authentication.getName(),semesterId)); }
    // 将登录用户与班级编号交给业务层检查，再返回对应名单。
    @GetMapping("/classes/{id}/students") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    // 从登录权限判断是否为管理员，将用户名和目标班级交给服务验证。
    public ApiResponse<?> roster(Authentication authentication, @PathVariable Long id) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return ApiResponse.success(service.roster(authentication.getName(),admin,id));
    }
}
