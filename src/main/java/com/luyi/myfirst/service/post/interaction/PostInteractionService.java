package com.luyi.myfirst.service.post.interaction;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.interaction.model.BookmarkResult;
import com.luyi.myfirst.service.post.interaction.model.CommentCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.CommentPage;
import com.luyi.myfirst.service.post.interaction.model.CommentView;
import com.luyi.myfirst.service.post.interaction.model.VoteCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.VoteResult;

/**
 * 帖子互动能力：评论、投票、收藏。
 *
 * 作者身份一律由调用方传入的 {@link VerifiedToken} 表达，请求体里不存在作者字段。
 */
public interface PostInteractionService {

    /** 发表评论；parentPublicId 为 null 表示顶级评论。 */
    CommentView createComment(VerifiedToken author, String postPublicId, CommentCreateCommand command);

    /** 删除评论；仅限作者。 */
    void deleteComment(VerifiedToken author, String postPublicId, String commentPublicId);

    /**
     * 查询某帖子的评论列表（含已删除的 MUST NOT 出现）。
     *
     * {@code cursor} 为 null / 空白时返回第一页；列表按创建时间倒序。
     */
    CommentPage listComments(String postPublicId, String cursor, Integer limit);

    /** 投票；同一账户同一帖子只保留一条有效记录，再次投票替换方向。 */
    VoteResult vote(VerifiedToken author, String postPublicId, VoteCreateCommand command);

    /** 取消投票；仅限投票人。 */
    void cancelVote(VerifiedToken author, String postPublicId);

    /** 收藏；幂等。 */
    BookmarkResult bookmark(VerifiedToken author, String postPublicId);

    /** 取消收藏；仅限收藏人。 */
    void cancelBookmark(VerifiedToken author, String postPublicId);
}
