package cn.edu.cczu.course.module.learning.service;

import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.service.EnrollmentService;
import cn.edu.cczu.course.module.academic.service.TeachingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.IntFunction;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 按导出范围和当前账号查询数据，再将指定字段整理成 CSV。 */
@Service
@Transactional(readOnly=true)
public class ExportService {
    private final AcademicMapper mapper;private final GradeService grades;private final EnrollmentService enrollments;private final TeachingService teaching;
    public ExportService(AcademicMapper mapper,GradeService grades,EnrollmentService enrollments,TeachingService teaching) {this.mapper=mapper;this.grades=grades;this.enrollments=enrollments;this.teaching=teaching;}
    // 逐页读取需要导出的记录，再整理成完整的结果集。
    private List<?> collect(IntFunction<? extends List<?>> fetch) {
        var rows=new ArrayList<Object>();
        // 导出接口依据后台分页逐段收集记录，避免为导出另写一套选择范围和过滤规则。
        for(int offset=0;;offset+=100) {var page=fetch.apply(offset);rows.addAll(page);check(rows.size()<=10000,"导出超过一万条，请缩小筛选范围");if(page.size()<100)return rows;}
    }
    // 按指定字段顺序抽取每行数据，与列标题一起交给 CSV 工具。
    private byte[] table(List<?> records,String header,String fields) {
        check(records.size()<=10000,"导出超过一万条，请缩小筛选范围");
        var rows=new ArrayList<List<Object>>();
        for(Object record:records) {
            var row=new ArrayList<Object>();
            for(String field:fields.split(",")) {
                // 字段列表与 CSV 表头顺序对应。通过记录类型提供的同名方法读取内容，字段配置不匹配时立即失败。
                try { row.add(record.getClass().getMethod(field).invoke(record)); }
                catch(ReflectiveOperationException ex) {throw new IllegalStateException("导出字段配置错误",ex);}
            }
            rows.add(row);
        }
        return CsvWriter.write(List.of(header.split(",")),rows);
    }
    // 按后台资源和筛选条件收集数据，选择对应的 CSV 列。
    public byte[] admin(String resource,String keyword,Long semesterId,String status,Long classId,String username) {
        String pattern=pattern(keyword);
        return switch(resource) {
            case "students" -> table(collect(o->mapper.students(pattern,o,100)),"学号,姓名,专业,班级,启用","studentNo,displayName,major,className,enabled");
            case "teachers" -> table(collect(o->mapper.teachers(pattern,o,100)),"工号,姓名,院系,启用","teacherNo,displayName,department,enabled");
            case "courses" -> table(collect(o->mapper.courses(pattern,o,100)),"课程编号,课程名称,学分,学时,简介","courseCode,name,credits,hours,description");
            case "semesters" -> table(collect(o->mapper.semesters(pattern,o,100)),"学期,开始日期,结束日期,选课开始,选课结束,学分上限","name,startDate,endDate,selectionStart,selectionEnd,maxCredits");
            case "classes" -> table(collect(o->mapper.classes(pattern,semesterId,o,100)),"教学班,课程编号,课程,教师,学期,学分,容量,已选,状态","classCode,courseCode,courseName,teacherName,semesterName,credits,capacity,selectedCount,status");
            case "enrollments" -> {
                valid(status==null||Set.of("ENROLLED","DROPPED").contains(status),"选课状态不正确");
                yield enrollmentTable(collect(o->mapper.enrollments(pattern,semesterId,status,o,100)));
            }
            case "grades" -> {valid(classId!=null,"请选择要导出的教学班");yield gradeTable(username,classId);}
            default -> throw new cn.edu.cczu.course.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"不支持导出此资源");
        };
    }
    // 为选课记录统一 CSV 的列名与字段顺序。
    private byte[] enrollmentTable(List<?> rows) {return table(rows,"学号,姓名,课程,教学班,教师,学期,学分,状态,选课时间,退课时间","studentNo,studentName,courseName,classCode,teacherName,semesterName,credits,status,selectedAt,droppedAt");}
    // 从当前学生的课表、选课记录或成绩中选择要导出的内容。
    public byte[] student(String resource,String username,Long semesterId,String keyword,String status) {
        valid(keyword==null||keyword.length()<=100,"搜索内容不能超过 100 个字符");
        valid(status==null||Set.of("ENROLLED","DROPPED").contains(status),"选课状态不正确");
        String term=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        if(resource.equals("enrollments"))return enrollmentTable(enrollments.mine(username,semesterId).stream().map(e->e.info())
            .filter(e->status==null||status.equals(e.status()))
            .filter(e->(e.courseName()+" "+e.classCode()+" "+e.teacherName()).toLowerCase(Locale.ROOT).contains(term)).toList());
        valid(resource.equals("grades"),"不支持导出此资源");
        return table(grades.mine(username,semesterId).records(),"课程编号,课程,教学班,教师,学期,学分,平时成绩,期末成绩,平时权重,总评,备注,发布时间","courseCode,courseName,classCode,teacherName,semesterName,credits,usualScore,examScore,usualWeight,totalScore,remark,publishedAt");
    }
    // 读取教学班成绩表并转成可下载的 CSV。
    public byte[] gradeTable(String username,Long classId) {
        return table(grades.book(username,classId).rows(),"学号,姓名,专业,班级,平时成绩,期末成绩,平时权重,总评,状态,备注,发布时间","studentNo,studentName,major,className,usualScore,examScore,usualWeight,totalScore,status,remark,publishedAt");
    }
    // 校验教学班访问权限后，按资源类型生成名单或成绩 CSV。
    public byte[] teaching(String resource,String username,boolean admin,Long classId) {
        valid(classId!=null,"请选择教学班");
        if(resource.equals("grades"))return gradeTable(username,classId);
        valid(resource.equals("roster"),"不支持导出此资源");
        return table(teaching.roster(username,admin,classId),"学号,姓名,专业,班级,选课时间","studentNo,displayName,major,className,selectedAt");
    }
}
