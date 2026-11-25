package cn.edu.cczu.course.module.academic.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.BatchEnrollRequest;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.EnrollRequest;
import cn.edu.cczu.course.module.academic.service.EnrollmentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 为学生提供查看选课记录、单选、批量选课、退课和查看课表的接口。 */
@RestController
public class EnrollmentController {
    private final EnrollmentService service;
    public EnrollmentController(EnrollmentService service) { this.service = service; }

    // 返回可供学生选择的学期列表。
    @GetMapping("/api/semesters") public ApiResponse<?> semesters() { return ApiResponse.success(service.semesters()); }
    // 从登录身份取得学生用户名，查询该学生本人的选退课记录。
    @GetMapping("/api/enrollments") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> mine(Authentication authentication, @RequestParam(required=false) Long semesterId) { return ApiResponse.success(service.mine(authentication.getName(),semesterId)); }
    // 提交一个教学班，与批量选课共用同一套验证。
    @PostMapping("/api/enrollments") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> enroll(Authentication authentication, @Valid @RequestBody EnrollRequest body) { return ApiResponse.success(service.enroll(authentication.getName(),body.teachingClassId())); }
    // 检查提交个数与重复编号，再把整组课程交给同一事务处理。
    // 批量接口把一组选择交给同一个事务。用户身份由当前会话给出，表单仅提供教学班编号。
    @PostMapping("/api/enrollments/batch") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> enrollBatch(Authentication authentication, @Valid @RequestBody BatchEnrollRequest body) {
        return ApiResponse.success(service.enrollBatch(authentication.getName(), body.teachingClassIds()));
    }
    // 验证记录归属和退课条件，更新状态并保留退课历史。
    @DeleteMapping("/api/enrollments/{id}") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> drop(Authentication authentication, @PathVariable Long id) { service.drop(authentication.getName(),id); return ApiResponse.success(null); }
    // 把有效选课的上课安排展开成课表条目，可筛选指定周次。
    @GetMapping("/api/timetable") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> timetable(Authentication authentication, @RequestParam(required=false) Long semesterId, @RequestParam(required=false) Integer week) { return ApiResponse.success(service.timetable(authentication.getName(),semesterId,week)); }
}
