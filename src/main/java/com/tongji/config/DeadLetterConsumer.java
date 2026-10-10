package com.tongji.config;

import com.tongji.counter.event.CounterTopics;
import com.tongji.relation.outbox.OutboxTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 死信主题（DLT）监控消费者。
 *
 * <p>{@link KafkaConsumerConfig} 的 DefaultErrorHandler 在业务消息重试耗尽后，会把它转发到
 * {@code <原主题>.DLT}。若无消费者，死信将静默堆积、无人知晓——可靠性链路就断在了"最后一公里"。</p>
 *
 * <p>本类监听各 {@code .DLT} 主题，把毒丸消息以 log.error 暴露出来。生产环境可在此接入
 * 钉钉/邮件告警、落库人工复核，或修复后重投原主题。至此链路闭合：
 * 业务主题 at-least-once 重试 → 耗尽进 DLT → DLT 被看见并告警。</p>
 *
 * <p>注意：死信主题由 DeadLetterPublishingRecoverer 首次投递时自动创建（依赖 broker 的
 * auto.create.topics.enable，默认开启）；监听尚不存在的主题不会导致启动失败，只会静待其出现。</p>
 */
@Service
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /**
     * 计数事件死信：counter-events.DLT
     *
     * @param message 重试耗尽的原始消息
     * @param ack 位点确认（manual_immediate 下需显式提交，避免重复告警）
     */
    @KafkaListener(topics = CounterTopics.EVENTS + ".DLT", groupId = "counter-dlt-monitor")
    public void onCounterDeadLetter(String message, Acknowledgment ack) {
        log.error("[死信告警] 计数事件重试耗尽进入 DLT，需人工介入排查。payload={}", message);
        // TODO 生产接入告警（钉钉/邮件）或落库复核；此处仅留痕后确认位点
        ack.acknowledge();
    }

    /**
     * Outbox 事件死信：canal-outbox.DLT
     *
     * @param message 重试耗尽的原始消息
     * @param ack 位点确认
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX + ".DLT", groupId = "outbox-dlt-monitor")
    public void onOutboxDeadLetter(String message, Acknowledgment ack) {
        log.error("[死信告警] outbox 事件重试耗尽进入 DLT，需人工介入排查。payload={}", message);
        ack.acknowledge();
    }
}
