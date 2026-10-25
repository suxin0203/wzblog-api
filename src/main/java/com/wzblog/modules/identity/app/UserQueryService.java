package com.wzblog.modules.identity.app;

import com.wzblog.common.profile.UserBrief;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨模块只读用户信息端口（00 §9.2）。不暴露 email/openid 等私密字段。
 */
@Service
public class UserQueryService {

    private final UserMapper userMapper;

    public UserQueryService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public UserBrief getBrief(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return toBrief(user);
    }

    /** 当前佩戴称号 id（M4 账户视图读取，无佩戴返回 null）。 */
    @Transactional(readOnly = true)
    public Long getActiveTitleId(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getActiveTitleId();
    }

    @Transactional(readOnly = true)
    public Map<Long, UserBrief> getBriefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBrief> result = new LinkedHashMap<>();
        for (User user : userMapper.selectBatchIds(ids)) {
            UserBrief brief = toBrief(user);
            if (brief != null) {
                result.put(brief.id(), brief);
            }
        }
        return result;
    }

    private UserBrief toBrief(User user) {
        if (user == null || user.isDeleted() || user.getStatus() != com.wzblog.modules.identity.domain.UserStatus.ACTIVE) {
            return null;
        }
        return new UserBrief(user.getId(), user.getNickname(), user.getAvatarKey());
    }
}