package com.luyi.myfirst.service.post;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.model.PostCommand;
import com.luyi.myfirst.service.post.model.PostPage;
import com.luyi.myfirst.service.post.model.PostView;

/**
 * 帖子的读写能力。
 *
 * 作者身份一律由调用方传入的 {@link VerifiedToken} 表达，服务层**不**从请求体取作者：
 * design D6 的判据是「请求体里不存在作者字段」，把它留在服务层之外实现，
 * 才不会有第二条入口顺手采信客户端传来的 id。
 *
 * 读接口的 {@code viewer} 允许为 {@code null}（游客）：草稿的可见性判定需要它，
 * 而公开路由上的读请求本身不需要令牌。
 */
public interface PostService {

    /** 发布/新建：作者取自令牌，返回 201 用的详情视图。 */
    PostView publish(VerifiedToken author, PostCommand command);

    /** 编辑：先判存在（404）再判作者（403），返回更新后的详情视图。 */
    PostView edit(VerifiedToken author, String postPublicId, PostCommand command);

    /** 删除：软删除，先判存在（404）再判作者（403），不返回内容。 */
    void delete(VerifiedToken author, String postPublicId);

    /** 详情：已发布对所有人可见，草稿仅作者可见（他人得 404 而非 403）。 */
    PostView read(String postPublicId, VerifiedToken viewer);

    /** 列表：只含已发布，游标分页；{@code cursor} 为 null 表示第一页。 */
    PostPage feed(String cursor, Integer limit);
}
