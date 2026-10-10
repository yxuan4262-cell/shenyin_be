package com.tongji.counter.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 计数事件生产者。
 *
 * <p>职责：将业务产生的计数增量事件异步发送到 Kafka 主题，供聚合消费者处理。</p>
 */
@Service
public class CounterEventProducer {
    private static final Logger log = LoggerFactory.getLogger(CounterEventProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public CounterEventProducer(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布计数事件到 Kafka。
     * @param event 计数事件（实体类型、ID、指标、delta 等）
     */
    public void publish(CounterEvent event) {
        // 分区键 = 实体维度：同一 etype:eid 的事件进同一分区，借 Kafka「单分区内有序」还原真实顺序。
        // 否则 +1/-1 乱序虽靠 HINCRBY 交换律侥幸不出错，但「记录最后状态」类聚合会翻车（第三课 Q2）。
        String key = event.getEntityType() + ":" + event.getEntityId();
        try {
            String payload = objectMapper.writeValueAsString(event);
            // 三参数 send(topic, key, payload)：补上分区键（原两参数版 key=null → sticky 分区 → 同实体乱序）。
            // 幂等生产已真正启用（application.yml 的 acks=all + enable.idempotence 经 CounterConfig 生效）。
            kafka.send(CounterTopics.EVENTS, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    // 异步发送失败：留痕告警，但不打断 toggle 主流程——位图已落盘，getCounts 缺失时 bitCount 回源兜底
                    log.error("计数事件发送失败（位图已落盘，可回源兜底）key={} metric={} delta={}",
                            key, event.getMetric(), event.getDelta(), ex);
                }
            });
        } catch (JsonProcessingException e) {
            // 同步序列化失败属编程错误：留痕便于告警，同样不打断主流程（降级但诚实，不再静默吞）
            log.error("计数事件序列化失败 key={} metric={}", key, event.getMetric(), e);
        }
    }
}