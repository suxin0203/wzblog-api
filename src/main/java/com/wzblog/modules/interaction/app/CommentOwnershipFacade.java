package com.wzblog.modules.interaction.app;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.util.Times;
import com.wzblog.modules.interaction.domain.Comment;
import com.wzblog.modules.interaction.infra.mapper.CommentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论归属批量迁移门面（跨模块最小出口，纯类型无 VO）：账号注销移交时把临时账号的
 * 评论改挂目标账号（B18 bind-migrate 用）。user_id（作者）与 reply_to_user_id（回复目标）
 * 双列条件更新；含软删行（归属一致性优先于展示过滤）。
 */
@Service
public class CommentOwnershipFacade {

    private final CommentMapper commentMapper;

    public CommentOwnershipFacade(CommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    /** 把 fromUserId 的评论（作者列 + 回复目标列）批量改挂到 toUserId，返回改动行数。 */
    @Transactional
    public long migrateOwnership(Long fromUserId, Long toUserId) {
        int authors = commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getUserId, fromUserId)
                .set(Comment::getUserId, toUserId)
                .set(Comment::getUpdatedAt, Times.nowUtc()));
        int replyTargets = commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getReplyToUserId, fromUserId)
                .set(Comment::getReplyToUserId, toUserId));
        return authors + replyTargets;
    }
}
