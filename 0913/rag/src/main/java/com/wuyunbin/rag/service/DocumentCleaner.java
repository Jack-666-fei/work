package com.wuyunbin.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档清洗：基于<b>有限状态机（Finite State Machine）</b>实现。
 *
 * <p>在向量化之前把不该进知识库的内容剔掉。脏数据一旦写进向量库，检索出来的就是垃圾，
 * 所以清洗必须发生在分块之前。</p>
 *
 * <h3>状态定义</h3>
 * <p>文档的每一行都处于且仅处于以下三个状态之一：</p>
 * <table border="1">
 *   <caption>状态与行为</caption>
 *   <tr><th>状态</th><th>含义</th><th>对该行采取的动作</th></tr>
 *   <tr><td>{@link SectionState#NORMAL}</td><td>普通章节</td><td>保留</td></tr>
 *   <tr><td>{@link SectionState#SONG}</td><td>校歌章节</td><td>删曲调行，留歌词</td></tr>
 *   <tr><td>{@link SectionState#TOC}</td><td>目录章节</td><td>删除（含标题行）</td></tr>
 * </table>
 *
 * <h3>状态转移</h3>
 * <pre>
 *                     遇到标题行
 *                         │
 *           ┌─────────────┴─────────────┐
 *           │  根据标题内容判定目标状态   │
 *           │  含"校歌" → SONG          │
 *           │  等于"目录" → TOC         │
 *           │  其它     → NORMAL        │
 *           └─────────────┬─────────────┘
 *                         │
 *       ┌─────────────────┼─────────────────┐
 *       ▼                 ▼                 ▼
 *  ┌─────────┐      ┌──────────┐      ┌─────────┐
 *  │ NORMAL  │      │   SONG   │      │   TOC   │
 *  │ 全部保留 │      │ 只留歌词  │      │ 整段删除 │
 *  └─────────┘      └──────────┘      └─────────┘
 * </pre>
 * <p><b>只有标题行能触发状态转移</b>；其余行只产生"动作"，不改变状态。
 * 初始状态为 {@code NORMAL}。</p>
 *
 * <h3>⚠️ 两个必须避开的坑（实测踩过）</h3>
 *
 * <p><b>坑 1：校歌简谱和表格都用 {@code |} 符号。</b></p>
 * <pre>
 *   简谱 : 5. 5  5. 5  5.  | 3  | 1  3  | 2  -      ← | 在行中间
 *   表格 : | 层次 | 专业 | 学费 (元/年) | …          ← | 在行首
 * </pre>
 * <p>所以「删除含 {@code |} 的行」会把表格删光，「含 {@code |} 就是表格」会把简谱留下来。
 * 本实现统一用<b>行首位置</b>区分。</p>
 *
 * <p><b>坑 2：汉字占比规则不能全局套用。</b>表格里有大量低汉字行：</p>
 * <pre>
 *   | ---- | ---- | ---- | ---- |        汉字占比 0.00
 *   | 本科 | 少数民族预科班 | 5040 | …    汉字占比约 0.16
 * </pre>
 * <p>全局套用会把表格删废。因此该规则被<b>限定在 {@code SONG} 状态下</b>，
 * 并叠加「表格行永不删除」的硬保护。</p>
 */
@Service
public class DocumentCleaner {

    private static final Logger log = LoggerFactory.getLogger(DocumentCleaner.class);

    // ==================== 状态定义 ====================

    /**
     * 章节状态。
     *
     * <p>状态机的"状态"就是<b>当前所在的章节类型</b> —— 它决定了接下来每一行该怎么处理。</p>
     */
    enum SectionState {
        /** 普通章节：内容原样保留 */
        NORMAL,
        /** 校歌章节：删除曲调行（简谱/调号/拍号），保留歌词 */
        SONG,
        /** 目录章节：整段删除，含标题行本身 */
        TOC
    }

    // ==================== 转移条件（标题行判定）====================

    /** 任意标题行：# ~ #### */
    private static final Pattern ANY_HEADING = Pattern.compile("^#{1,4}\\s.*$");

    /** 触发转入 SONG 的标题：标题里含「校歌」 */
    private static final Pattern SONG_HEADING = Pattern.compile("^#{1,4}\\s*.*校歌.*$");

    /**
     * 触发转入 TOC 的标题：标题中<b>包含</b>「目录」二字。
     *
     * <p>注意不能用「标题恰好等于目录」这种严格匹配 —— 实测两份文档的目录标题写法不同：</p>
     * <pre>
     *   新生手册: 「## 目录」
     *   招生手册: 「## 手册目录板块」   ← 严格匹配会漏掉，目录就清不掉
     * </pre>
     * <p>改为包含匹配后两者都能命中。</p>
     */
    private static final Pattern TOC_HEADING = Pattern.compile("^#{1,4}\\s*.*目录.*$");

    // ==================== 行级判定 ====================

    /** 表格行：行首是 | —— 这类行永不删除 */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|");

    /** 汉字字符 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private final boolean enabled;
    private final double lyricsCjkRatio;

    public DocumentCleaner(@Value("${rag.kb.clean.enabled:true}") boolean enabled,
                           @Value("${rag.kb.clean.lyrics-cjk-ratio:0.5}") double lyricsCjkRatio) {
        this.enabled = enabled;
        this.lyricsCjkRatio = lyricsCjkRatio;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================================================================

    /** 清洗并把统计信息一起返回（供日志与预览接口使用）。 */
    public CleanResult cleanWithStats(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new CleanResult("", 0, 0, 0, 0, 0);
        }

        String[] lines = raw.split("\\R", -1);
        List<String> kept = new ArrayList<>(lines.length);

        SectionState state = SectionState.NORMAL;   // 初始状态
        int transitions = 0;
        int songLinesRemoved = 0;
        int tocLinesRemoved = 0;

        for (String line : lines) {

            // ---------- ① 状态转移：只有标题行能触发 ----------
            if (isHeading(line)) {
                SectionState next = classify(line);
                if (next != state) {
                    log.debug("状态转移 {} → {} (标题: {})", state, next, line);
                    transitions++;
                }
                state = next;
            }

            // ---------- ② 动作：由当前状态决定这行的去留 ----------
            boolean keep;
            switch (state) {
                case TOC -> keep = false;                 // 目录：连标题一起删
                case SONG -> keep = isHeading(line)       // 标题保留
                        || line.isBlank()                 // 空行保留
                        || isTableRow(line)               // 表格保护
                        || cjkRatio(line) >= lyricsCjkRatio;   // 歌词留存，曲调删除
                default -> keep = true;                   // NORMAL：全部保留
            }

            // ---------- ③ 输出 ----------
            if (keep) {
                kept.add(line);
            } else if (state == SectionState.TOC) {
                tocLinesRemoved++;
            } else {
                songLinesRemoved++;
            }
        }

        String cleaned = String.join("\n", kept);
        log.debug("状态机结束: 共 {} 次状态转移", transitions);

        return new CleanResult(cleaned, raw.length(), cleaned.length(),
                songLinesRemoved, tocLinesRemoved, transitions);
    }

    /** 只取清洗后的文本。 */
    public String clean(String raw) {
        return cleanWithStats(raw).cleaned();
    }

    // ==================== 转移函数 ====================

    /**
     * 转移函数：由标题行决定下一个状态。
     *
     * <p>这是状态机里唯一的"转移边"，所有状态变化都从这里发生。</p>
     */
    private SectionState classify(String headingLine) {
        if (TOC_HEADING.matcher(headingLine).matches()) {
            return SectionState.TOC;
        }
        if (SONG_HEADING.matcher(headingLine).matches()) {
            return SectionState.SONG;
        }
        return SectionState.NORMAL;
    }

    // ==================== 行级判定 ====================

    private boolean isHeading(String line) {
        return ANY_HEADING.matcher(line).matches();
    }

    private boolean isTableRow(String line) {
        return TABLE_ROW.matcher(line).find();
    }

    /**
     * 汉字占比 = 汉字数 ÷ 总字符数。
     *
     * <p>用于在 {@code SONG} 状态下区分歌词与曲调：</p>
     * <pre>
     *   闽海之滨有我集美乡，山明兮水秀，         0.88  → 歌词，保留
     *   6  5  | 3  1  | 2  5. 4  | 3. 2  1 ||   0.00  → 简谱，删除
     *   1=F(或G)                               0.14  → 调号，删除
     *   2/4(庄严)                              0.29  → 拍号，删除
     * </pre>
     *
     * <p>之所以用「占比」而不是「是否含汉字」：调号和拍号里<b>带汉字</b>（「或」「庄严」），
     * 用「不含汉字就删」会漏掉它们。占比规则一次覆盖三类曲调行，不用写特例。
     * 实测歌词行最低 0.80、曲调行最高 0.29，阈值 0.5 有很宽的安全区。</p>
     */
    private double cjkRatio(String line) {
        if (line.isEmpty()) {
            return 0;
        }
        int cjk = CJK.matcher(line).results().mapToInt(m -> 1).sum();
        return (double) cjk / line.length();
    }

    // ==================== 结果 ====================

    /**
     * 清洗统计。
     *
     * @param stateTransitions 状态机发生的状态转移次数（用于观察状态机行为）
     */
    public record CleanResult(String cleaned,
                              int originalChars,
                              int cleanedChars,
                              int songLinesRemoved,
                              int tocLinesRemoved,
                              int stateTransitions) {

        public int removedChars() {
            return originalChars - cleanedChars;
        }

        public double removedPercent() {
            return originalChars == 0 ? 0 : Math.round(removedChars() * 1000.0 / originalChars) / 10.0;
        }
    }
}
