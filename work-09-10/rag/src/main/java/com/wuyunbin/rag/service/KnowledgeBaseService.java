package com.wuyunbin.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 知识库服务：文档读取 -> 标题分节/分块 -> 向量化 -> 写入 Milvus。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** Milvus 集合名（与 application.properties 中 spring.ai.vectorstore.milvus.collection-name 保持一致） */
    public static final String COLLECTION_NAME = "jmu_handbook";

    /** 默认导入文档：集美大学2025级新生入学手册 */
    public static final String DEFAULT_DOCUMENT_PATH =
            "C:\\path\\to\\jmu新生手册.txt";

    /** Markdown 风格标题行，例如 "# 学校基础信息"、"## 报到流程" */
    private static final Pattern HEADING_SPLIT = Pattern.compile("(?m)(?=^#{1,4} )");

    /** 单块目标最大字符数，超出按段落继续切分 */
    private static final int MAX_CHUNK_CHARS = 900;

    private final VectorStore vectorStore;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public String defaultDocumentPath() {
        return DEFAULT_DOCUMENT_PATH;
    }

    /**
     * 导入文档：读取 -> 按标题分节（超长再按段落切分）-> 逐块 embedding -> 写入 Milvus。
     *
     * @param path 文档绝对路径；为空/空白时使用默认新生手册
     * @return 实际写入的向量块数量
     */
    public int importDocument(String path) {
        String docPath = (path == null || path.isBlank()) ? DEFAULT_DOCUMENT_PATH : path;

        long start = System.currentTimeMillis();
        String content = readText(docPath);
        List<Document> chunks = buildChunks(content, docPath);

        log.info("导入开始: 文档={} 正文={}字 切分={}块 collection={}",
                docPath, content.length(), chunks.size(), COLLECTION_NAME);

        vectorStore.add(chunks);

        log.info("导入完成: 写入 {} 块, 耗时 {}ms collection={}",
                chunks.size(), System.currentTimeMillis() - start, COLLECTION_NAME);
        return chunks.size();
    }

    /**
     * 相似度检索：query -> embedding -> Milvus 近似检索 TopK。
     */
    public List<Document> search(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(Math.max(1, topK)).build());
    }

    /**
     * 清空知识库：按 metadata 中的 collection 字段删除本集合的全部向量。
     *
     * <p>存在的意义：{@link #importDocument} 走的是 {@code vectorStore.add()}，
     * 每次调用都会生成新主键、<b>不做去重</b>。同一份文档导入两次，向量就会变成两份，
     * 检索时会返回重复片段、挤占 TopK 名额。重新导入前先调用本方法即可保证不累积。</p>
     *
     * <p>条数核对请直接查 Milvus：
     * {@code POST /v2/vectordb/entities/query} body
     * {@code {"collectionName":"jmu_handbook","filter":"","outputFields":["count(*)"]}}
     * —— Spring AI 的 VectorStore 接口不暴露计数能力，所以这里不做统计。</p>
     */
    public void clear() {
        vectorStore.delete(new FilterExpressionBuilder().eq("collection", COLLECTION_NAME).build());
        log.info("Cleared all vectors in collection {}", COLLECTION_NAME);
    }

    private String readText(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文档: " + path + "，" + e.getMessage(), e);
        }
    }

    private List<Document> buildChunks(String content, String sourcePath) {
        String[] sections = HEADING_SPLIT.split(content);
        List<Document> docs = new ArrayList<>();
        String currentHeading = null;

        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            String[] lines = section.split("\\R", 2);
            String first = lines[0].trim();
            if (first.startsWith("#")) {
                // 该小节带标题
                currentHeading = first.replaceFirst("^#{1,4}\\s*", "");
                section = lines.length > 1 ? lines[1] : "";
            }
            String heading = currentHeading == null ? "" : currentHeading;
            String body = section.trim();
            if (body.isEmpty()) {
                continue;
            }
            // 关键：把标题并入正文一起向量化。
            //
            // 原实现只把标题存进 metadata、正文里不带标题，导致「报到时间」这类
            // 只出现在标题中的词完全检索不到——对应片段正文只有
            // "2025年9月10日7:30-17:30"，里面根本没有"报到""时间"两个字。
            // 「学费」「接站」召不回同理。加上标题后，向量同时承载章节语义与正文语义。
            String text = heading.isEmpty() ? body : heading + "\n" + body;
            Map<String, Object> metadata = Map.of(
                    "source", sourcePath,
                    "heading", heading,
                    "collection", COLLECTION_NAME);
            docs.addAll(splitLongText(text, metadata));
        }
        return docs;
    }

    private List<Document> splitLongText(String text, Map<String, Object> metadata) {
        List<Document> out = new ArrayList<>();
        if (text.length() <= MAX_CHUNK_CHARS) {
            out.add(new Document(text, metadata));
            return out;
        }
        // 超长文本按段落("空行")切分，组装到不超过 MAX_CHUNK_CHARS 的块
        StringBuilder buf = new StringBuilder();
        for (String paragraph : text.split("(?m)^\\s*$")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (buf.length() > 0 && buf.length() + p.length() + 1 > MAX_CHUNK_CHARS) {
                out.add(new Document(buf.toString().trim(), metadata));
                buf.setLength(0);
            }
            if (p.length() > MAX_CHUNK_CHARS) {
                // 极长段落直接硬切
                for (int i = 0; i < p.length(); i += MAX_CHUNK_CHARS) {
                    out.add(new Document(p.substring(i, Math.min(p.length(), i + MAX_CHUNK_CHARS)), metadata));
                }
                buf.setLength(0);
            } else {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(p);
            }
        }
        if (!buf.isEmpty()) {
            out.add(new Document(buf.toString().trim(), metadata));
        }
        return out;
    }
}
