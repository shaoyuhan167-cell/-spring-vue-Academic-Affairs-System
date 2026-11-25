package cn.edu.cczu.course.module.learning.controller;

import cn.edu.cczu.course.module.learning.service.ExportService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

/** 接收各角色的导出请求，把生成的 CSV 作为文件返回给浏览器。 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private final ExportService service;
    public ExportController(ExportService service) {this.service=service;}
    // 把 CSV 字节交给浏览器下载，并设置文件名和响应类型。
    // 设置 CSV 类型和下载文件名，浏览器收到的是文件内容，普通 JSON 解包逻辑不会参与。
    private ResponseEntity<byte[]> csv(byte[] data) {return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("选课系统数据.csv",StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(data);}
    // 获取管理范围内的导出数据并返回 CSV 下载。
    @GetMapping("/admin/{resource}") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> admin(Authentication a,@PathVariable String resource,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long semesterId,@RequestParam(required=false) String status,@RequestParam(required=false) Long classId) {return csv(service.admin(resource,keyword,semesterId,status,classId,a.getName()));}
    // 从登录身份获取学生账号，仅导出本人的数据。
    @GetMapping("/student/{resource}") @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<byte[]> student(Authentication a,@PathVariable String resource,@RequestParam(required=false) Long semesterId,@RequestParam(required=false) String keyword,@RequestParam(required=false) String status) {return csv(service.student(resource,a.getName(),semesterId,keyword,status));}
    // 按教师或管理员权限导出指定教学班的名单或成绩。
    @GetMapping("/teaching/{resource}") @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public ResponseEntity<byte[]> teaching(Authentication a,@PathVariable String resource,@RequestParam Long classId) {return csv(service.teaching(resource,a.getName(),a.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_ADMIN")),classId));}
}
