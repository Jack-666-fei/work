package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.service.LogFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查看与导出接口。
 *
 * <p>安全：这是<b>文件读取</b>接口，默认只允许本机访问（日志里含提问原文与手册正文），
 * 且文件名经过 {@link LogFileService#resolveSafe(String)} 双重校验防路径穿越。</p>
 */
@RestController
@RequestMapping("/api/logs")
@Tag(name = "日志接口", description = "查看与导出应用运行日志")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final LogFileService logFileService;

    @Value("${rag.logs.export.enabled:true}")
    private boolean exportEnabled;

    @Value("${rag.logs.export.localhost-only:true}")
    private boolean localhostOnly;

    @Value("${rag.logs.tail.max-lines:5000}")
    private int maxLines;

    public LogController(LogFileService logFileService) {
        this.logFileService = logFileService;
    }

    // ------------------------------------------------------------------

    @GetMapping("/files")
    @Operation(summary = "列出日志文件", description = "返回日志目录下所有日志文件的名称、大小与最后修改时间")
    public Map<String, Object> files(HttpServletRequest request) {
        guard(request);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("logDir", logFileService.logDir().toString());
        resp.put("files", logFileService.listFiles());
        return resp;
    }

    @GetMapping("/tail")
    @Operation(summary = "查看最近日志",
            description = "读取指定日志文件的末尾若干行，可选按级别过滤。无需下载即可快速查看")
    public Map<String, Object> tail(@RequestParam(defaultValue = "rag.log") String file,
                                    @RequestParam(defaultValue = "200") int lines,
                                    @RequestParam(required = false) String level,
                                    HttpServletRequest request) {
        guard(request);

        int safeLines = Math.max(1, Math.min(lines, maxLines));
        List<String> result = logFileService.tail(file, safeLines, level);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("file", file);
        resp.put("level", level == null ? "" : level);
        resp.put("requestedLines", lines);
        resp.put("returned", result.size());
        resp.put("maxLines", maxLines);
        resp.put("lines", result);
        return resp;
    }

    @GetMapping("/download")
    @Operation(summary = "下载单个日志文件", description = "以附件形式下载指定日志文件")
    public ResponseEntity<Resource> download(@RequestParam String file, HttpServletRequest request) {
        guard(request);

        Path path = logFileService.resolveSafe(file);
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取日志文件大小");
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(size)
                .body(new FileSystemResource(path));
    }

    @GetMapping("/export")
    @Operation(summary = "导出全部日志(zip)",
            description = "把所有日志文件打包成 zip 下载，包内附一份 README 说明各文件用途与排查提示")
    public void export(HttpServletRequest request, HttpServletResponse response) throws IOException {
        guard(request);

        String zipName = "rag-logs-" + STAMP.format(LocalDateTime.now()) + ".zip";

        response.setContentType("application/zip");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(zipName, StandardCharsets.UTF_8).build().toString());

        int count = logFileService.writeZip(response.getOutputStream());
        response.flushBuffer();

        log.info("导出日志: {} 个文件 -> {}", count, zipName);
    }

    // ------------------------------------------------------------------

    /**
     * 统一的访问控制。
     *
     * <p>注意：不记录被拒绝者的完整信息，避免日志本身成为探测工具。</p>
     */
    private void guard(HttpServletRequest request) {
        if (!exportEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "日志接口未启用");
        }
        if (localhostOnly && !isLocalRequest(request)) {
            log.warn("拒绝非本机的日志访问: remoteAddr={} uri={}", request.getRemoteAddr(), request.getRequestURI());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "日志接口仅允许本机访问");
        }
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr)
                || "::1".equals(addr)
                || "0:0:0:0:0:0:0:1".equals(addr)
                || "localhost".equalsIgnoreCase(addr);
    }
}
