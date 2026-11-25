package cn.edu.cczu.course.module.learning.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.learning.service.*;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.*;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 提供成绩、个人资料和公告相关接口，将具体业务交给各自的服务。 */
@RestController
@RequestMapping("/api")
public class LearningController {
    private final GradeService grades;private final ProfileService profiles;private final NoticeService notices;
    public LearningController(GradeService grades,ProfileService profiles,NoticeService notices) {this.grades=grades;this.profiles=profiles;this.notices=notices;}
    // 列出当前教师或管理员有权操作成绩的教学班。
    @GetMapping("/grades/classes") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ApiResponse<?> classes(Principal principal,@RequestParam(required=false) Long semesterId) {return ApiResponse.success(grades.classes(principal.getName(),semesterId));}
    // 读取教学班名单和成绩，汇总已录入、已发布、平均分和及格人数。
    @GetMapping("/grades/classes/{id}") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ApiResponse<?> book(Principal p,@PathVariable Long id) {return ApiResponse.success(grades.book(p.getName(),id));}
    // 验证提交表单后，把班级成绩交给服务保存为草稿。
    @PutMapping("/grades/classes/{id}") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ApiResponse<?> save(Principal p,@PathVariable Long id,@Valid @RequestBody GradeBatch body) {return ApiResponse.success(grades.save(p.getName(),id,body));}
    // 根据请求中的名单和版本发布当前教学班的成绩。
    @PostMapping("/grades/classes/{id}/publish") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ApiResponse<?> publish(Principal p,@PathVariable Long id,@Valid @RequestBody GradeBatch body) {return ApiResponse.success(grades.changeStatus(p.getName(),id,body,true));}
    // 校验提交版本之后撤回发布，学生端随即停止展示这些成绩。
    @PostMapping("/grades/classes/{id}/withdraw") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ApiResponse<?> withdraw(Principal p,@PathVariable Long id,@Valid @RequestBody GradeBatch body) {return ApiResponse.success(grades.changeStatus(p.getName(),id,body,false));}
    // 根据会话中的学生账号查询已发布成绩及统计。
    @GetMapping("/grades/mine") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<?> mine(Principal p,@RequestParam(required=false) Long semesterId) {return ApiResponse.success(grades.mine(p.getName(),semesterId));}
    // 按用户名或当前会话读取个人资料。
    // 当前用户从会话获取，资料接口不用前端传账号编号，可以避免误读或修改其他人的资料。
    @GetMapping("/profile") public ApiResponse<?> profile(Principal p) {return ApiResponse.success(profiles.get(p.getName()));}
    // 保存用户可编辑的个人资料字段。
    @PutMapping("/profile") public ApiResponse<?> updateProfile(Principal p,@Valid @RequestBody ProfileRequest body) {profiles.update(p.getName(),body);return ApiResponse.success(profiles.get(p.getName()));}
    // 读取当前角色可见的公告，可用关键词和未读状态筛选。
    @GetMapping("/announcements") public ApiResponse<?> feed(Principal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean unreadOnly) {return ApiResponse.success(notices.list(p.getName(),false,page,size,keyword,unreadOnly));}
    // 按公告编号读取当前用户可见的内容。
    @GetMapping("/announcements/{id}") public ApiResponse<?> detail(Principal p,@PathVariable Long id) {return ApiResponse.success(notices.detail(p.getName(),id));}
    // 将当前用户看过的公告版本保存为已读。
    @PostMapping("/announcements/{id}/read") public ApiResponse<?> read(Principal p,@PathVariable Long id,@Valid @RequestBody ReadNoticeRequest body) {notices.read(p.getName(),id,body.revision());return ApiResponse.success(null);}
    // 按管理视角查询公告列表，便于查看草稿和发布历史。
    @GetMapping("/admin/announcements") public ApiResponse<?> management(Principal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String keyword) {return ApiResponse.success(notices.list(p.getName(),true,page,size,keyword,false));}
    // 接收管理员的新公告表单，交给服务保存。
    @PostMapping("/admin/announcements") public ApiResponse<?> create(Principal p,@Valid @RequestBody NoticeRequest body) {notices.create(p.getName(),body);return ApiResponse.success(null);}
    // 按公告编号和表单版本处理管理员的修改请求。
    @PutMapping("/admin/announcements/{id}") public ApiResponse<?> update(Principal p,@PathVariable Long id,@Valid @RequestBody NoticeRequest body) {notices.update(p.getName(),id,body);return ApiResponse.success(null);}
    // 按公告编号和版本请求删除未发布的草稿。
    @DeleteMapping("/admin/announcements/{id}") public ApiResponse<?> remove(Principal p,@PathVariable Long id,@RequestParam int revision) {notices.remove(p.getName(),id,revision);return ApiResponse.success(null);}
}
