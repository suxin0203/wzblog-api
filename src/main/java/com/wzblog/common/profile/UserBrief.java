package com.wzblog.common.profile;

/**
 * 作者摘要（跨模块批量装配）：activeTitle 为用户当前佩戴称号名（未佩戴为 null），
 * 供评论/文章作者旁的称号徽标展示。
 */
public record UserBrief(Long id, String nickname, String avatarKey, String activeTitle) {
}
