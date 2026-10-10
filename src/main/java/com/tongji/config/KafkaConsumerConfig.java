package com.tongji.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费端统一错误处理。
 *
 * <p>第三课缺陷修复的核心一环：消费失败不再“静默吞掉”，而是</p>
 * <ol>
 *   <li>有限重试（{@link FixedBackOff}：间隔 1s，最多重试 3 次）；</li>
 *   <li>重试耗尽后由 {@link DeadLetterPublishingRecoverer} 转发到死信主题
 *       （原主题名 + ".DLT"，沿用原分区号，便于按实体排查）；</li>
 *   <li>每次失败都 log.warn 留痕，杜绝“沉默”。</li>
 * </ol>
 *
 * <p>这样既不让“毒丸消息”无限重放卡死分区，也不会静默丢弃——问题消息被隔离到 DLT 事后处理。</p>
 *
 * <p>生效机制：Spring Boot 检测到容器中唯一的 {@code CommonErrorHandler} bean 后，会自动应用到
 * 默认的 kafkaListenerContainerFactory，故所有 {@code @KafkaListener} 都会用上它，无需逐个改注解。</p>
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * 全局消费错误处理器：有限重试 + 死信兜底 + 失败留痕。
     *
     * @param stringKafkaTemplate 复用 CounterConfig 定义的字符串模板，用于转发死信消息
     * @return 应用到所有 @KafkaListener 的错误处理器
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> stringKafkaTemplate) {
        // 重试耗尽后的兜底：把毒丸消息转发到 <原主题>.DLT 的同名分区
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                stringKafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        // 有限重试：间隔 1000ms，最多重试 3 次（含首次共尝试 4 次），仍失败则进死信
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // 重试监听：每次投递失败都留痕（第几次、哪个 topic/partition/offset、异常堆栈）
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka 消费失败，第 {} 次投递 topic={} partition={} offset={}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(), ex));

        return handler;
    }
}
