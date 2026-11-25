package cn.edu.cczu.course.module.course.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.cczu.course.module.academic.service.EnrollmentService;

/** 提供课程目录接口，将学期筛选交给选课服务处理。 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final EnrollmentService courseService;

    public CourseController(EnrollmentService courseService) {
        this.courseService = courseService;
    }

    // 将可选的学期编号交给课程目录服务，并包装查询结果。
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) Long semesterId) {
        // 参数为空时返回各学期已经发布的教学班。实际的选课资格仍由提交选课时的校验决定。
        return ApiResponse.success(courseService.catalog(semesterId));
    }
}
