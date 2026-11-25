package cn.edu.cczu.course.module.learning.service;

import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.academic.service.AcademicSupport;
import cn.edu.cczu.course.module.audit.service.AuditService;
import cn.edu.cczu.course.module.learning.mapper.LearningMapper;
import cn.edu.cczu.course.module.learning.model.LearningModels.*;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.*;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 处理成绩录入、发布、撤回，以及学生已发布成绩的统计。 */
@Service
@Transactional(readOnly=true)
public class GradeService {
    private final AcademicMapper academic;
    private final LearningMapper mapper;
    private final UserMapper users;
    private final AcademicSupport support;
    private final AuditService audit;
    public GradeService(AcademicMapper academic,LearningMapper mapper,UserMapper users,AcademicSupport support,AuditService audit) {
        this.academic=academic;this.mapper=mapper;this.users=users;this.support=support;this.audit=audit;
    }
    // 教师仅加载本人授课班级，管理员可按学期加载班级列表。
    public List<ClassView> classes(String username,Long semesterId) {
        var user=require(users.findByUsername(username),"账号不存在");
        if(user.role().equals("TEACHER")) return support.classViews(academic.teacherClasses(require(academic.teacherByUsername(username),"教师档案不存在").id(),semesterId));
        if(!user.role().equals("ADMIN")) throw new BusinessException(HttpStatus.FORBIDDEN,"仅教师和管理员可管理成绩");
        check(academic.countClasses(null,semesterId)<=10000,"教学班过多，请选择学期");
        var rows=new ArrayList<ClassRow>();
        for(int offset=0;;offset+=100) { var page=academic.classes(null,semesterId,offset,100);rows.addAll(page);if(page.size()<100)break; }
        return support.classViews(rows);
    }
    // 管理员可访问全部班级。教师只可读取和操作自己负责的班级。
    public ClassRow accessible(String username,Long classId) {
        var user=require(users.findByUsername(username),"账号不存在");
        var row=require(academic.teachingClass(classId),"教学班不存在");
        if(!user.role().equals("ADMIN")) {
            var teacher=academic.teacherByUsername(username);
            if(!user.role().equals("TEACHER")||teacher==null||!teacher.id().equals(row.teacherId()))
                throw new BusinessException(HttpStatus.FORBIDDEN,"只能管理本人教学班成绩");
        }
        return row;
    }
    // 读取教学班名单和成绩，汇总已录入、已发布、平均分和及格人数。
    public GradeBook book(String username,Long classId) {
        var row=accessible(username,classId);var grades=mapper.gradeRows(classId);
        // 班级平均分只统计已经计算出总分的成绩。没有完整成绩时平均分保持为空。
        var complete=grades.stream().filter(g->g.totalScore()!=null).toList();
        return new GradeBook(support.classView(row),grades.isEmpty()?30:grades.getFirst().usualWeight(),grades,complete.size(),
            (int)grades.stream().filter(g->g.status().equals("PUBLISHED")).count(),
            complete.isEmpty()?null:complete.stream().map(GradeRow::totalScore).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(complete.size()),2,RoundingMode.HALF_UP),
            (int)complete.stream().filter(g->g.totalScore().compareTo(BigDecimal.valueOf(60))>=0).count());
    }
    // 锁住学期和教学班后重查名单，让成绩写入与选退课互相协调。
    private List<GradeRow> lockedRows(String username,Long id) {
        // 沿用选课时的加锁顺序，再读取名单，避免保存成绩期间选课名单发生变化。
        var initial=accessible(username,id);support.lockSemesters(List.of(initial.semesterId()));
        require(academic.lockClass(id),"教学班不存在");
        var current=accessible(username,id);
        check(current.semesterId().equals(initial.semesterId()),"教学班已变更，请刷新后重试");
        return mapper.gradeRows(id);
    }
    // 核对学生名单和成绩版本，避免名单变化或并发保存导致覆盖。
    private void verify(List<GradeRow> rows,GradeBatch body) {
        check(!rows.isEmpty(),"本教学班暂无选课学生");
        var entries=body.entries();
        valid(entries.stream().map(GradeEntry::enrollmentId).distinct().count()==entries.size(),"成绩中存在重复学生");
        // 先确认提交的学生名单与最新名单一致，再比对每条成绩的版本，避免覆盖别人刚保存的修改。
        var versions=entries.stream().collect(Collectors.toMap(GradeEntry::enrollmentId,Function.identity()));
        // 后端用当前班级名单重新核对表单。人数、选课记录编号和每条成绩版本都要对上。
        check(rows.size()==entries.size()&&rows.stream().allMatch(g->versions.containsKey(g.enrollmentId())),"学生名单已变化，请刷新后重新录入");
        check(rows.stream().allMatch(g->g.revision()==versions.get(g.enrollmentId()).revision()),"成绩已被其他操作更新，请刷新后重试");
    }
    // 平时和考试成绩都已录入时才按权重计算总分。
    public static BigDecimal total(BigDecimal usual,BigDecimal exam,int weight) {
        // |exam==null?null:|平时分和考试分都已填写才计算总分，按平时权重加权并四舍五入到两位小数。
        return usual==null||exam==null?null:usual.multiply(BigDecimal.valueOf(weight)).add(exam.multiply(BigDecimal.valueOf(100-weight))).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP);
    }
    // 锁定当前名单后校验版本，计算总分并保存全班草稿。
    @Transactional
    public GradeBook save(String username,Long id,GradeBatch body) {
        var rows=lockedRows(username,id);verify(rows,body);
        check(rows.stream().noneMatch(g->g.status().equals("PUBLISHED")),"请先撤回已发布成绩再修改");
        var user=users.findByUsername(username);var now=support.now();
        for(var entry:body.entries()) {
            var safe=new GradeEntry(entry.enrollmentId(),entry.usualScore(),entry.examScore(),entry.remark()==null?"":entry.remark().trim(),entry.revision());
            var score=total(safe.usualScore(),safe.examScore(),body.usualWeight());
            // 版本为 0 表示尚无成绩记录；已有记录按提交时的版本更新。
            if(entry.revision()==0) mapper.insertGrade(safe,body.usualWeight(),score,user.id(),now);
            else check(mapper.updateGrade(safe,body.usualWeight(),score,user.id(),now)==1,"成绩已变化，请刷新重试");
        }
        audit.record(username, "GRADE_SAVE", "TEACHING_CLASS", id, "保存 " + rows.size() + " 条成绩，平时权重 " + body.usualWeight());
        return book(username,id);
    }
    // 发布完整的全班成绩，或撤回已发布成绩以恢复编辑。
    @Transactional
    public GradeBook changeStatus(String username,Long id,GradeBatch body,boolean publish) {
        var rows=lockedRows(username,id);verify(rows,body);
        // 发布前要求全班成绩都完整，而且使用相同的平时分权重。
        if(publish) {
            check(rows.stream().noneMatch(g->g.status().equals("PUBLISHED")),"成绩已经发布");
            check(rows.stream().allMatch(g->g.usualScore()!=null&&g.examScore()!=null&&g.totalScore()!=null),"请先保存全班完整成绩再发布");
            check(rows.stream().map(GradeRow::usualWeight).distinct().count()==1,"全班成绩权重不一致，请重新保存");
        } else check(rows.stream().anyMatch(g->g.status().equals("PUBLISHED")),"本班成绩尚未发布");
        // 发布时记录新的发布时间，撤回时清空该时间。两种操作都会被审计记录。
        var now=support.now();mapper.gradeStatus(id,publish?"PUBLISHED":"DRAFT",publish?now:null,now,users.findByUsername(username).id());
        audit.record(username, publish ? "GRADE_PUBLISH" : "GRADE_WITHDRAW", "TEACHING_CLASS", id,
                (publish ? "发布 " : "撤回 ") + rows.size() + " 条成绩");
        return book(username,id);
    }
    // 读取当前学生已发布成绩并计算学分加权平均分和绩点。
    public GradeSummary mine(String username,Long semesterId) {
        var student=require(academic.studentByUsername(username),"学生档案不存在");
        var records=mapper.myGrades(username,semesterId);
        // 已选课程总数减去已发布成绩的门数，即学生端要提示的未发布门数。
        long enrolled=academic.studentEnrollments(student.id(),semesterId).stream().filter(e->e.status().equals("ENROLLED")).count();
        BigDecimal credits=BigDecimal.ZERO,earned=BigDecimal.ZERO,weighted=BigDecimal.ZERO,points=BigDecimal.ZERO;
        // 均分与绩点都按课程学分加权，及格课程的学分计入已获学分。
        for(var grade:records) {
            credits=credits.add(grade.credits());weighted=weighted.add(grade.totalScore().multiply(grade.credits()));
            if(grade.totalScore().compareTo(BigDecimal.valueOf(60))>=0) earned=earned.add(grade.credits());
            // 演示绩点不及格计 0，及格后按分数折算，最高为 4。学期绩点随后以各课程学分加权。
            BigDecimal point=grade.totalScore().compareTo(BigDecimal.valueOf(60))<0?BigDecimal.ZERO:grade.totalScore().subtract(BigDecimal.valueOf(50)).divide(BigDecimal.TEN).min(BigDecimal.valueOf(4));
            points=points.add(point.multiply(grade.credits()));
        }
        return new GradeSummary(records,Math.max(0,enrolled-records.size()),credits.signum()==0?null:weighted.divide(credits,2,RoundingMode.HALF_UP),earned,credits,
            credits.signum()==0?null:points.divide(credits,2,RoundingMode.HALF_UP));
    }
}
