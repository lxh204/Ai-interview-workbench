# AI Interview Workbench

**Java 21 · Spring Boot 3.5 · Vue 3 · TypeScript · PostgreSQL + pgvector · Redis Stream**

[旧版在线演示（需账号）](https://8.163.102.189/) · [本地启动](#快速开始) · [系统架构](#系统架构)

## 项目介绍

AI Interview Workbench 是一款面向求职者的 AI 面试辅助平台，整合简历分析、文字与语音模拟面试、面试日程、知识库问答、题库练习和多模型配置，覆盖投递准备、面试练习与复盘。

平台支持通过 OpenAI 兼容接口接入**大语言模型（LLM）**，用于简历分析、面试反馈和 **SSE 流式问答**。配置向量模型后，系统将文档分块向量写入 **PostgreSQL + pgvector**，在数据库中按余弦距离召回候选片段，再结合关键词检索生成带原文引用的 RAG 回答。

简历上传后保存任务状态，再由 **Redis Stream** 投递给后台消费者异步分析，支持失败重试与任务恢复。语音面试支持**实时转写**和浏览器朗读。配置阿里云百炼 Fun-ASR 后，音频可通过 **WebSocket** 转发给云端模型实时转写。

| 知识库管理 | 问答助手 |
| --- | --- |
| ![知识库文档列表和分类筛选](docs/screenshots/knowledge-library.png) | ![问答会话和可展开的原文引用](docs/screenshots/knowledge-chat.png) |

## 可以做什么

| 模块 | 操作流程 |
| --- | --- |
| 知识库与问答 | 批量导入 PDF、DOCX、TXT、Markdown 或粘贴文本。支持按资料范围流式问答、查看原文引用。从知识库面试总览查看每份资料的有效题目和练习次数，进入题库管理、出题或开始练习。 |
| 简历管理 | 导入 PDF、DOCX、TXT、Markdown 或粘贴文本，填写目标岗位后提交异步分析。从简历库查看进度、重试失败任务、下载原件或导出 PDF 报告。 |
| 模拟面试 | 在同一个面试中心选择文字或语音模式，按主题与难度配置 3–20 道题，可关联已分析的简历、选择本场模型或根据岗位描述练习。最近记录支持续练、查看报告和按原主题、难度与题量重新练习。总记录页汇总文字、语音和知识库练习。文字模式支持暂存、收藏、跳题和提前交卷。语音模式支持字幕、暂停恢复和文字输入。完成后可查看逐题反馈并导出 PDF。 |
| 日程与设置 | 粘贴面试邀请并确认提取结果，在日、周、月或列表中管理日程。支持拖拽改期、修改状态和日程页内提醒。可保存并切换多套模型配置。 |

## 系统架构

工作台在浏览器中运行，Spring Boot 应用统一提供业务接口、模型接入与后台任务。PostgreSQL 保存业务记录与知识库，pgvector 在库内执行向量检索，Redis Stream 用于简历分析任务的投递和消费。

[![AI Interview Workbench 分层系统架构](docs/architecture.svg)](docs/architecture.svg)

- **简历异步分析**　上传后在 PostgreSQL 保存简历和任务状态，前端轮询进度。后台把任务 ID 和版本写入 Redis Stream，消费者分析后先写回数据库，再确认消息。临时故障会按计划重试。
- **知识库 RAG**　文档解析、分块后按模型配置生成向量。问答先限定会话资料范围，合并 pgvector 召回与关键词评分，配置聊天模型后，后端将检索片段传给模型，通过 SSE 流式返回回答，并附上可核对的原文引用。
- **实时语音**　页面显示实时字幕，题目由浏览器朗读。启用百炼后，浏览器通过 WebSocket 将音频发送到后端，后端转发给 Fun-ASR，并把实时字幕回传给浏览器。

## 快速开始

环境需要 **Java 21、Maven 3.9+、Node.js 20+、pnpm、PostgreSQL 16+（安装 pgvector）和 Redis 7+**。先克隆仓库，再配置运行依赖。

```bash
git clone https://github.com/lxh204/Ai-interview-workbench.git
cd Ai-interview-workbench
```

已有 PostgreSQL 和 Redis 时，新建 `interview_workbench` 数据库、启用 `vector` 扩展，再设置下文的连接参数。后端在启动时初始化表结构。

也可以在仓库根目录用 Compose 启动依赖。PowerShell 示例。

```powershell
$env:WORKBENCH_DB_PASSWORD = '替换为本地数据库密码'
docker compose up -d --wait
```

接下来在同一终端构建和启动后端。数据库连接与密码通过环境变量配置。

### 单 JAR 运行

先在仓库根目录构建 Vue，再让 Maven 把前端产物打进可执行 JAR。

```bash
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend build
cd backend
mvn clean package
java -jar target/interview-workbench-1.0.0.jar
```

打开 [http://127.0.0.1:8092](http://127.0.0.1:8092)。未配置聊天模型时，演示模式仍可通过 Redis Stream 异步生成规则反馈，并使用关键词检索进行知识库练习。旧版链接的路径也可打开对应入口，例如 [模拟面试](http://127.0.0.1:8092/interview-hub) 和 [面试日程](http://127.0.0.1:8092/interview-schedule)。

### 用内置资料试一次问答

1. 在「知识库管理」选择「导入资料 → 粘贴文本 → 填入示例」，导入「Spring 事务复习笔记」。
2. 在资料行点「问答」，确认本次问答范围包含刚导入的文档。
3. 提问「Spring 事务为什么在同一个对象内部调用时可能失效？」，展开引用，查看系统命中的原文片段。

### 前后端分开开发

在两个终端中分别从仓库根目录执行。

```bash
# 终端一
cd backend
mvn spring-boot:run
```

```bash
# 终端二
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

打开 [http://127.0.0.1:5182](http://127.0.0.1:5182)。Vite 把 `/api` 转发到后端，默认目标为 `http://127.0.0.1:8092`，可用 `API_PROXY_TARGET` 覆盖。

## 实现要点

- **简历去重与 Redis Stream**。按文件 SHA-256 与目标岗位去重，数据库持久化任务状态和重试计划。消息只携带任务 ID 与版本，处理结果落库后确认消息。发布补偿和消费租约用于恢复中断任务，临时故障最多自动重试三次。[任务状态](backend/src/main/java/com/interviewlab/workbench/resume/ResumeReviews.java) · [Redis Stream 消费者](backend/src/main/java/com/interviewlab/workbench/resume/RedisResumeWorker.java)
- **历史题目筛选**。优先安排同方向、主题和练习方式下还未答过的主问题。候选题不足时，从最久未练的题中补齐。 [选题代码](backend/src/main/java/com/interviewlab/workbench/interview/InterviewQuestions.java)
- **答题进度与并发提交**。在一个数据库事务里校验当前题号、推进进度和保存答题记录。重复或过期的提交返回 `409`，页面可以重新读取最新进度。[面试服务代码](backend/src/main/java/com/interviewlab/workbench/interview/InterviewService.java)
- **检索范围与出处**。按会话文档范围、嵌入模型和维度筛选，在 PostgreSQL 内用 pgvector 计算余弦距离，再融合关键词评分。连接聊天模型后，通过 SSE 推送回复增量，校验最终回复的引用编号，并单独展示实际命中的片段。[检索与回答代码](backend/src/main/java/com/interviewlab/workbench/knowledge/KnowledgeAnswers.java) · [SSE 接口](backend/src/main/java/com/interviewlab/workbench/knowledge/KnowledgeChatController.java)

## 模型与运行配置

在「设置」中保存 OpenAI 兼容模型服务，测试连接后启用，也可以在启动后端前设置环境变量。

| 环境变量 | 含义 |
| --- | --- |
| `AI_BASE_URL` | 服务的 OpenAI 兼容基址，填写到 `/v1` 等服务版本路径 |
| `AI_API_KEY` | 服务密钥 |
| `AI_CHAT_MODEL` | 聊天模型名称 |
| `AI_EMBEDDING_MODEL` | 可选。启用后，新导入或重建索引的文档会生成向量，可与关键词检索混合使用 |
| `WORKBENCH_ASR_API_KEY` / `WORKBENCH_ASR_WORKSPACE` | 可选。配置北京地域阿里云百炼业务空间和 API Key 后，可选用 Fun-ASR 实时转写 |
| `WORKBENCH_HOST` / `WORKBENCH_PORT` | 后端监听地址和端口，默认 `127.0.0.1:8092` |
| `WORKBENCH_DB_URL` | PostgreSQL JDBC 地址，默认 `jdbc:postgresql://127.0.0.1:5432/interview_workbench` |
| `WORKBENCH_DB_USERNAME` / `WORKBENCH_DB_PASSWORD` | 数据库用户名与密码，默认用户名为 `interview_workbench` |
| `WORKBENCH_REDIS_HOST` / `WORKBENCH_REDIS_PORT` / `WORKBENCH_REDIS_PASSWORD` | Redis 连接，默认 `127.0.0.1:6379`，按实际服务配置密码 |
| `WORKBENCH_RESUME_STREAM` | 简历分析 Stream 的键名，可为不同环境独立设置 |
| `WORKBENCH_PDF_FONT` | PDF 导出使用的中文 TrueType 字体路径。Windows 默认尝试系统黑体 |

在页面保存的 API Key 经加密后写入 PostgreSQL。备份数据库时一并保存 `backend/data/model-store.key`，用于解密已有配置。从早期 H2 版本升级时，先备份旧 `data/`；需要在新库继续使用的记录须另行导入。启用云端模型后，相关简历文本、面试回答和检索片段会发给所配置的服务。配置向量模型后，导入或重建索引时会将文档片段发给该服务。语音识别默认使用浏览器，云端模式会将录音发送到配置的百炼服务。题目朗读使用浏览器语音合成，录音需要麦克风授权。当前配置面向本机使用，公网部署需要添加认证和访问控制。

## 目录与验证

| 路径 | 内容 |
| --- | --- |
| [`frontend/src/views/`](frontend/src/views) | Vue 页面与交互 |
| [`backend/src/main/java/com/interviewlab/workbench/`](backend/src/main/java/com/interviewlab/workbench) | 简历任务、面试、日程、知识库与模型接入 |
| [`backend/src/test/`](backend/src/test) | 后端接口和模型协议测试 |

在仓库根目录运行。

```bash
mvn -f backend/pom.xml verify
pnpm --dir frontend build
```

常规回归测试使用独立测试库。向量集成测试可通过 `WORKBENCH_VECTOR_TEST_URL`、`WORKBENCH_VECTOR_TEST_USER` 和 `WORKBENCH_VECTOR_TEST_PASSWORD` 连接安装了 pgvector 的测试库。Redis Stream 集成测试通过 `RESUME_TEST_REDIS_PORT` 启用，也可设置 `RESUME_TEST_POSTGRES_URL`、`RESUME_TEST_POSTGRES_USER` 和 `RESUME_TEST_POSTGRES_PASSWORD` 验证消费和 PostgreSQL 状态的一致性。

## 项目参考

简历库、面试中心和知识库面试的操作流程参考 [InterviewGuide](https://github.com/Snailclimb/interview-guide)。本仓库使用 Vue 3 和 Spring Boot 实现相应流程，运行依赖 PostgreSQL、pgvector 与 Redis。
