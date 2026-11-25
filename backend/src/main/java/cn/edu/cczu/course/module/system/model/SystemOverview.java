package cn.edu.cczu.course.module.system.model;

/** 承载管理员工作台展示的系统统计数据。 */
// 每个字段对应工作台或概览接口上的一项计数。
public record SystemOverview(long studentCount, long teacherCount, long courseCount, long teachingClassCount) {
}
