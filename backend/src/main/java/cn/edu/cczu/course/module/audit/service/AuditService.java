package cn.edu.cczu.course.module.audit.service;

import cn.edu.cczu.course.module.academic.service.AcademicSupport;
import cn.edu.cczu.course.module.audit.mapper.AuditMapper;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static cn.edu.cczu.course.module.academic.service.AcademicSupport.require;

/** 将关键业务操作写入审计日志，并支持从当前会话获取操作人。 */
@Service
public class AuditService {
    private final AuditMapper mapper;
    private final UserMapper users;
    private final AcademicSupport support;

    public AuditService(AuditMapper mapper, UserMapper users, AcademicSupport support) {
        this.mapper = mapper;
        this.users = users;
        this.support = support;
    }

    // 解析操作人并记录审计信息，供管理员事后追查。
    @Transactional
    public void record(String username, String action, String targetType, Long targetId, String detail) {
        var actor = require(users.findByUsername(username), "账号不存在");
        // 请求上下文存在时附上来源信息。维护脚本或测试直接调用业务方法时也允许记录操作。
        var request = RequestContextHolder.getRequestAttributes();
        String ip = request instanceof ServletRequestAttributes servlet ? servlet.getRequest().getRemoteAddr() : "";
        mapper.insert(actor.id(), actor.role(), action, targetType, targetId, detail, ip, support.now());
    }

    // 从当前安全上下文取得操作人，再写入操作记录。
    @Transactional
    public void recordCurrentActor(String action, String targetType, Long targetId, String detail) {
        var actor = SecurityContextHolder.getContext().getAuthentication();
        // 初始化脚本与直接服务调用没有登录操作者。
        if (actor != null && actor.isAuthenticated() && !(actor instanceof AnonymousAuthenticationToken)) {
            record(actor.getName(), action, targetType, targetId, detail);
        }
    }
}
