package com.tongji.llm.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 问答查询服务：
 * - 在问答前保障索引，检索相关上下文并构造提示词
 * - 通过 ChatClient 以流式（SSE）方式返回模型输出
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {
    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);
    // 向量检索接口（Elasticsearch 向量库封装）
    private final VectorStore vectorStore;
    // 大模型对话客户端（在 LlmConfig 中通过 @Qualifier 绑定 deepSeekChatModel）
    private final ChatClient chatClient;
    // 索引服务：确保帖子在问答前已建立/更新索引
    private final RagIndexService indexService;

    /**
     * 使用 WebFlux 返回回答内容的流。
     */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        // 阻塞式准备（保障索引 + 向量检索）统一放到 boundedElastic 上执行。
        // 本项目是 WebFlux：ensureIndexed 与 similaritySearch 内部都是阻塞 IO（MySQL/OSS/OpenAI embedding/ES），
        // 绝不能在 Netty 事件循环线程上跑，否则一个问答就卡住整个服务的并发——这正是缺陷④的根治点。
        return Mono.fromCallable(() -> {
                    // 索引保障失败只记录、不阻断本次问答：检索仍基于「当前已建好的索引」；
                    // 若尚未建好，则由下方空上下文闸门优雅兜底（与索引端「静默吞异常假装成功」有本质区别：这里显式 log.error）。
                    try {
                        indexService.ensureIndexed(postId);
                    } catch (Exception e) {
                        log.error("RAG 索引保障失败，将基于现有索引继续问答 postId={}", postId, e);
                    }
                    // 检索上下文：过滤下推到 ES，只在当前帖子的切片里做向量检索
                    return searchContexts(String.valueOf(postId), question, Math.max(1, topK));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(contexts -> {
                    // 空上下文拦截：检索不到任何切片时，绝不把空 context 塞给 LLM 裸答（否则 RAG 退化为无依据的幻觉）。
                    if (contexts.isEmpty()) {
                        log.warn("RAG 未检索到上下文，拒绝裸问 LLM postId={} question={}", postId, question);
                        return Flux.just("抱歉，暂时没有找到与该问题相关的知文内容，无法基于资料作答。请调整问题，或确认该知文已建立向量索引。");
                    }

                    // 组装上下文文本，分隔符用于提示词中分块标识
                    String context = String.join("\n\n---\n\n", contexts);

                    // 系统提示：限定只依据提供的上下文作答，无法确定需明确说明
                    String system = "你是中文知识助手。只能依据提供的知文上下文回答；无法确定的请说明不确定。";
                    // 用户消息：包含问题和召回到的上下文
                    String user = "问题：" + question + "\n\n上下文如下（可能不完整）：\n" + context + "\n\n请基于以上上下文作答。";

                    return chatClient
                            .prompt() // 构建对话
                            .system(system)
                            .user(user)
                            .options(DeepSeekChatOptions.builder()
                                    .model("deepseek-chat") // 指定 DeepSeek 模型
                                    .temperature(0.2)       // 低温度：更稳健、少发散
                                    .maxTokens(maxTokens)    // 控制最大输出长度
                                    .build())
                            .stream()  // 以流式（SSE）返回模型输出
                            .content(); // 转换为 Flux<String>
                });
    }

    /**
     * 语义检索上下文（过滤下推版）：
     * - 用 filterExpression 把 postId 过滤下推到 ES，向量检索只在当前帖子的切片里进行
     * - ES 端已按相似度排序并取前 topK，无需再全局宽召回 + 内存过滤
     */
    private List<String> searchContexts(String postId, String query, int topK) {
        // 过滤下推：把 postId 过滤交给 ES 端（filterExpression），让向量检索只在「当前帖子」的切片里进行。
        // 对比原「全局召回 fetchK 再内存过滤」——避免目标切片被全局相似度挤出 fetchK 导致召回不足，
        // 也让 topK 直接生效（ES 已在该帖子范围内按相似度排好序取前 topK）。
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .filterExpression("postId == '" + postId + "'") // DSL：字符串值用单引号
                        .build()
        );
        List<String> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            String txt = d.getText();
            if (txt != null && !txt.isEmpty()) {
                out.add(txt);
            }
        }
        return out;
    }
}