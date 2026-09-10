package com.wuyunbin.rag.config;

import com.wuyunbin.rag.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后打印一次生效配置。
 *
 * <p>用途：避免"改了配置却没生效"这类困惑 —— 这个坑我们踩过，
 * 手动 javac 构建不会拷贝 resources，导致 {@code target/classes} 里的
 * application.properties 长期是旧版本。</p>
 */
@Component
public class StartupInfoLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    @Value("${rag.chat.rag.enabled:true}")
    private boolean ragEnabled;

    @Value("${rag.chat.rag.top-k:5}")
    private int topK;

    @Value("${rag.chat.rag.similarity-threshold:0.0}")
    private double similarityThreshold;

    @Value("${rag.chat.memory.max-messages:20}")
    private int maxMessages;

    @Value("${rag.logs.retrieval-level:full}")
    private String retrievalLevel;

    @Value("${spring.ai.openai.chat.options.model:未知}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model:未知}")
    private String embeddingModel;

    @Value("${rag.logs.dir:logs}")
    private String logDir;

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 生效配置 ==========");
        log.info("RAG      : enabled={} topK={} 相似度阈值={}", ragEnabled, topK, similarityThreshold);
        log.info("记忆窗口 : {} 条消息", maxMessages);
        log.info("会话模型 : {}", chatModel);
        log.info("嵌入模型 : {}", embeddingModel);
        log.info("向量集合 : {}", KnowledgeBaseService.COLLECTION_NAME);
        log.info("召回日志 : level={}", retrievalLevel);
        log.info("日志目录 : {}", logDir);
        log.info("==============================");
    }
}
