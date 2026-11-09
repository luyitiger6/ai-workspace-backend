package com.luyi.myfirst.web.dm;

import com.luyi.myfirst.web.dm.dto.DmMessageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅表的三条契约（tasks 4.4）：推一次、断连清、提交后才发。
 *
 * 不起 Spring 上下文：{@link SseEmitter} 的发送在未绑定响应时只写内部缓存，
 * 所以测试注入一个只记录事件的子类，直接断言「推了什么」。
 */
class DmEventBrokerTest {

    private static final DmMessageResponse MESSAGE = new DmMessageResponse(
            "01MSGPUBLICID00000000000001",
            "01SENDER0000000000000000001",
            "你好",
            Instant.parse("2026-11-09T00:00:00Z"));

    private final DmEventBroker broker = new DmEventBroker();

    @Test
    @DisplayName("订阅者收到一次事件，载荷就是那条消息")
    void subscriberReceivesOneEvent() {
        RecordingEmitter emitter = new RecordingEmitter();
        broker.subscribe("conv-1", emitter);

        broker.publishAfterCommit("conv-1", MESSAGE);

        assertThat(emitter.events).hasSize(1);
        assertThat(payloadsOf(emitter.events.get(0))).contains(MESSAGE);
    }

    @Test
    @DisplayName("同一会话的每个订阅者都收到，别的会话收不到")
    void everySubscriberOfThatConversationReceives() {
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        RecordingEmitter other = new RecordingEmitter();
        broker.subscribe("conv-2", first);
        broker.subscribe("conv-2", second);
        broker.subscribe("conv-other", other);

        broker.publishAfterCommit("conv-2", MESSAGE);

        assertThat(first.events).hasSize(1);
        assertThat(second.events).hasSize(1);
        assertThat(other.events).isEmpty();
    }

    @Test
    @DisplayName("推送失败说明连接已断：该订阅被移除，剩下的不受影响")
    void brokenSubscriberIsRemoved() {
        BrokenEmitter broken = new BrokenEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        broker.subscribe("conv-3", broken);
        broker.subscribe("conv-3", healthy);
        assertThat(broker.subscriberCount("conv-3")).isEqualTo(2);

        broker.publishAfterCommit("conv-3", MESSAGE);

        assertThat(broker.subscriberCount("conv-3")).isEqualTo(1);
        assertThat(healthy.events).hasSize(1);
    }

    @Test
    @DisplayName("有活动事务时：提交前不推送，提交后推送")
    void waitsForCommitWhenTransactionIsActive() {
        RecordingEmitter emitter = new RecordingEmitter();
        broker.subscribe("conv-4", emitter);

        TransactionSynchronizationManager.initSynchronization();
        try {
            broker.publishAfterCommit("conv-4", MESSAGE);
            assertThat(emitter.events).isEmpty();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(emitter.events).hasSize(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("没有订阅者时推送是空操作")
    void publishWithoutSubscribersIsNoop() {
        broker.publishAfterCommit("conv-nobody", MESSAGE);

        assertThat(broker.subscriberCount("conv-nobody")).isZero();
    }

    // ---------- 夹具 ----------

    /** 只记录事件，不触碰响应。 */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<SseEventBuilder> events = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            events.add(builder);
        }
    }

    /** 模拟「客户端已经断开」：任何一次写入都失败。 */
    private static final class BrokenEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client gone");
        }
    }

    private static List<Object> payloadsOf(SseEmitter.SseEventBuilder event) {
        return event.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .toList();
    }
}
