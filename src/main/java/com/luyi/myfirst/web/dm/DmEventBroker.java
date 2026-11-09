package com.luyi.myfirst.web.dm;

import com.luyi.myfirst.web.dm.dto.DmMessageResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 SSE 订阅表（design D7）。
 *
 * 单实例内存实现：多副本部署时一个进程的推送到不了另一个进程的订阅者，
 * 那时需要换成 Redis pub/sub 广播；当前 K8s 是 1 副本，先不做。
 *
 * 三条约定：
 * <ul>
 *   <li><b>事务提交后才推送</b>：{@link #publishAfterCommit} 在有活动事务时挂到
 *       {@code afterCommit}，否则立即发。否则订阅者可能先收到事件、后读到数据，
 *       刷新一次又消失——「看到了但不存在」比延迟更难排查。</li>
 *   <li><b>断了就从表里删</b>：{@code IOException} / {@code IllegalStateException}
 *       说明连接已经不可恢复，继续留着只会让每个发送者都白遍历一次。</li>
 *   <li><b>同一 emitter 串行写</b>：{@code SseEmitter.send} 不是线程安全的，
 *       两个人几乎同时发消息时，两个请求线程会争同一个连接，用 emitter 自身当锁。</li>
 * </ul>
 */
@Component
public class DmEventBroker {

    /**
     * 连接存活上限。EventSource 会在断开后自动重连，所以这个值只需
     * 「长到不打断正常阅读、短到僵尸连接不会一直占着」。
     */
    static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();

    /** 事件名：前端 {@code EventSource.addEventListener('message', ...)} 按它订阅。 */
    public static final String EVENT_NAME = "message";

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * 为会话登记一个新的订阅者。
     *
     * 调用方必须先完成可见性判定（非参与者 404）：这里的返回值一旦交给 Spring，
     * 响应头就已经发出去了，再抛异常也改写不了状态码。
     */
    public SseEmitter subscribe(String conversationPublicId) {
        return subscribe(conversationPublicId, new SseEmitter(STREAM_TIMEOUT_MILLIS));
    }

    /**
     * 与 {@link #subscribe(String)} 相同的登记逻辑，但在调用方给定 emitter 时使用它。
     *
     * 只供测试注入一个可观测的 {@code SseEmitter} 子类：生产路径固定用
     * {@code new SseEmitter}，不把「怎么造 emitter」变成外部可配置的事。
     */
    SseEmitter subscribe(String conversationPublicId, SseEmitter emitter) {
        subscribers.computeIfAbsent(conversationPublicId, key -> ConcurrentHashMap.newKeySet())
                .add(emitter);
        emitter.onCompletion(() -> remove(conversationPublicId, emitter));
        emitter.onTimeout(() -> {
            remove(conversationPublicId, emitter);
            emitter.complete();
        });
        emitter.onError(failure -> remove(conversationPublicId, emitter));
        return emitter;
    }

    /**
     * 推送一条新消息；有活动事务时推迟到提交之后。
     *
     * 事件 id 用消息的公开标识：EventSource 断线重连会带 {@code Last-Event-ID}，
     * 服务端目前不据此重放（重连由前端重拉首页补齐），但这个 id 让浏览器天然的
     * 去重语义有依据。
     */
    public void publishAfterCommit(String conversationPublicId, DmMessageResponse message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(conversationPublicId, message);
                }
            });
            return;
        }
        publish(conversationPublicId, message);
    }

    private void publish(String conversationPublicId, DmMessageResponse message) {
        Set<SseEmitter> emitters = subscribers.get(conversationPublicId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(conversationPublicId, emitter, message);
        }
    }

    private void send(String conversationPublicId, SseEmitter emitter, DmMessageResponse message) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(EVENT_NAME)
                        .id(message.publicId())
                        .data(message, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException broken) {
            // 客户端已断开：不必再 complete（同样会抛），移除订阅后由超时机制回收。
            remove(conversationPublicId, emitter);
        }
    }

    void remove(String conversationPublicId, SseEmitter emitter) {
        subscribers.computeIfPresent(conversationPublicId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /** 当前订阅者数量；供测试断言「断开后被移除」。 */
    int subscriberCount(String conversationPublicId) {
        Set<SseEmitter> emitters = subscribers.get(conversationPublicId);
        return emitters == null ? 0 : emitters.size();
    }
}
