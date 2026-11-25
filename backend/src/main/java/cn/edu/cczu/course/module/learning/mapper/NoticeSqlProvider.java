package cn.edu.cczu.course.module.learning.mapper;

import java.util.Map;

/** 根据用户角色、时间和已读状态拼接公告列表与计数使用的查询。 */
public class NoticeSqlProvider {
    // 按管理视角、角色和发布时间组装公告筛选条件。
    private String query(Map<String,Object> p) {
        String sql=LearningMapper.NOTICE+" WHERE 1=1";
        // 普通读者只看到已发布、面向其角色且当前处于显示时段的公告。后台管理同时包含草稿和历史公告。
        if (!Boolean.TRUE.equals(p.get("admin"))) {
            sql+=" AND a.status='PUBLISHED' AND a.audience IN ('ALL',#{role}) AND (a.visible_from IS NULL OR a.visible_from<=#{now}) AND (a.expires_at IS NULL OR a.expires_at>#{now})";
        }
        if(p.get("pattern")!=null) sql+=" AND (a.title LIKE #{pattern} ESCAPE '~' OR a.content LIKE #{pattern} ESCAPE '~')";
        if(Boolean.TRUE.equals(p.get("unread"))) sql+=" AND (r.notice_revision IS NULL OR r.notice_revision<>a.revision)";
        return sql;
    }
    // 在公告筛选基础上追加排序和分页。
    public String list(Map<String,Object> p) {
        // 先展示置顶公告，再按时间和编号倒序。列表与计数复用同一组筛选条件。
        return query(p) + " ORDER BY a.pinned DESC,a.created_at DESC,a.id DESC"
                + " OFFSET #{offset} ROWS FETCH NEXT #{size} ROWS ONLY";
    }
    // 使用与公告列表相同的条件统计总数。
    public String count(Map<String,Object> p) { return "SELECT COUNT(*) FROM ("+query(p)+") notice_count"; }
}
