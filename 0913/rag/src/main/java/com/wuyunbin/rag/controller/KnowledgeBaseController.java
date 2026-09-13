package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.KbImportRequest;
import com.wuyunbin.rag.dto.KbSearchRequest;
import com.wuyunbin.rag.service.DocumentCleaner;
import com.wuyunbin.rag.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口：文档导入 + 向量检索。
 */
@RestController
@RequestMapping("/api/kb")
@Tag(name = "知识库接口", description = "Milvus 向量库相关：文档导入与相似度检索")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService kbService;

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @PostMapping("/import")
    @Operation(summary = "导入文档到知识库", description = "读取文档 -> 分块 -> embedding -> 写入 Milvus 集合，返回写入块数")
    public Map<String, Object> importDocument(@RequestBody(required = false) KbImportRequest request) {
        String path = request == null ? null : request.path();
        String docPath = (path == null || path.isBlank())
                ? KnowledgeBaseService.DEFAULT_DOCUMENT_PATH
                : path;
        int chunks = kbService.importDocument(docPath);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("collection", kbService.collectionName());
        resp.put("document", docPath);
        resp.put("importedChunks", chunks);
        return resp;
    }

    @PostMapping("/search")
    @Operation(summary = "相似度检索知识库", description = "对 query 做 embedding 后到 Milvus 检索 TopK 相关片段。响应含 score 与 rank，便于判断召回质量")
    public Map<String, Object> search(@RequestBody KbSearchRequest request) {
        String query = request.query();
        int topK = request.topK() == null ? 5 : request.topK();
        double threshold = request.similarityThreshold() == null ? 0 : request.similarityThreshold();

        long start = System.currentTimeMillis();
        List<Document> hits = kbService.search(query, topK, threshold);
        long elapsed = System.currentTimeMillis() - start;

        log.info("知识库检索: query=\"{}\" topK={} 阈值={} 命中={} 条 耗时={}ms",
                abbreviate(query, 60), topK, threshold, hits.size(), elapsed);

        List<Map<String, Object>> results = new ArrayList<>();
        int rank = 0;
        for (Document doc : hits) {
            rank++;
            Map<String, Object> m = new LinkedHashMap<>();
            // rank/score 是判断召回质量的关键：分数低说明没检索对，
            // 而不是"模型没用上资料"。之前这两个字段被丢掉了，排查时很吃亏。
            m.put("rank", rank);
            m.put("score", doc.getScore());
            m.put("id", doc.getId());
            m.put("heading", doc.getMetadata().getOrDefault("heading", ""));
            m.put("source", doc.getMetadata().getOrDefault("source", ""));
            m.put("text", doc.getText());
            results.add(m);
        }

        Double topScore = hits.isEmpty() ? null : hits.get(0).getScore();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query", query);
        resp.put("topK", results.size());
        resp.put("similarityThreshold", threshold);
        resp.put("topScore", topScore);
        resp.put("elapsedMs", elapsed);
        resp.put("results", results);
        return resp;
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\R", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    @PostMapping("/clear")
    @Operation(summary = "清空知识库", description = "删除集合内全部向量。重新导入文档前先调用，可避免同内容重复累积（导入不去重）")
    public Map<String, Object> clear() {
        kbService.clear();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("collection", kbService.collectionName());
        resp.put("cleared", true);
        return resp;
    }

    @PostMapping("/clean-preview")
    @Operation(summary = "文档清洗预览",
            description = "执行清洗规则(过滤校歌曲调、过滤目录、保护表格)并返回清洗前后对比与统计，不写入向量库")
    public Map<String, Object> cleanPreview(@RequestBody(required = false) KbImportRequest request) {
        String path = request == null ? null : request.path();
        String docPath = (path == null || path.isBlank())
                ? KnowledgeBaseService.DEFAULT_DOCUMENT_PATH
                : path;

        DocumentCleaner.CleanResult cr = kbService.cleanPreview(docPath);

        log.info("清洗预览: 文档={} {}字 → {}字 (删除 {}字/{}%, 校歌{}行, 目录{}行, 状态转移{}次)",
                docPath, cr.originalChars(), cr.cleanedChars(),
                cr.removedChars(), cr.removedPercent(),
                cr.songLinesRemoved(), cr.tocLinesRemoved(), cr.stateTransitions());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("document", docPath);
        resp.put("originalChars", cr.originalChars());
        resp.put("cleanedChars", cr.cleanedChars());
        resp.put("removedChars", cr.removedChars());
        resp.put("removedPercent", cr.removedPercent());
        resp.put("songLinesRemoved", cr.songLinesRemoved());
        resp.put("tocLinesRemoved", cr.tocLinesRemoved());
        resp.put("stateTransitions", cr.stateTransitions());
        resp.put("cleanedText", cr.cleaned());
        return resp;
    }

    @GetMapping("/default-path")
    @Operation(summary = "获取服务端默认导入文档路径")
    public Map<String, String> defaultPath() {
        return Map.of("path", kbService.defaultDocumentPath());
    }
}
