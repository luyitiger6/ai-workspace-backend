package com.luyi.myfirst.service.dm;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.dm.model.DmConversationPage;
import com.luyi.myfirst.service.dm.model.DmConversationSummary;
import com.luyi.myfirst.service.dm.model.DmMessagePage;
import com.luyi.myfirst.service.dm.model.DmMessageView;
import com.luyi.myfirst.service.dm.model.DmOpenResult;
import com.luyi.myfirst.service.dm.model.DmSendCommand;

/**
 * 一对一私信的读写能力（六个行为，外加一个只读的参与者校验视图）。
 *
 * 请求者身份一律由调用方传入的 {@link VerifiedToken} 表达，服务层**不**从请求体取发送者：
 * 与 {@code PostService} 同一条规矩——把「作者来自令牌」留在服务层之外实现，
 * 才不会有第二条入口顺手采信客户端传来的 id。
 *
 * 「不是参与者」与「会话不存在」共用同一个 404（design D8）：
 * 区分它们等于告诉对方「这里确实有一条你插不进去的会话」。
 */
public interface DmService {

    /** 会话列表：只含本人参与的会话，按最近活跃时间倒序；limit 钳制到 1–50，默认 20。 */
    DmConversationPage conversations(VerifiedToken me, Integer limit);

    /** 发起会话：该对已有会话时返回既有会话（{@code created=false}），首次创建为 {@code true}。 */
    DmOpenResult open(VerifiedToken me, String peerPublicId);

    /**
     * 单个会话的摘要视图，只有参与者可读，其余一律 404。
     *
     * SSE 订阅端点用它做建立连接前的可见性判定：{@code SseEmitter} 一旦返回，
     * 错误响应就再没有机会写回去了，所以判定必须发生在返回之前。
     */
    DmConversationSummary view(VerifiedToken me, String conversationPublicId);

    /** 消息分页：按创建时间倒序，游标为 null 表示第一页；非参与者一律 404。 */
    DmMessagePage messages(VerifiedToken me, String conversationPublicId, String cursor, Integer limit);

    /** 发送消息：同一事务内写消息并更新会话摘要与对方未读，返回新消息。 */
    DmMessageView send(VerifiedToken me, String conversationPublicId, DmSendCommand command);

    /** 标记已读到当前最新一条，按已读位置重算未读；返回更新后的会话摘要。 */
    DmConversationSummary markRead(VerifiedToken me, String conversationPublicId);

    /** 顶栏总未读：请求者在各会话中那一侧计数之和。 */
    long unreadCount(VerifiedToken me);
}
