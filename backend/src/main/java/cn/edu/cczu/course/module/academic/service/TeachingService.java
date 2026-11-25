package cn.edu.cczu.course.module.academic.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.require;

/** 查询教师负责的教学班和选课名单，并检查名单的访问权限。 */
@Service
@Transactional(readOnly = true)
public class TeachingService {
    private final AcademicMapper mapper;
    private final AcademicSupport support;

    public TeachingService(AcademicMapper mapper, AcademicSupport support) {
        this.mapper = mapper;
        this.support = support;
    }

    // 通过教师档案找到授课记录，并附上班级排课。
    public List<ClassView> mine(String username, Long semesterId) {
        var teacher = require(mapper.teacherByUsername(username), "当前账号缺少教师档案");
        return support.classViews(mapper.teacherClasses(teacher.id(), semesterId));
    }

    // 检查该班是否由当前教师负责，管理员可跨班查询有效选课名单。
    public List<RosterStudent> roster(String username, boolean admin, Long classId) {
        var teachingClass = require(mapper.teachingClass(classId), "教学班不存在");
        // 教师查询名单时必须与教学班的任课教师匹配。管理员允许查询其他教师的班级。
        if (!admin) {
            var teacher = require(mapper.teacherByUsername(username), "当前账号缺少教师档案");
            if (!teacher.id().equals(teachingClass.teacherId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "只能查看本人授课教学班的学生名单");
            }
        }
        return mapper.roster(classId);
    }
}
