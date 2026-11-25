package cn.edu.cczu.course.module.audit.mapper;

import cn.edu.cczu.course.module.audit.model.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 保存和查询审计记录，供后台按条件分页浏览。 */
public interface AuditMapper {
    @Insert("""
            INSERT INTO audit_log(actor_id,actor_role,action,target_type,target_id,detail,client_ip,created_at)
            VALUES(#{actorId},#{actorRole},#{action},#{targetType},#{targetId,jdbcType=BIGINT},#{detail},#{clientIp},#{createdAt})
            """)
    // 将操作人、动作、目标和请求信息写入审计表。
    void insert(@Param("actorId") Long actorId, @Param("actorRole") String actorRole, @Param("action") String action,
                @Param("targetType") String targetType, @Param("targetId") Long targetId, @Param("detail") String detail,
                @Param("clientIp") String clientIp, @Param("createdAt") LocalDateTime createdAt);

    // 合并执行人的显示名与日志保存的动作摘要，优先加载最近发生的记录。
    String LATEST = """
            SELECT a.id,a.actor_id,u.display_name AS actor_name,a.actor_role,a.action,a.target_type,a.target_id,
                   a.detail,a.client_ip,a.created_at
            FROM audit_log a JOIN sys_user u ON u.id=a.actor_id
            WHERE (#{action} IS NULL OR a.action=#{action})
            ORDER BY a.id DESC
            """;

    // 按时间倒序返回指定数量的审计记录。
    @Select(LATEST + " OFFSET 0 ROWS FETCH NEXT #{size} ROWS ONLY")
    List<AuditLog> latest(@Param("action") String action, @Param("size") int size);
}
