package com.wuyunbin.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 日志文件读取与打包。
 *
 * <p>本类处理的是<b>来自 HTTP 请求的文件名参数</b>，因此做了两层防路径穿越校验，
 * 见 {@link #resolveSafe(String)}。</p>
 */
@Service
public class LogFileService {

    private static final Logger log = LoggerFactory.getLogger(LogFileService.class);

    /** 只允许这种形态的文件名：rag.log / rag.2026-09-10.0.log / rag-retrieval.log */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.log(\\.\\d+)?$");

    /** 日志行首的级别，用于按级别过滤（形如 "2026-09-10 11:02:33.123 WARN ..."） */
    private static final Pattern LINE_LEVEL =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} +(TRACE|DEBUG|INFO|WARN|ERROR)\\b");

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Value("${rag.logs.dir:logs}")
    private String logDirConfig;

    /** 解析为绝对规范路径，避免相对路径受工作目录影响。 */
    public Path logDir() {
        return Paths.get(logDirConfig).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // 列举
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listFiles() {
        Path dir = logDir();
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> SAFE_FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> result.add(describe(p)));
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出日志目录: " + dir, e);
        }
        return result;
    }

    private Map<String, Object> describe(Path p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getFileName().toString());
        try {
            m.put("sizeBytes", Files.size(p));
            m.put("lastModified", TS_FMT.format(Files.getLastModifiedTime(p).toInstant()));
        } catch (IOException e) {
            m.put("sizeBytes", -1);
            m.put("lastModified", null);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 安全检查
    // ------------------------------------------------------------------

    /**
     * 把用户传来的文件名解析成安全路径。
     *
     * <p>两层校验：</p>
     * <ol>
     *   <li>文件名必须匹配白名单正则（挡住 {@code ../../} 之类的形态）</li>
     *   <li>解析后的<b>父目录必须恰好是日志目录</b>（挡住一切绕过正则的构造）</li>
     * </ol>
     * <p>校验失败抛 {@link IllegalArgumentException}，由全局异常处理器转成 400。</p>
     */
    public Path resolveSafe(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("file 参数不能为空");
        }
        if (!SAFE_FILE_NAME.matcher(fileName).matches()) {
            log.warn("拒绝可疑的日志文件名: {}", fileName);
            throw new IllegalArgumentException("非法的日志文件名: " + fileName);
        }

        Path dir = logDir();
        Path resolved = dir.resolve(fileName).normalize();
        if (!dir.equals(resolved.getParent())) {
            log.warn("拒绝越界的日志文件路径: {} -> {}", fileName, resolved);
            throw new IllegalArgumentException("非法的日志文件路径: " + fileName);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("日志文件不存在: " + fileName);
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // tail
    // ------------------------------------------------------------------

    /**
     * 读取文件末尾若干行。
     *
     * <p>用环形缓冲（{@link ArrayDeque}）逐行读，不把整个文件加载进内存 ——
     * 日志文件最大 10MB，直接全读也不是不行，但没必要。</p>
     *
     * @param fileName 日志文件名
     * @param lines    最多返回多少行
     * @param level    可选，按级别过滤（INFO/WARN/ERROR/DEBUG/TRACE），null 表示不过滤
     */
    public List<String> tail(String fileName, int lines, String level) {
        Path file = resolveSafe(fileName);
        String wanted = (level == null || level.isBlank()) ? null : level.trim().toUpperCase();

        Deque<String> ring = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (wanted != null && !matchLevel(line, wanted)) {
                    continue;
                }
                ring.addLast(line);
                if (ring.size() > lines) {
                    ring.removeFirst();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取日志文件失败: " + file, e);
        }
        return new ArrayList<>(ring);
    }

    private boolean matchLevel(String line, String level) {
        Matcher m = LINE_LEVEL.matcher(line);
        return m.find() && level.equals(m.group(1));
    }

    // ------------------------------------------------------------------
    // 打包
    // ------------------------------------------------------------------

    /**
     * 把所有日志文件打包成 zip 写入给定输出流（流式，不落临时文件）。
     *
     * @return 打包的文件数量
     */
    public int writeZip(OutputStream out) {
        List<Path> files = new ArrayList<>();
        Path dir = logDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> SAFE_FILE_NAME.matcher(p.getFileName().toString()).matches())
                        .sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("无法列出日志目录: " + dir, e);
            }
        }

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            // 附一份说明，说明这些文件分别是什么
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(buildReadme(files).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("打包日志失败", e);
        }
        return files.size();
    }

    private String buildReadme(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("RAG 日志导出\n");
        sb.append("导出时间: ").append(TS_FMT.format(Instant.now())).append("\n\n");
        sb.append("文件说明:\n");
        sb.append("  rag.log            主日志：请求、耗时、错误、检索一行汇总\n");
        sb.append("  rag-retrieval.log  召回详情：每次检索命中的分数、章节、完整正文\n");
        sb.append("  rag-error.log      仅 ERROR 级别\n\n");
        sb.append("排查提示:\n");
        sb.append("  1) 一次请求的全部日志可以用同一个 [req=xxxxxxxx] 串起来\n");
        sb.append("  2) 想看某次检索召回了什么、分数多少，查 rag-retrieval.log\n");
        sb.append("  3) 错误堆栈在 rag-error.log\n\n");
        sb.append("本次包含文件:\n");
        for (Path p : files) {
            sb.append("  ").append(p.getFileName());
            try {
                sb.append("  (").append(Files.size(p)).append(" 字节)");
            } catch (IOException ignored) {
                // 大小读不到就不显示
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
