package cn.edu.cczu.course.module.learning.service;

import cn.edu.cczu.course.module.learning.mapper.LearningMapper;
import cn.edu.cczu.course.module.learning.model.LearningModels.Profile;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.ProfileRequest;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.academic.service.AcademicSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 按当前用户名读取和保存个人资料。 */
@Service
public class ProfileService {
    private final LearningMapper mapper;private final UserMapper users;private final AcademicSupport support;
    public ProfileService(LearningMapper mapper,UserMapper users,AcademicSupport support) {this.mapper=mapper;this.users=users;this.support=support;}
    // 按登录用户名读取个人资料，找不到时返回资源不存在。
    public Profile get(String username) {return require(mapper.profile(username),"个人资料不存在");}
    // 只修改联系信息与简介，首次保存时新增资料记录。
    @Transactional
    public void update(String username,ProfileRequest body) {
        // 资料内容与账户档案相互独立。更新方法先找到当前账号，再按是否已有资料决定新增或更新。
        var id=require(users.findByUsername(username),"账号不存在").id();
        var safe=new ProfileRequest(body.email().trim(),body.phone().trim(),body.bio().trim());
        if(mapper.profileExists(id)==0) mapper.insertProfile(id,safe,support.now());else mapper.updateProfile(id,safe,support.now());
    }
}
