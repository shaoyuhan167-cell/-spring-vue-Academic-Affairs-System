package cn.edu.cczu.course.module.academic.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.*;
import cn.edu.cczu.course.module.academic.service.ManagementService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 提供管理员使用的档案、课程、学期、教学班和选课记录管理接口。 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class ManagementController {
    private final ManagementService service;
    public ManagementController(ManagementService service) { this.service = service; }

    // 为管理表单准备教师、课程和学期等下拉选项。
    // 下拉选项给页面显示名称和内部编号。保存教学班时提交编号，由服务层重新检查其有效性。
    @GetMapping("/options") public ApiResponse<?> options() { return ApiResponse.success(service.options()); }
    // 按关键词和页码读取学生档案，供管理列表显示。
    @GetMapping("/students") public ApiResponse<?> students(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword) { return ApiResponse.success(service.students(page,size,keyword)); }
    // 新建学生登录账号与学籍档案。
    @PostMapping("/students") public ApiResponse<?> createStudent(@Valid @RequestBody StudentRequest body) { return ApiResponse.success(service.createStudent(body)); }
    // 按学生编号修改学籍和账号状态。
    @PutMapping("/students/{id}") public ApiResponse<?> updateStudent(@PathVariable Long id, @Valid @RequestBody StudentRequest body) { return ApiResponse.success(service.updateStudent(id,body)); }
    // 按学生档案编号重置其登录账号的密码。
    @PostMapping("/students/{id}/password") public ApiResponse<?> resetStudentPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest body) { service.resetPassword("students",id,body.newPassword()); return ApiResponse.success(null); }
    // 按关键词和页码读取教师档案，供管理列表显示。
    @GetMapping("/teachers") public ApiResponse<?> teachers(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword) { return ApiResponse.success(service.teachers(page,size,keyword)); }
    // 新建教师登录账号与任职档案。
    @PostMapping("/teachers") public ApiResponse<?> createTeacher(@Valid @RequestBody TeacherRequest body) { return ApiResponse.success(service.createTeacher(body)); }
    // 按教师编号修改任职信息和账号状态。
    @PutMapping("/teachers/{id}") public ApiResponse<?> updateTeacher(@PathVariable Long id, @Valid @RequestBody TeacherRequest body) { return ApiResponse.success(service.updateTeacher(id,body)); }
    // 按教师档案编号重置其登录账号的密码。
    @PostMapping("/teachers/{id}/password") public ApiResponse<?> resetTeacherPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest body) { service.resetPassword("teachers",id,body.newPassword()); return ApiResponse.success(null); }
    // 按编号或课程名筛选课程库，并返回当前页。
    @GetMapping("/courses") public ApiResponse<?> courses(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword) { return ApiResponse.success(service.courses(page,size,keyword)); }
    // 新增课程库记录，供后续开设教学班。
    @PostMapping("/courses") public ApiResponse<?> createCourse(@Valid @RequestBody CourseRequest body) { return ApiResponse.success(service.createCourse(body)); }
    // 更新既有课程的基础信息。
    @PutMapping("/courses/{id}") public ApiResponse<?> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseRequest body) { return ApiResponse.success(service.updateCourse(id,body)); }
    // 删除指定课程前检查它是否还被教学班引用。
    @DeleteMapping("/courses/{id}") public ApiResponse<?> deleteCourse(@PathVariable Long id) { service.deleteCourse(id); return ApiResponse.success(null); }
    // 查询学期。管理列表支持分页和关键词筛选。
    @GetMapping("/semesters") public ApiResponse<?> semesters(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword) { return ApiResponse.success(service.semesters(page,size,keyword)); }
    // 新建学期并校验教学日期和选课窗口。
    @PostMapping("/semesters") public ApiResponse<?> createSemester(@Valid @RequestBody SemesterRequest body) { return ApiResponse.success(service.createSemester(body)); }
    // 修改学期的日期、选课时间或学分上限。
    @PutMapping("/semesters/{id}") public ApiResponse<?> updateSemester(@PathVariable Long id, @Valid @RequestBody SemesterRequest body) { return ApiResponse.success(service.updateSemester(id,body)); }
    // 删除学期前检查是否还有教学班引用它。
    @DeleteMapping("/semesters/{id}") public ApiResponse<?> deleteSemester(@PathVariable Long id) { service.deleteSemester(id); return ApiResponse.success(null); }
    // 按条件查询教学班，供列表或下拉框使用。
    @GetMapping("/classes") public ApiResponse<?> classes(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword, @RequestParam(required=false) Long semesterId) { return ApiResponse.success(service.classes(page,size,keyword,semesterId)); }
    // 保存教学班及其上课安排。
    @PostMapping("/classes") public ApiResponse<?> createClass(@Valid @RequestBody ClassRequest body) { return ApiResponse.success(service.createClass(body)); }
    // 更新指定教学班，并根据已有选课记录限制可修改内容。
    @PutMapping("/classes/{id}") public ApiResponse<?> updateClass(@PathVariable Long id, @Valid @RequestBody ClassRequest body) { return ApiResponse.success(service.updateClass(id,body)); }
    // 仅在教学班没有选退课历史时删除班级及排课。
    @DeleteMapping("/classes/{id}") public ApiResponse<?> deleteClass(@PathVariable Long id) { service.deleteClass(id); return ApiResponse.success(null); }
    // 按关键词、学期和选退课状态查询后台选课记录。
    @GetMapping("/enrollments") public ApiResponse<?> enrollments(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size, @RequestParam(required=false) String keyword, @RequestParam(required=false) Long semesterId, @RequestParam(required=false) String status) { return ApiResponse.success(service.enrollments(page,size,keyword,semesterId,status)); }
}
