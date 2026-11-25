package cn.edu.cczu.course.module.learning.service;

import cn.edu.cczu.course.module.learning.mapper.LearningMapper;
import cn.edu.cczu.course.module.learning.model.LearningModels.Notice;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.NoticeRequest;
import cn.edu.cczu.course.module.academic.model.AcademicModels.PageResult;
import cn.edu.cczu.course.module.academic.service.AcademicSupport;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 管理公告发布、可见范围和已读状态，并用版本号处理同时编辑的情况。 */
@Service
public class NoticeService {
    private final LearningMapper mapper;private final UserMapper users;private final AcademicSupport support;
    public NoticeService(LearningMapper mapper,UserMapper users,AcademicSupport support) {this.mapper=mapper;this.users=users;this.support=support;}
    // 用同一组条件分别查询当前页和记录总数。
    public PageResult<Notice> list(String username,boolean admin,int page,int size,String keyword,boolean unread) {
        var user=require(users.findByUsername(username),"账号不存在");int offset=offset(page,size);var pattern=pattern(keyword);var now=support.now();
        return new PageResult<>(mapper.notices(user.id(),user.role(),admin,pattern,unread,now,offset,size),mapper.countNotices(user.id(),user.role(),admin,pattern,unread,now),page,size);
    }
    // 在读取公告时按当前账号的角色与时间再次检查可见性。
    public Notice detail(String username,Long id) {
        var user=require(users.findByUsername(username),"账号不存在");var notice=require(mapper.notice(id,user.id()),"公告不存在");var now=support.now();
        // 打开详情时再次判断公告的发布状态、面向角色与展示时间。
        boolean visible=notice.status().equals("PUBLISHED")&&(notice.audience().equals("ALL")||notice.audience().equals(user.role()))
            &&(notice.visibleFrom()==null||!notice.visibleFrom().isAfter(now))&&(notice.expiresAt()==null||notice.expiresAt().isAfter(now));
        return require(visible?notice:null,"公告不存在或当前不可见");
    }
    // 确保展示时段有效，并禁止发布已经过期的公告。
    private void validate(NoticeRequest body) {
        valid(body.expiresAt()==null||body.visibleFrom()==null||body.expiresAt().isAfter(body.visibleFrom()),"公告截止时间必须晚于开始时间");
        valid(!body.status().equals("PUBLISHED")||body.expiresAt()==null||body.expiresAt().isAfter(support.now()),"不能发布已过期的公告");
    }
    // 创建公告时要求初始版本正确，并按发布状态设置发布时间。
    @Transactional
    public void create(String username,NoticeRequest body) {
        validate(body);valid(body.revision()==0,"新公告版本应为 0");var now=support.now();
        mapper.insertNotice(body,users.findByUsername(username).id(),body.status().equals("PUBLISHED")?now:null,now);
    }
    // 保留首次发布时间，用旧版本号限制并发修改。
    @Transactional
    public void update(String username,Long id,NoticeRequest body) {
        validate(body);var current=require(mapper.notice(id,users.findByUsername(username).id()),"公告不存在");
        // 发布时间一旦建立就继续保留。撤回只影响当前显示状态，能区分从未发布过的草稿。
        var now=support.now();var published=current.publishedAt()!=null?current.publishedAt():body.status().equals("PUBLISHED")?now:null;
        check(mapper.updateNotice(id,body,published,now)==1,"公告已被其他操作修改，请刷新重试");
    }
    // 只有从未发布的草稿可以删除，已发布过的公告保留历史。
    @Transactional
    public void remove(String username,Long id,int revision) {
        var notice=require(mapper.notice(id,users.findByUsername(username).id()),"公告不存在");
        check(notice.publishedAt()==null&&notice.status().equals("DRAFT"),"已发布过的公告请撤回为草稿，保留历史记录");
        check(mapper.deleteNotice(id,revision)==1,"公告已变化，请刷新重试");
    }
    // 将当前用户看过的公告版本保存为已读。
    public void read(String username,Long id,int revision) {
        // 先按详情规则再次确认读者有权访问。若公告在打开后已更新，提示读者打开新版本再标记。
        var notice=detail(username,id);check(notice.revision()==revision,"公告已更新，请重新打开阅读");
        var user=users.findByUsername(username);var now=support.now();
        // 已读记录保存用户看到的公告版本；同一用户并发标记时，新增失败的一方再尝试更新。
        if(mapper.updateRead(id,user.id(),revision,now)==0) {
            try {mapper.insertRead(id,user.id(),revision,now);}catch(DuplicateKeyException ignored) {mapper.updateRead(id,user.id(),revision,now);}
        }
    }
}
