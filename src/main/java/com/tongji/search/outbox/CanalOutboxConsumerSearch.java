package com.tongji.search.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.outbox.OutboxTopics;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.search.index.SearchIndexService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索索引的 Outbox 消费者：监听 canal-outbox，驱动 ES 索引的增量更新。
 * 仅处理 entity=knowpost 的 upsert 与软删。
 */
@Service
@RequiredArgsConstructor
public class CanalOutboxConsumerSearch {
    private static final Logger log = LoggerFactory.getLogger(CanalOutboxConsumerSearch.class);

    private final ObjectMapper objectMapper;
    private final SearchIndexService indexService;

    /**
     * 消费 outbox 消息，解析合法行并按实体类型更新索引。
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "search-index-consumer")
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

                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                String entity = text(payload.get("entity"));
                String op = text(payload.get("op"));
                Long id = asLong(payload.get("id"));
                if (!"knowpost".equals(entity) || id == null) {
                    continue;
                }

                // 软删与 upsert，均覆盖写入同一文档 ID，保证幂等
                if ("delete".equalsIgnoreCase(op)) {
                    indexService.softDeleteKnowPost(id);
                } else {
                    indexService.upsertKnowPost(id);
                }
            }
            // 提交位点，确保“已处理”的语义
            ack.acknowledge();
        } catch (Exception e) {
            // ES upsert/softDelete 覆盖写同一 docId 天然幂等，重试安全：记录后抛出，
            // 不 acknowledge → 交全局 DefaultErrorHandler 有限重试，耗尽进 <topic>.DLT
            log.error("搜索索引 outbox 消费失败，将重试或进入死信 topic={}", OutboxTopics.CANAL_OUTBOX, e);
            throw e;
        }
    }

    private String text(JsonNode n) {
        return n == null ? null : n.asText();
    }

    private Long asLong(JsonNode n) {
        if (n == null) {
            return null;
        }

        try {
            return Long.parseLong(n.asText());
        } catch (Exception e) {
            return null;
        }
    }
}
