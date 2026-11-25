package cn.edu.cczu.course.module.audit.model;

import java.time.LocalDateTime;

/** 记录一次审计操作的执行人、对象、内容和时间。 */
// 目标信息使用类型和编号表达，所以一张表可以记录账号、选退课、教学班、成绩等不同操作。
public record AuditLog(Long id, Long actorId, String actorName, String actorRole, String action,
                       String targetType, Long targetId, String detail, String clientIp, LocalDateTime createdAt) { }
