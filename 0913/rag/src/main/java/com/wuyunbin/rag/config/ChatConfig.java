package com.wuyunbin.rag.config;

import com.wuyunbin.rag.advisor.KnowledgeBaseAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Spring AI ChatClient 配置。
 *
 * <p>advisor 链由两个 advisor 组成：</p>
 * <ol>
 *   <li>{@link KnowledgeBaseAdvisor} —— 检索增强（RAG）：先查向量库，把资料拼进 system 消息</li>
 *   <li>{@link MessageChatMemoryAdvisor} —— 会话记忆：把历史消息带进本次请求</li>
 * </ol>
 *
 * <p>两者的相对顺序由各自的 {@code getOrder()} 决定，不能随意调换，原因见
 * {@link KnowledgeBaseAdvisor} 的类注释。</p>
 */
@Configuration
public class ChatConfig {

    /**
     * 会话记忆：保留最近 N 条消息的滑动窗口（1 轮问答 = 2 条消息）。
     *
     * <p>框架的 ChatMemoryAutoConfiguration 在 classpath 上已通过
     * {@code @ConditionalOnMissingBean} 提供了默认实现（固定 20 条），
     * 这里显式声明以覆盖它，便于通过配置调整窗口大小。</p>
     *
     * <p>注意：使用内存仓储，应用重启后会话上下文全部丢失。
     * 如需持久化，替换 {@code ChatMemoryRepository} 的实现即可。</p>
     *
     * @param maxMessages 窗口保留的消息条数
     */
    @Bean
    public ChatMemory chatMemory(@Value("${rag.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * RAG 检索增强 advisor。
     *
     * <p>order 取 {@code DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100}，即紧贴在记忆 advisor
     * 内层、远在 {@code ChatModelCallAdvisor}（Integer.MAX_VALUE）之外。这个位置是刻意选的：
     * 记忆 advisor 会把「最后一条用户消息」写进会话记忆，只有本 advisor 排在它内层，
     * 记忆里存的才是用户原话，而不是被检索资料撑大的增强版消息。</p>
     */
    @Bean
    public KnowledgeBaseAdvisor knowledgeBaseAdvisor(
            VectorStore vectorStore,
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource,
            @Value("${rag.chat.rag.top-k:5}") int topK,
            @Value("${rag.chat.rag.similarity-threshold:0.0}") double similarityThreshold,
            @Value("${rag.chat.rag.enabled:true}") boolean enabled,
            @Value("${rag.logs.retrieval-level:full}") String retrievalLevel,
            @Value("${rag.logs.retrieval-max-chars:0}") int retrievalMaxChars,
            @Value("${rag.logs.low-score-threshold:0}") double lowScoreThreshold,
            @Value("${spring.ai.vectorstore.milvus.collection-name:jmu_handbook}") String collectionName) {
        return new KnowledgeBaseAdvisor(
                vectorStore,
                systemPromptResource,
                topK,
                similarityThreshold,
                enabled,
                Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100,
                collectionName,
                retrievalLevel,
                retrievalMaxChars,
                lowScoreThreshold);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 KnowledgeBaseAdvisor knowledgeBaseAdvisor) {
        return builder
                .defaultAdvisors(
                        knowledgeBaseAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
