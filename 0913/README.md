# RAG 校园手册问答系统

基于 **Spring Boot 4.1.1 + Spring AI 2.0.0 + Milvus** 的检索增强生成(RAG)示例项目。
以「集美大学新生入学手册」为知识库,实现带多轮上下文的问答,并配套完整的数据清洗、日志与调参工具。

---

## 功能总览

| 模块 | 功能 |
|---|---|
| **多轮对话** | 通过 `conversationId` 维持上下文,同一会话内模型能记住之前说过的话 |
| **RAG 检索增强** | 每次提问先从 Milvus 检索相关内容,再拼进 system 消息交给模型 |
| **角色约束** | 通过 system prompt 限定「只依据手册回答」,资料里没有就明确说「手册中未提及」,不编造 |
| **文档清洗** | 基于**有限状态机**的三条清洗规则:过滤校歌曲调(留歌词)、过滤目录、保护表格 |
| **文档导入** | 读取文档 → 清洗 → 按标题分块 → 向量化 → 写入 Milvus |
| **详细召回日志** | 每次检索打印命中的**分数、排名、章节、完整正文**,同时输出到控制台与独立文件 |
| **日志导出** | 通过 HTTP 查看 / 下载 / 打包(zip)应用日志 |
| **参数调优工具** | topK × similarityThreshold 网格扫描脚本,自动计算 Recall@k 与 MRR |
| **评测问题集** | 两份手册各 10 道题,同构设计,含精确/概括/库外三类 |

---

## 技术栈

- **Spring Boot** 4.1.1 ｜ **Spring AI** 2.0.0(OpenAI 兼容协议 + Milvus 向量库)
- **Milvus** v3.0.1(Docker Compose 部署,含 etcd / MinIO)
- **Knife4j** 5.6.0(接口文档) ｜ **Logback**(日志,含独立召回日志)
- 本地推理:**LM Studio**(对话模型 + 嵌入模型) ｜ **Java 21+**
- **零额外依赖**:日志、zip 打包、参数扫描全部用现有库实现

---

## 架构

```
用户提问
   │
   ├─► KnowledgeBaseAdvisor       检索 Milvus,把资料拼进 system 消息
   │       └─ order = 记忆advisor + 100（必须在内层,见下）
   │
   ├─► MessageChatMemoryAdvisor   带上该 conversationId 的历史消息
   │       └─ order = -2147483448（最外层）
   │
   └─► ChatModelCallAdvisor       真正调用模型
           └─ order = Integer.MAX_VALUE（最内层）
```

### ⚠️ advisor 顺序不能随意调换

`MessageChatMemoryAdvisor.before()` 会把**最后一条用户消息**写进会话记忆。
因此检索 advisor 必须排在它**内层**,否则写进记忆的会是「用户原话 + 检索资料」,
导致记忆被资料撑爆并污染后续轮次。

---

## 快速开始

### 1. 启动 Milvus

```bash
docker compose up -d
# 等 http://127.0.0.1:9091/healthz 返回 OK
```

### 2. 启动 LM Studio 并加载模型

需要一个对话模型和一个嵌入模型,并在 LM Studio 中开启本地 API 服务(默认端口 1234)。

```bash
# 若用 lms CLI
lms status
lms server start
```

### 3. 修改配置

编辑 `rag/src/main/resources/application.properties`:

```properties
spring.ai.openai.base-url=http://localhost:1234/v1
spring.ai.openai.chat.options.model=<你的对话模型>
spring.ai.openai.embedding.options.model=<你的嵌入模型>
rag.kb.default-document-path=<你的文档绝对路径>
```

### 4. 启动应用

```bash
cd rag
mvn spring-boot:run
```

或在 IDEA 中直接运行 `RagApplication`。

> **注意**:项目 POM 配置了 `-parameters` 编译选项(IDEA 默认已带)。
> 命令行用 `javac` 手动编译时需显式加上,否则 `@PathVariable` 会因拿不到参数名而报错。

### 5. 导入文档

```bash
# 用配置里的默认路径
curl -X POST http://127.0.0.1:8080/api/kb/import \
  -H "Content-Type: application/json" -d '{}'

# 或指定路径
curl -X POST http://127.0.0.1:8080/api/kb/import \
  -H "Content-Type: application/json" \
  -d '{"path":"C:\\docs\\handbook.txt"}'
```

### 6. 提问

```bash
# 第一轮(不传 conversationId,服务端会生成)
curl -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"新生什么时候报到？"}'
# => {"conversationId":"xxx-xxx","reply":"..."}

# 第二轮(带上 conversationId 延续上下文)
curl -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"xxx-xxx","message":"那接站安排呢？"}'
```

接口文档:<http://127.0.0.1:8080/doc.html>

---

## 接口

### 聊天

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 多轮对话。`conversationId` 可选,不传则服务端生成 |
| DELETE | `/api/chat/{conversationId}` | 清空该会话记忆 |
| GET | `/api/chat/{conversationId}/messages` | 查看会话窗口内的消息 |

### 知识库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/kb/import` | 导入文档(**不去重**,重导前先 clear) |
| POST | `/api/kb/clear` | 清空集合内全部向量 |
| POST | `/api/kb/search` | 单独检索,可传 `topK` / `similarityThreshold`,响应含 `rank` / `score` |
| POST | `/api/kb/clean-preview` | **清洗预览**:返回清洗前后对比与统计,不写库 |
| GET | `/api/kb/default-path` | 查看默认导入路径 |

### 日志

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/logs/files` | 列出日志文件 |
| GET | `/api/logs/tail` | 查看末尾若干行,支持 `level` 过滤 |
| GET | `/api/logs/download` | 下载单个日志文件 |
| GET | `/api/logs/export` | 打包全部日志为 zip |

---

## 数据清洗(有限状态机)

清洗发生在**向量化之前** —— 脏数据一旦进了 Milvus,检索出来的就是垃圾。

### 状态与转移

| 状态 | 含义 | 动作 |
|---|---|---|
| `NORMAL` | 普通章节 | 全部保留 |
| `SONG` | 校歌章节 | 删曲调行,留歌词 |
| `TOC` | 目录章节 | 整段删除(含标题) |

```
                    遇到标题行
                        │
          ┌─────────────┴─────────────┐
          │  含"校歌"  → SONG         │
          │  含"目录"  → TOC          │
          │  其它      → NORMAL       │
          └─────────────┬─────────────┘
```

**只有标题行能触发状态转移**,其余行只产生动作。初始状态为 `NORMAL`。

### 判定规则

**校歌歌词 vs 曲调** —— 用「汉字占比」区分(仅 `SONG` 状态下生效):

| 行 | 汉字占比 | 判定 |
|---|---|---|
| `大家勿忘,大家勿忘。` | 0.80 | 歌词,保留 |
| `6  5  \| 3  1  \| 2  5. 4  \| 3. 2  1 \|\|` | 0.00 | 简谱,删除 |
| `1=F(或G)` | 0.14 | 调号,删除 |
| `2/4(庄严)` | 0.29 | 拍号,删除 |

阈值取 **0.5**,安全区很宽。之所以不用「不含汉字就删」:调号和拍号里**带汉字**(`或`、`庄严`),那个规则会漏掉。

### ⚠️ 两个必须避开的坑

**坑 1:校歌简谱和表格都用 `|` 符号**

```
简谱 : 5. 5  5. 5  5.  | 3  | 1  3  | 2  -      ← | 在行中间
表格 : | 层次 | 专业 | 学费 (元/年) | …          ← | 在行首
```

所以「删除含 `|` 的行」会**把表格删光**;「含 `|` 就是表格」会**把简谱留下**。
统一用**行首位置**区分。

**坑 2:汉字占比规则不能全局套用**

表格里有大量低汉字行,全局套用会把表格删废:

```
| ---- | ---- | ---- | ---- |        汉字占比 0.00
| 本科 | 少数民族预科班 | 5040 | …    汉字占比约 0.16
```

**对策**:该规则被**限定在 `SONG` 状态下**,并叠加「以 `|` 开头的行永不删除」的硬保护。
这正是状态机的价值 —— **规则作用域由状态精确界定,不靠 `if` 条件小心避开**。

### 表格保护(第二层)

清洗只保证表格**不被删**,还需保证**不被切**。
`splitLongText()` 对超过 900 字的段落会硬切,一旦切在表格中间,表头和数据就分离了。

**对策**:检测到 Markdown 表格(连续 ≥ 2 行以 `|` 开头)时,**整段作为一个块输出,即使超长也不切**。

### 清洗效果(两份手册实测)

| 文档 | 原始 | 清洗后 | 删除 | 校歌行 | 目录行 | 状态转移 |
|---|---|---|---|---|---|---|
| 新生手册 | 11176 字 | 10735 字 | 441 字 (3.9%) | 7 | 12 | 4 次 |
| 招生手册 | 11370 字 | 11239 字 | 131 字 (1.2%) | 1 | 17 | 4 次 |

---

## 详细召回日志

排查 RAG 效果时,**光看「命中几条」是没用的**,必须能看到分数和内容本身。

每次检索都会输出(同时到**控制台**与 `logs/rag-retrieval.log`):

```
==================== 检索开始 ====================
会话: c10f8344-b0d8-4898-83ed-9be56e5cefaa
查询原文: 新生要把户口迁到学校吗？
参数: topK=10 相似度阈值=0.6 集合=jmu_handbook
结果: 命中 10 条, 检索耗时 45ms
--- #1 排名=1 分数=0.6776 章节="新生报到核查" 长度=84 字 ---
> 新生报到核查
> 新生报到后由学校统一安排资格审查及体检复查。……
...
分数区间: 最高=0.6776 最低=0.6190
去重: 命中 10 条 → 有效 10 条(丢弃重复正文 0 条)
==================== 检索结束 ====================
```

正文每行带 `> ` 前缀,因此多行内容**仍可逐行 grep**,不会破坏日志结构。

### 日志文件分工

| 位置 | 内容 |
|---|---|
| **控制台** | 主日志 **+ 召回详情**(两者都打) |
| `logs/rag.log` | 只有主日志,不含召回详情 |
| `logs/rag-retrieval.log` | 只有召回详情 |
| `logs/rag-error.log` | 只有 ERROR |

### 请求追踪

每个 HTTP 请求生成 8 位 `requestId`,日志格式带 `[req=xxxxxxxx]`,并回写到响应头 `X-Request-Id`。
一次对话跨 Controller → Advisor → Service 三个类,用这个 ID 才能串起来。

```powershell
Select-String -Path logs\rag.log -Pattern '71a83db0'
```

---

## 参数调优(topK × similarityThreshold)

### 这两个参数是什么

| 参数 | 含义 | 出处 |
|---|---|---|
| **topK** | 从向量库取回**最相似的 K 条**(KNN 的 K) | **向量检索通用概念** |
| **similarityThreshold** | 相似度**下限**,低于此分的丢弃 | 同上 |

在 Spring AI 里体现为 `SearchRequest.topK(int)` / `.similarityThreshold(double)`,
由 Spring AI 的 `MilvusVectorStore` 翻译给 Milvus 执行。

### 本机实测最优组合

```
chunk=900, topK=10, similarityThreshold=0.6, temp=0.7
```

扫描网格(新生手册 7 道可答题):

| topK \ 阈值 | 0.0 | 0.5 | 0.6 | 0.65 | 0.70 |
|---|---|---|---|---|---|
| 3 | 42.9% | 42.9% | 42.9% | 42.9% | 14.3% |
| 5 | 42.9% | 42.9% | 42.9% | 42.9% | 14.3% |
| 8 | 57.1% | 57.1% | 57.1% | 42.9% | 14.3% |
| **10** | 71.4% | 71.4% | **71.4% ★** | 57.1% | 14.3% |
| 15 | 71.4% | 71.4% | 71.4% | 57.1% | 14.3% |

**为什么是 0.6**:阈值 0~0.6 召回率完全相同,但 0.6 平均少返回 1 条噪声;
0.65 就掉到 57.1%,0.70 直接灾难(5/7 题返回空结果)。

**为什么是 10**:10 是召回拐点,15 不再提升但平均多返回 4.3 条噪声。

> ⚠️ **本机最优值不可照搬。** 实测同一套流程,参考机 topK=5 就够(Recall 100%),
> 本机必须 topK=10(Recall 71.4%)。原因是本机 4GB 显存限制了嵌入模型,
> 而该模型对中文区分度弱,分数全挤在 0.65~0.73,只能靠加大 topK 兜底。

运行扫描:

```bash
powershell -ExecutionPolicy Bypass -File eval-params.ps1
```

---

## 评测问题集

`rag/src/main/resources/questions/benchmark-questions.json`

两份手册各 10 题,共 20 题,**两组同构**:相同 slot 的问题在类型与考察维度上一一对应。

| slot | 类型 | 维度 | 新生手册 | 招生手册 |
|---|---|---|---|---|
| 1 | precise | 校歌歌词 | 歌词最后一句 | 歌词最后一句 |
| 2 | precise | 期限/年限 | 落户截止日期 | 最长学习年限 |
| 3 | precise | 关键数字 | 艺术类学费 | 本科专业数 |
| 4 | precise | 联系方式 | 迁入地址+派出所 | 咨询电话+邮编 |
| 5 | summary | 流程概括 | 报到流程 | 国际交流情况 |
| 6 | summary | 住宿生活 | 生活用品要求 | 宿舍条件 |
| 7 | summary | 管理规定 | 校园交通规定 | 困难生资助 |
| 8 | absent | 录取分数线 | 2025 分数线 | 2024 分数线 |
| 9 | absent | 交叉考察 | 本科专业数 | 新生报到日期 |
| 10 | absent | 文档外信息 | 图书馆开放时间 | 食堂营业时间 |

每道题含 `expectedAnswer`(标准答案)与 `evidence`(出处),便于人工复核 ——
**评测集最容易出的问题就是"标准答案本身错了"**。

---

## 主要配置项

```properties
# ---- RAG 检索 ----
rag.chat.rag.enabled=true                 # 关掉后退化为纯聊天
rag.chat.rag.top-k=10                     # 本机实测最优
rag.chat.rag.similarity-threshold=0.6     # 本机实测最优

# ---- 会话记忆 ----
rag.chat.memory.max-messages=20           # 窗口保留的消息条数(1 轮问答 = 2 条)

# ---- 文档清洗 ----
rag.kb.clean.enabled=true                 # 总开关
rag.kb.clean.lyrics-cjk-ratio=0.5         # 歌词判定阈值

# ---- 日志 ----
rag.logs.dir=logs
rag.logs.retrieval-level=full             # off | brief | full | prompt
rag.logs.low-score-threshold=0            # 低分告警阈值,<=0 关闭
rag.logs.export.enabled=true
rag.logs.export.localhost-only=true       # 日志含提问原文,默认只允许本机访问
rag.logs.tail.max-lines=5000
```

---

## 已知问题 / 待改进

| # | 问题 | 说明 |
|---|---|---|
| 1 | **检索召回率受嵌入模型限制** ⭐ | 若使用以英文为主的嵌入模型,中文语义区分能力很弱。实测「接站」对**无关**片段的相似度(0.6857)甚至**高于相关**片段(0.6131)。**建议换成支持中文的嵌入模型**(bge-m3 / Qwen3-Embedding / bge-large-zh) |
| 2 | 导入不幂等 | `vectorStore.add()` 不去重,同一文档导入两次会产生两份向量。重导前请先调 `/api/kb/clear` |
| 3 | 会话记忆不持久 | 使用内存存储,应用重启即丢失。替换 `ChatMemoryRepository` 实现即可持久化 |
| 4 | 会话无淘汰机制 | 空闲会话不会释放,长期运行有内存增长风险 |
| 5 | 表格能存不能查 | 表格数据完整保留在库里,但检索阶段难以召回(数字密集、语义稀薄) |
| 6 | 短块语义稀 | 实测有 13 个块不足 50 字,建议合并到相邻块 |
| 7 | 无 chunk overlap | 标准 RAG 通常留 10~20% 重叠,本项目无重叠,答案可能正好被切在块边界 |
| 8 | 默认文档路径需配置 | 见 `application.properties` 中的 `rag.kb.default-document-path` |

---

## 项目结构

```
.
├── README.md
├── docker-compose.yml              Milvus + etcd + MinIO
├── chat.ps1                        交互式多轮对话客户端(Windows)
├── verify-chat.ps1                 一键验证 6 个用例(Windows)
├── eval-params.ps1                 topK × 阈值 参数扫描评测(Windows)
├── plan/                           各阶段的设计、实施记录与实验报告
└── rag/                            Maven 模块
    ├── pom.xml
    └── src/main
        ├── java/com/wuyunbin/rag
        │   ├── advisor/            RAG 检索增强 advisor(含详细召回日志)
        │   ├── config/             ChatClient 装配 / 启动信息打印
        │   ├── controller/         聊天、知识库、日志接口
        │   ├── dto/                请求响应体
        │   ├── exception/          全局异常处理
        │   ├── filter/             请求 ID(MDC)
        │   └── service/            聊天、会话、知识库、文档清洗、日志文件
        └── resources
            ├── application.properties
            ├── logback-spring.xml
            ├── prompts/system-prompt.txt
            └── questions/benchmark-questions.json
```
