package com.luyi.myfirst.service.dm.impl;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.DmErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.dm.entity.DmConversation;
import com.luyi.myfirst.dao.dm.entity.DmMessage;
import com.luyi.myfirst.dao.dm.repository.DmConversationRepository;
import com.luyi.myfirst.dao.dm.repository.DmMessageRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.dm.DmService;
import com.luyi.myfirst.service.dm.model.DmConversationPage;
import com.luyi.myfirst.service.dm.model.DmConversationSummary;
import com.luyi.myfirst.service.dm.model.DmMessagePage;
import com.luyi.myfirst.service.dm.model.DmMessageView;
import com.luyi.myfirst.service.dm.model.DmOpenResult;
import com.luyi.myfirst.service.dm.model.DmPeer;
import com.luyi.myfirst.service.dm.model.DmSendCommand;
import com.luyi.myfirst.service.dm.support.DmCursor;
import com.luyi.myfirst.service.dm.support.DmInputRules;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 私信服务实现（design D1 / D2 / D11）。
 *
 * 三条不变式：
 * <ul>
 *   <li><b>发起会话的唯一性靠库</b>：「先查后插」之间永远有并发窗口，所以插入走
 *       {@code ON DUPLICATE KEY UPDATE}，撞键后回查既有会话，而不是捕获异常——
 *       唯一键异常会把事务标记为 rollback-only，回查反而查不到。</li>
 *   <li><b>非参与者一律 404</b>：会话存在性不向无关用户暴露（design D8）。</li>
 *   <li><b>已读不盲清零</b>：按「已读位置重算」把并发到达的新消息留下（spec
 *       「已读与新消息并发时不丢」）。</li>
 * </ul>
 *
 * 消息摘要在读取路径上由一次批量查询装配（{@code findLatestOfEach}），
 * 会话表不存摘要列——摘要是消息的投影，多一列就多一个可能过期的真源。
 */
@Service
public class DmServiceImpl implements DmService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int PREVIEW_MAX_CHARS = 50;

    private final DmConversationRepository conversations;
    private final DmMessageRepository messages;
    private final AccountRepository accounts;

    public DmServiceImpl(DmConversationRepository conversations,
                         DmMessageRepository messages,
                         AccountRepository accounts) {
        this.conversations = conversations;
        this.messages = messages;
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public DmConversationPage conversations(VerifiedToken me, Integer limit) {
        Long meId = me.account().id();
        List<DmConversation> rows = conversations.findByParticipant(
                meId, PageRequest.of(0, normalizeLimit(limit)));
        Map<Long, DmMessage> latest = latestOf(rows);
        Map<Long, Account> peers = accountsOf(rows.stream().map(c -> c.peerOf(meId)).toList());
        List<DmConversationSummary> items = rows.stream()
                .map(c -> toSummary(c, meId, peers.get(c.peerOf(meId)), latest.get(c.id())))
                .toList();
        return new DmConversationPage(items);
    }

    @Override
    @Transactional
    public DmOpenResult open(VerifiedToken me, String peerPublicId) {
        Account peer = requirePeer(peerPublicId);
        Long meId = me.account().id();
        if (peer.id().equals(meId)) {
            throw new BizException(DmErrorEnum.DM_SELF_CONVERSATION);
        }
        long low = Math.min(meId, peer.id());
        long high = Math.max(meId, peer.id());

        Optional<DmConversation> existing =
                conversations.findByParticipantLowIdAndParticipantHighIdAndDeletedAtIsNull(low, high);
        if (existing.isPresent()) {
            return new DmOpenResult(summaryWithLatest(existing.get(), meId, peer), false);
        }

        // 创建时即填 last_message_at：没消息的会话按创建时间参与同一排序（spec），
        // 而不是被 NULL 沉底。
        String candidate = Ulid.generate();
        conversations.insertIfAbsent(candidate, low, high, Instant.now());
        DmConversation resolved = conversations
                .findByParticipantLowIdAndParticipantHighIdAndDeletedAtIsNull(low, high)
                .orElseThrow(() -> new IllegalStateException("dm conversation vanished after insert"));
        boolean created = candidate.equals(resolved.publicId());
        return new DmOpenResult(summaryWithLatest(resolved, meId, peer), created);
    }

    @Override
    @Transactional(readOnly = true)
    public DmConversationSummary view(VerifiedToken me, String conversationPublicId) {
        DmConversation conversation = requireParticipant(conversationPublicId, me.account().id());
        return summaryWithLatest(conversation, me.account().id(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public DmMessagePage messages(VerifiedToken me, String conversationPublicId, String cursor, Integer limit) {
        DmConversation conversation = requireParticipant(conversationPublicId, me.account().id());
        int size = normalizeLimit(limit);
        DmCursor after = DmCursor.decode(cursor);
        Pageable pageable = PageRequest.of(0, size + 1);

        List<DmMessage> rows = after == null
                ? messages.findLatest(conversation.id(), pageable)
                : messages.findBefore(conversation.id(), after.id(), pageable);

        boolean hasMore = rows.size() > size;
        List<DmMessage> page = hasMore ? rows.subList(0, size) : rows;
        Map<Long, String> senders = publicIdsOf(page.stream().map(DmMessage::senderId).toList());
        List<DmMessageView> items = page.stream()
                .map(m -> new DmMessageView(m.publicId(), senders.get(m.senderId()), m.content(), m.createdAt()))
                .toList();
        String nextCursor = hasMore ? new DmCursor(page.get(page.size() - 1).id()).encode() : null;
        return new DmMessagePage(items, nextCursor);
    }

    @Override
    @Transactional
    public DmMessageView send(VerifiedToken me, String conversationPublicId, DmSendCommand command) {
        Long meId = me.account().id();
        // 先判可见性再判参数：非参与者不该从「400 还是 404」里读出会话是否存在。
        DmConversation conversation = requireParticipant(conversationPublicId, meId);
        String content = DmInputRules.requireContent(command == null ? null : command.content());

        DmMessage saved = messages.saveAndFlush(
                DmMessage.create(Ulid.generate(), conversation.id(), meId, content));
        conversations.applyNewMessage(conversation.id(), meId, saved.createdAt());
        return new DmMessageView(saved.publicId(), me.account().publicId(), saved.content(), saved.createdAt());
    }

    @Override
    @Transactional
    public DmConversationSummary markRead(VerifiedToken me, String conversationPublicId) {
        Long meId = me.account().id();
        DmConversation conversation = requireParticipant(conversationPublicId, meId);

        // 先取会话内最大 id，再按位置重算：这两步之间到达的新消息 id 更大，
        // 会被子查询重新计入未读，而不是被「清零」抹掉。
        Long latestId = messages.findMaxId(conversation.id());
        if (latestId != null) {
            if (conversation.participantLowId().equals(meId)) {
                conversations.markReadForLow(conversation.id(), meId, latestId);
            } else {
                conversations.markReadForHigh(conversation.id(), meId, latestId);
            }
        }
        // markRead* 的 clearAutomatically 已清上下文，这里读到的是重算后的真实计数。
        DmConversation refreshed = conversations.findById(conversation.id())
                .orElseThrow(DmServiceImpl::notFound);
        return summaryWithLatest(refreshed, meId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(VerifiedToken me) {
        return conversations.sumUnread(me.account().id());
    }

    private DmConversationSummary summaryWithLatest(DmConversation conversation, Long viewerId, Account knownPeer) {
        Account peer = knownPeer != null
                ? knownPeer
                : accounts.findById(conversation.peerOf(viewerId)).orElse(null);
        DmMessage latest = messages.findLatest(conversation.id(), PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        return toSummary(conversation, viewerId, peer, latest);
    }

    private static DmConversationSummary toSummary(DmConversation c,
                                                   Long viewerId,
                                                   Account peer,
                                                   DmMessage latest) {
        return new DmConversationSummary(
                c.publicId(),
                toPeer(peer),
                c.messageCount(),
                c.unreadCountOf(viewerId),
                latest == null ? null : preview(latest.content()),
                latest == null ? null : latest.createdAt(),
                c.createdAt());
    }

    private static DmPeer toPeer(Account peer) {
        if (peer == null) {
            // 外键保证账户行存在；这里是防御性兜底（账户行被库外手段删除时也不崩）。
            return new DmPeer(null, null, null);
        }
        return new DmPeer(peer.publicId(), peer.displayName(), peer.avatarUrl());
    }

    /** 摘要超过 50 个字符时按码点截断，不加省略号——spec 只要求「截断」。 */
    private static String preview(String content) {
        if (content == null || content.codePointCount(0, content.length()) <= PREVIEW_MAX_CHARS) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, PREVIEW_MAX_CHARS));
    }

    private Map<Long, DmMessage> latestOf(List<DmConversation> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(DmConversation::id).toList();
        return messages.findLatestOfEach(ids).stream()
                .collect(Collectors.toMap(DmMessage::conversationId, Function.identity()));
    }

    private Map<Long, Account> accountsOf(List<Long> accountIds) {
        Set<Long> unique = Set.copyOf(accountIds);
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<Long, Account> result = new HashMap<>();
        accounts.findAllById(unique).forEach(account -> result.put(account.id(), account));
        return result;
    }

    private Map<Long, String> publicIdsOf(List<Long> accountIds) {
        return accountsOf(accountIds).values().stream()
                .collect(Collectors.toMap(Account::id, Account::publicId));
    }

    private DmConversation requireParticipant(String conversationPublicId, Long accountId) {
        if (conversationPublicId == null || conversationPublicId.isBlank()) {
            throw notFound();
        }
        return conversations.findByPublicIdAndDeletedAtIsNull(conversationPublicId)
                .filter(c -> c.includes(accountId))
                .orElseThrow(DmServiceImpl::notFound);
    }

    private Account requirePeer(String peerPublicId) {
        if (peerPublicId == null || peerPublicId.isBlank()) {
            throw peerNotFound();
        }
        return accounts.findByPublicId(peerPublicId)
                .filter(account -> account.deletedAt() == null)
                .orElseThrow(DmServiceImpl::peerNotFound);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        // spec 写的是「取值超出 1–50 时被钳制到边界」，不是「回退到默认值」。
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static BizException notFound() {
        return new BizException(DmErrorEnum.DM_CONVERSATION_NOT_FOUND);
    }

    private static BizException peerNotFound() {
        return new BizException(DmErrorEnum.DM_PEER_NOT_FOUND);
    }
}
