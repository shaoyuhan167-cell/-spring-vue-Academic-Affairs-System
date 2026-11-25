package cn.edu.cczu.course.module.system.service;

import cn.edu.cczu.course.module.system.mapper.SystemMapper;
import cn.edu.cczu.course.module.system.model.SystemOverview;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

/** 读取概览统计和数据库类型，供系统接口返回。 */
@Service
public class SystemService {
    private final SystemMapper systemMapper;
    private final JdbcTemplate jdbcTemplate;

    public SystemService(SystemMapper systemMapper, JdbcTemplate jdbcTemplate) {
        this.systemMapper = systemMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // 汇总账号、课程及选课等记录数量，供管理员查看。
    public SystemOverview overview() {
        return systemMapper.overview();
    }

    // 读取当前连接使用的数据库产品名称。
    public String databaseProductName() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        // 通过实际打开的 JDBC 连接读取产品名，健康检查能反映当前应用实际连接的数据库。
        return jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
    }
}
