package com.wzblog.modules.identity.app;

import com.wzblog.common.profile.UserBrief;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.infra.mapper.TitleNameMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨模块只读用户信息端口（00 §9.2）。不暴露 email/openid 等私密字段。
 * UserBrief 携带当前佩戴称号名（评论/文章作者旁徽标展示用）。
 */
@Service
public class UserQueryService {

    private final UserMapper userMapper;
    private final TitleNameMapper titleNameMapper;

    public UserQueryService(UserMapper userMapper, TitleNameMapper titleNameMapper) {
        this.userMapper = userMapper;
        this.titleNameMapper = titleNameMapper;
    }

    @Transactional(readOnly = true)
    public UserBrief getBrief(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return toBrief(user, activeTitleNames(List.of(user)));
    }

    /** M7 看板：区间注册数。 */
    @Transactional(readOnly = true)
    public long countRegisteredBetween(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>();
        wrapper.ge(User::getCreatedAt, from).lt(User::getCreatedAt, to);
        return userMapper.selectCount(wrapper);
    }

    /** M7 看板：总用户数。 */
    @Transactional(readOnly = true)
    public long countUsers() {
        return userMapper.selectCount(null);
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
        List<User> users = userMapper.selectBatchIds(ids);
        Map<Long, String> titleNames = activeTitleNames(users);
        Map<Long, UserBrief> result = new LinkedHashMap<>();
        for (User user : users) {
            UserBrief brief = toBrief(user, titleNames);
            if (brief != null) {
                result.put(brief.id(), brief);
            }
        }
        return result;
    }

    /** 佩戴中的称号 id → 名称（称号总量小，一次全量查询换批量解析；软删称号不再展示）。 */
    private Map<Long, String> activeTitleNames(List<User> users) {
        List<Long> titleIds = users.stream()
                .map(User::getActiveTitleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (titleIds.isEmpty()) {
            return Map.of();
        }
        return titleNameMapper.selectAllActive().stream()
                .filter(t -> titleIds.contains(t.id()))
                .collect(Collectors.toMap(TitleNameMapper.TitleName::id, TitleNameMapper.TitleName::name,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private UserBrief toBrief(User user, Map<Long, String> titleNames) {
        if (user == null || user.isDeleted() || user.getStatus() != com.wzblog.modules.identity.domain.UserStatus.ACTIVE) {
            return null;
        }
        return new UserBrief(user.getId(), user.getNickname(), user.getAvatarKey(),
                user.getActiveTitleId() == null ? null : titleNames.get(user.getActiveTitleId()));
    }
}