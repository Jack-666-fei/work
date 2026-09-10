# RAG 校园手册问答系统

基于 **Spring Boot 4.1.1 + Spring AI 2.0.0 + Milvus** 的检索增强生成（RAG）示例项目。
以「集美大学 2025 级新生入学手册」为知识库，实现带多轮上下文的问答。

---

## 功能

| 功能 | 说明 |
|---|---|
| **多轮对话** | 通过 `conversationId` 维持上下文，同一会话内模型能记住之前说过的话 |
| **RAG 检索增强** | 每次提问先从 Milvus 检索相关内容，再拼进 system 消息交给模型 |
| **角色约束** | 通过 system prompt 限定「只依据手册回答」，资料里没有就明确说「手册中未提及」，不编造 |
| **文档导入** | 读取本地文档 → 按标题分块 → 向量化 → 写入 Milvus |
| **知识库清空** | 重新导入前清空，避免同内容重复累积 |
| **详细召回日志** | 每次检索打印命中的分数、排名、章节和完整正文 |
| **日志导出** | 通过 HTTP 接口查看 / 下载 / 打包日志 |

---

## 技术栈

- **Spring Boot** 4.1.1
- **Spring AI** 2.0.0（OpenAI 兼容协议 + Milvus 向量库）
- **Milvus** v3.0.1（Docker Compose 部署，含 etcd / MinIO）
- **Knife4j** 5.6.0（接口文档）
- 本地推理：**LM Studio**（对话模型 + 嵌入模型）
- Java 21+

---

## 架构

```
用户提问
   │
   ├─► KnowledgeBaseAdvisor       检索 Milvus，把资料拼进 system 消息
   │       └─ order = 记忆advisor + 100（必须在内层，见下）
   │
   ├─► MessageChatMemoryAdvisor   带上该 conversationId 的历史消息
   │       └─ order = -2147483448（最外层）
   │
   └─► ChatModelCallAdvisor       真正调用模型
           └─ order = Integer.MAX_VALUE（最内层）
```

### ⚠️ advisor 顺序不能随意调换

`MessageChatMemoryAdvisor.before()` 会把**最后一条用户消息**写进会话记忆。
因此检索 advisor 必须排在它**内层**，否则写进记忆的会是「用户原话 + 检索资料」，
导致记忆被资料撑爆并污染后续轮次。

---

## 快速开始

### 1. 启动 Milvus

```bash
docker compose up -d
# 等 9091/healthz 返回 OK
```

默认端口：`19530`（gRPC）、`9091`（健康检查）、`9000/9001`（MinIO）

### 2. 启动 LM Studio 并加载模型

需要一个对话模型和一个嵌入模型，并在 LM Studio 中开启本地 API 服务（默认 `1234`）。

### 3. 修改配置

编辑 `rag/src/main/resources/application.properties`：

```properties
# LM Studio 地址
spring.ai.openai.base-url=http://localhost:1234/v1
# 对话模型（改成你本地实际加载的模型名）
spring.ai.openai.chat.options.model=<你的对话模型>
# 嵌入模型
spring.ai.openai.embedding.options.model=<你的嵌入模型>
```

### 4. 启动应用

```bash
cd rag
mvn spring-boot:run
```

或在 IDEA 中直接运行 `RagApplication`。

> **注意**：项目 POM 中配置了 `-parameters` 编译选项（IDEA 默认已带），
> 命令行用 `javac` 手动编译时需显式加上，否则 `@PathVariable` 会因拿不到参数名而报错。

### 5. 导入文档

```bash
# 用默认路径导入（见 rag.kb.default-document-path 配置）
curl -X POST http://127.0.0.1:8080/api/kb/import \
  -H "Content-Type: application/json" -d '{}'

# 或指定路径
curl -X POST http://127.0.0.1:8080/api/kb/import \
  -H "Content-Type: application/json" \
  -d '{"path":"C:\\docs\\handbook.txt"}'
```

### 6. 提问

```bash
# 第一轮（不传 conversationId，服务端会生成）
curl -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"新生什么时候报到？"}'
# => {"conversationId":"xxx-xxx","reply":"..."}

# 第二轮（带上 conversationId 延续上下文）
curl -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"xxx-xxx","message":"那接站安排呢？"}'
```

接口文档：<http://127.0.0.1:8080/doc.html>

---

## 接口

### 聊天

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 多轮对话。`conversationId` 可选，不传则服务端生成 |
| DELETE | `/api/chat/{conversationId}` | 清空该会话记忆 |
| GET | `/api/chat/{conversationId}/messages` | 查看会话窗口内的消息 |

### 知识库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/kb/import` | 导入文档（**不去重**，重导前先 clear） |
| POST | `/api/kb/clear` | 清空集合内全部向量 |
| POST | `/api/kb/search` | 单独检索，响应含 `rank` / `score`，便于排查召回质量 |
| GET | `/api/kb/default-path` | 查看默认导入路径 |

### 日志

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/logs/files` | 列出日志文件 |
| GET | `/api/logs/tail` | 查看末尾若干行，支持 `level` 过滤 |
| GET | `/api/logs/download` | 下载单个日志文件 |
| GET | `/api/logs/export` | 打包全部日志为 zip |

---

## 详细召回日志

排查 RAG 效果时，**光看「命中几条」是没用的**，必须能看到分数和内容本身。

每次检索都会输出（同时到控制台与 `logs/rag-retrieval.log`）：

```
==================== 检索开始 ====================
会话: c10f8344-b0d8-4898-83ed-9be56e5cefaa
查询原文: 新生要把户口迁到学校吗？
参数: topK=5 相似度阈值=0.0 集合=jmu_handbook
结果: 命中 5 条, 检索耗时 45ms
--- #1 排名=1 分数=0.7253 章节="新生报到核查" 长度=84 字 ---
> 新生报到核查
> 新生报到后由学校统一安排资格审查及体检复查。……
--- #4 排名=4 分数=0.6951 章节="户口迁移相关规定" 长度=535 字 ---
> 户口迁移相关规定
> (1)但未领到身份证的、或身份证遗失的，必须到当地派出所开具相关证明……
分数区间: 最高=0.7253 最低=0.6931
去重: 命中 5 条 → 有效 5 条（丢弃重复正文 0 条）
==================== 检索结束 ====================
```

正文每行带 `> ` 前缀，因此多行内容**仍可逐行 grep**，不会破坏日志结构。

### 详细程度可配

```properties
# off | brief | full | prompt
rag.logs.retrieval-level=full
```

| 档位 | 内容 |
|---|---|
| `off` | 只留一行汇总 |
| `brief` | query + 命中数 + 每条分数/章节（不打正文） |
| `full` | brief + 每条完整正文（**默认**） |
| `prompt` | full + 最终发给模型的完整 prompt |

---

## 主要配置项

```properties
# ---- RAG ----
rag.chat.rag.enabled=true                 # 关掉后退化为纯聊天
rag.chat.rag.top-k=5                      # 检索条数
rag.chat.rag.similarity-threshold=0.0     # 相似度阈值，0 = 不过滤

# ---- 会话记忆 ----
rag.chat.memory.max-messages=20           # 窗口保留的消息条数（1 轮问答 = 2 条）

# ---- 日志 ----
rag.logs.dir=logs
rag.logs.retrieval-level=full
rag.logs.low-score-threshold=0            # 低分告警阈值，<=0 关闭
rag.logs.export.enabled=true
rag.logs.export.localhost-only=true       # 日志含提问原文，默认只允许本机访问
```

---

## 已知问题 / 待改进

| # | 问题 | 说明 |
|---|---|---|
| 1 | **检索召回率受嵌入模型限制** | 若使用以英文为主的嵌入模型，中文语义区分能力很弱。实测同一批中文文本相似度全挤在 0.6~0.7 区间，相关与不相关几乎分不开。**建议换成支持中文的嵌入模型**（如 bge-m3、Qwen3-Embedding） |
| 2 | 导入不幂等 | `vectorStore.add()` 不去重，同一文档导入两次会产生两份向量。重导前请先调 `/api/kb/clear` |
| 3 | 会话记忆不持久 | 使用内存存储，应用重启即丢失。如需持久化，替换 `ChatMemoryRepository` 实现 |
| 4 | 会话无淘汰机制 | 空闲会话不会释放，长期运行有内存增长风险 |
| 5 | 默认文档路径需配置 | 见 `application.properties` 中的说明 |

---

## 项目结构

```
.
├── docker-compose.yml          Milvus + etcd + MinIO
├── plan/                       各阶段的设计与实施记录
├── chat.ps1                    交互式多轮对话客户端（Windows）
├── verify-chat.ps1             一键验证 6 个用例（Windows）
└── rag/                        Maven 模块
    ├── pom.xml
    └── src/main
        ├── java/com/wuyunbin/rag
        │   ├── advisor/        RAG 检索增强 advisor
        │   ├── config/         ChatClient / 启动信息
        │   ├── controller/     聊天、知识库、日志接口
        │   ├── dto/            请求响应体
        │   ├── exception/      全局异常处理
        │   ├── filter/         请求 ID（MDC）
        │   └── service/        聊天、会话、知识库、日志文件
        └── resources
            ├── application.properties
            ├── logback-spring.xml
            └── prompts/system-prompt.txt
```
