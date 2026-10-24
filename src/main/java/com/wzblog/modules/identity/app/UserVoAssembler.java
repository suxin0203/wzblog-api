package com.wzblog.modules.identity.app;

import com.wzblog.common.profile.TitleBrief;
import com.wzblog.common.profile.TitleLookup;
import com.wzblog.common.util.Times;
import com.wzblog.modules.identity.api.vo.AdminUserVO;
import com.wzblog.modules.identity.api.vo.PublicUserProfileVO;
import com.wzblog.modules.identity.api.vo.TitleBriefVO;
import com.wzblog.modules.identity.api.vo.UserSelfVO;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.infra.config.AuthProperties;
import org.springframework.stereotype.Component;

/**
 * User 实体 → VO；公开资料严格白名单。
 */
@Component
public class UserVoAssembler {

    private final TitleLookup titleLookup;
    private final AuthProperties properties;

    public UserVoAssembler(TitleLookup titleLookup, AuthProperties properties) {
        this.titleLookup = titleLookup;
        this.properties = properties;
    }

    public UserSelfVO toSelf(User user) {
        return new UserSelfVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getEmailVerifiedAt() != null,
                avatarUrl(user.getAvatarKey()),
                user.getBio(),
                user.getRole().name(),
                user.getStatus().name(),
                title(user.getActiveTitleId()),
                Times.toInstant(user.getLastLoginAt()),
                Times.toInstant(user.getCreatedAt()));
    }

    public PublicUserProfileVO toPublic(User user) {
        return new PublicUserProfileVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                avatarUrl(user.getAvatarKey()),
                user.getBio(),
                user.getRole().name(),
                title(user.getActiveTitleId()),
                Times.toInstant(user.getCreatedAt()));
    }

    public AdminUserVO toAdmin(User user) {
        return new AdminUserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                avatarUrl(user.getAvatarKey()),
                user.getRole().name(),
                user.getStatus().name(),
                Times.toInstant(user.getLastLoginAt()),
                user.getLastLoginIp(),
                Times.toInstant(user.getCreatedAt()));
    }

    public String avatarUrl(String avatarKey) {
        if (avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        String base = properties.getAvatarPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return avatarKey;
        }
        return base.endsWith("/") ? base + avatarKey : base + "/" + avatarKey;
    }

    private TitleBriefVO title(Long activeTitleId) {
        if (activeTitleId == null) {
            return null;
        }
        return titleLookup.findById(activeTitleId)
                .map(UserVoAssembler::toTitleVo)
                .orElse(null);
    }

    private static TitleBriefVO toTitleVo(TitleBrief brief) {
        return new TitleBriefVO(brief.id(), brief.name(), brief.cssClass());
    }
}