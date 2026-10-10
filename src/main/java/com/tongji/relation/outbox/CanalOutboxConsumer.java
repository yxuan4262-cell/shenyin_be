package com.tongji.relation.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.processor.RelationEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import com.tongji.common.util.OutboxMessageUtil;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数；使用手动位点确保处理成功语义。
 */
@Service
public class CanalOutboxConsumer {
    private static final Logger log = LoggerFactory.getLogger(CanalOutboxConsumer.class);

    private final ObjectMapper objectMapper;
    private final RelationEventProcessor processor;

    /**
     * Outbox 消费者构造函数。
     * @param objectMapper JSON 序列化器
     * @param processor 关系事件处理器
     */
    public CanalOutboxConsumer(ObjectMapper objectMapper, RelationEventProcessor processor) {
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    /**
     * 消费 Canal outbox 消息并转为关系事件处理。
     * 监听 Canal→Kafka 桥接写入的 outbox 主题；使用手动位点提交
     * @param message Kafka 消息内容
     * @param ack 位点确认对象
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "relation-outbox-consumer")
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }
            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                if (payloadNode == null) {
                    continue;
                }
                
                RelationEvent evt = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
                processor.process(evt);
            }
            ack.acknowledge();
        } catch (Exception e) {
            // 关系数据不可回源（无“从 following 重建 follower”的任务），绝不能吞异常静默丢：
            // 记录后抛出 → 不 acknowledge → 交全局 DefaultErrorHandler 有限重试，耗尽进 <topic>.DLT 人工兜底
            log.error("关系 outbox 消费失败，将重试或进入死信 topic={}", OutboxTopics.CANAL_OUTBOX, e);
            throw e;
        }
    }
}
