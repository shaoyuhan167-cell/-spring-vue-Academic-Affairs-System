package cn.edu.cczu.course.module.system.mapper;

import cn.edu.cczu.course.module.system.model.SystemOverview;
import org.apache.ibatis.annotations.Select;

/** 从数据库汇总工作台需要的各类记录数。 */
public interface SystemMapper {
    // 在数据库中统计数量，仅返回汇总给工作台，页面无需下载完整账号和选课记录。
    @Select("""
            SELECT (SELECT COUNT(*) FROM student) AS student_count,
                   (SELECT COUNT(*) FROM teacher) AS teacher_count,
                   (SELECT COUNT(*) FROM course) AS course_count,
                   (SELECT COUNT(*) FROM teaching_class) AS teaching_class_count
            """)
    // 汇总账号、课程及选课等记录数量，供管理员查看。
    SystemOverview overview();
}
