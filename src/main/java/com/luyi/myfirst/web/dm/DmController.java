package com.luyi.myfirst.web.dm;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.dm.DmService;
import com.luyi.myfirst.service.dm.model.DmConversationPage;
import com.luyi.myfirst.service.dm.model.DmConversationSummary;
import com.luyi.myfirst.service.dm.model.DmMessagePage;
import com.luyi.myfirst.service.dm.model.DmMessageView;
import com.luyi.myfirst.service.dm.model.DmOpenResult;
import com.luyi.myfirst.service.dm.model.DmPeer;
import com.luyi.myfirst.service.dm.model.DmSendCommand;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.dm.dto.CreateConversationRequest;
import com.luyi.myfirst.web.dm.dto.DmConversationListResponse;
import com.luyi.myfirst.web.dm.dto.DmConversationResponse;
import com.luyi.myfirst.web.dm.dto.DmMessageListResponse;
import com.luyi.myfirst.web.dm.dto.DmMessageResponse;
import com.luyi.myfirst.web.dm.dto.DmPeerResponse;
import com.luyi.myfirst.web.dm.dto.DmUnreadCountResponse;
import com.luyi.myfirst.web.dm.dto.SendMessageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 私信 REST 端点（七个：列表、发起、消息分页、发送、标记已读、总未读、推送）。
 *
 * 与其它控制器同一条分工：身份从 request attribute 取、DTO 与 service 模型互转、
 * 返回正确的 HTTP 状态码；业务判定全部在 {@link DmService}。
 *
 * 状态码是 spec 的验收点而不是风格选择：发起会话首次 201、重复 200、
 * 发送消息 201，这三处一旦混成一致的 200，spec 的 Scenario 就无从区分。
 */
@RestController
@RequestMapping("/api/dm")
public class DmController {

    private static final String VERIFIED = AuthInterceptor.VERIFIED_ATTRIBUTE;

    private final DmService dmService;
    private final DmEventBroker events;

    public DmController(DmService dmService, DmEventBroker events) {
        this.dmService = dmService;
        this.events = events;
    }

    @GetMapping("/conversations")
    DmConversationListResponse conversations(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @RequestParam(required = false) Integer limit) {
        DmConversationPage page = dmService.conversations(me, limit);
        return new DmConversationListResponse(
                page.items().stream().map(DmController::toResponse).toList());
    }

    @PostMapping("/conversations")
    ResponseEntity<DmConversationResponse> create(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @RequestBody(required = false) CreateConversationRequest request) {
        // 空 body / 缺字段是参数问题（400），不是「对方不存在」（404）：
        // 把两者混成同一个码，排查时会以为是数据问题。
        if (request == null || request.peerPublicId() == null || request.peerPublicId().isBlank()) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        DmOpenResult result = dmService.open(me, request.peerPublicId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toResponse(result.conversation()));
    }

    @GetMapping("/conversations/{publicId}/messages")
    DmMessageListResponse messages(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @PathVariable String publicId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        DmMessagePage page = dmService.messages(me, publicId, cursor, limit);
        return new DmMessageListResponse(
                page.items().stream().map(DmController::toMessage).toList(),
                page.nextCursor());
    }

    @PostMapping("/conversations/{publicId}/messages")
    ResponseEntity<DmMessageResponse> send(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @PathVariable String publicId,
            @RequestBody(required = false) SendMessageRequest request) {
        DmMessageView saved = dmService.send(me, publicId,
                new DmSendCommand(request == null ? null : request.content()));
        DmMessageResponse body = toMessage(saved);
        // 推送挂在 service 返回之后：此刻消息已经提交，订阅者收到的每条事件
        // 都对应一条读得到的数据。
        events.publishAfterCommit(publicId, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/conversations/{publicId}/read")
    DmConversationResponse markRead(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @PathVariable String publicId) {
        return toResponse(dmService.markRead(me, publicId));
    }

    @GetMapping("/unread-count")
    DmUnreadCountResponse unreadCount(@RequestAttribute(VERIFIED) VerifiedToken me) {
        return new DmUnreadCountResponse(dmService.unreadCount(me));
    }

    /**
     * 会话内新消息推送（design D7）。
     *
     * 先做参与者校验再返回 emitter：非参与者必须拿到 404，而 emitter 一旦返回，
     * 响应头已经发出，错误就只能写进一个本来准备发事件的流里。
     */
    @GetMapping("/conversations/{publicId}/stream")
    SseEmitter stream(
            @RequestAttribute(VERIFIED) VerifiedToken me,
            @PathVariable String publicId) {
        dmService.view(me, publicId);
        return events.subscribe(publicId);
    }

    private static DmConversationResponse toResponse(DmConversationSummary summary) {
        return new DmConversationResponse(
                summary.publicId(),
                toPeer(summary.peer()),
                summary.messageCount(),
                summary.unreadCount(),
                summary.lastMessagePreview(),
                summary.lastMessageAt(),
                summary.createdAt());
    }

    private static DmPeerResponse toPeer(DmPeer peer) {
        return new DmPeerResponse(peer.publicId(), peer.displayName(), peer.avatarUrl());
    }

    private static DmMessageResponse toMessage(DmMessageView view) {
        return new DmMessageResponse(
                view.publicId(),
                view.senderPublicId(),
                view.content(),
                view.createdAt());
    }
}
