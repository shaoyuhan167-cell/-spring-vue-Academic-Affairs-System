package cn.edu.cczu.course.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 单包部署时，让前端路由直接访问和刷新都能加载入口页面。 */
@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/dashboard", "/courses", "/my-courses", "/timetable",
            "/students", "/teachers", "/course-management", "/teaching-classes",
            "/semesters", "/enrollments", "/teaching", "/roster", "/account", "/grades", "/profile",
            "/announcements", "/announcement-management", "/audit-logs"})
    // 把前端页面请求转发到入口文件，页面地址由 Vue Router 解释。
    public String index() {
        // 服务端内部转发到静态入口，保持地址栏中的原路径，让 Vue 接手并渲染相应页面。
        return "forward:/index.html";
    }
}
