# AI Interview Workbench

**Java 21 · Spring Boot 3.5 · Vue 3 · TypeScript · PostgreSQL + pgvector · Redis Stream**

[旧版在线演示（需账号）](https://8.163.102.189/) · [功能演示](#功能演示) · [本地启动](#快速开始) · [系统架构](#系统架构)

## 项目介绍

AI Interview Workbench 是一款面向求职者的 AI 面试辅助平台，整合简历分析、文字与语音模拟面试、面试日程、知识库问答、题库练习和多模型配置，覆盖投递准备、面试练习与复盘。

平台支持通过 OpenAI 兼容接口接入**大语言模型（LLM）**，用于简历分析、面试反馈和 **SSE 流式问答**。配置向量模型后，系统将文档分块向量写入 **PostgreSQL + pgvector**，在数据库中按余弦距离召回候选片段，再结合关键词检索生成带原文引用的 RAG 回答。

简历上传后保存任务状态，再由 **Redis Stream** 投递给后台消费者异步分析，支持失败重试与任务恢复。语音面试支持**实时转写**和浏览器朗读。配置阿里云百炼 Fun-ASR 后，音频可通过 **WebSocket** 转发给云端模型实时转写。

## 可以做什么

| 模块 | 操作流程 |
| --- | --- |
| 知识库与问答 | 批量导入 PDF、DOCX、TXT、Markdown 或粘贴文本。支持按资料范围流式问答、查看原文引用。从知识库面试总览查看每份资料的有效题目和练习次数，进入题库管理、出题或开始练习。 |
| 简历管理 | 导入 PDF、DOCX、TXT、Markdown 或粘贴文本，填写目标岗位后提交异步分析。从简历库查看进度、重试失败任务、下载原件或导出 PDF 报告。 |
| 模拟面试 | 在同一个面试中心选择文字或语音模式，按主题与难度配置 3–20 道题，可关联已分析的简历、选择本场模型或根据岗位描述练习。最近记录支持续练、查看报告和按原主题、难度与题量重新练习。总记录页汇总文字、语音和知识库练习。文字模式支持暂存、收藏、跳题和提前交卷。语音模式支持字幕、暂停恢复和文字输入。完成后可查看逐题反馈并导出 PDF。 |
| 日程与设置 | 粘贴面试邀请并确认提取结果，在日、周、月或列表中管理日程。支持拖拽改期、修改状态和日程页内提醒。可保存并切换多套模型配置。 |

## 功能演示

以下画面来自本机运行的工作台。演示连接真实 PostgreSQL、pgvector 和 Redis，使用合成资料与模拟聊天、向量服务。语音演示使用模拟浏览器识别和朗读回调。

[简历管理](#简历管理) · [岗位 JD 与文字面试](#岗位-jd-与文字面试) · [语音面试](#语音面试) · [知识库管理与问答](#知识库管理与问答) · [题库与专项练习](#题库与专项练习) · [面试日程](#面试日程) · [模型设置](#模型设置)

### 简历管理

填写目标岗位并提交简历后，列表显示待分析状态。分析完成后，可筛选记录、查看改写建议、下载原件或导出 PDF。

![简历分析结果、改写建议与导出操作](docs/demos/resume-04-report.webp)

<details>
<summary>展开文件上传、异步分析、失败重试与简历库</summary>

1. 上传文件或粘贴示例简历，填写目标岗位。

   ![通过文件上传录入简历](docs/demos/resume-05-file.webp)

   ![录入简历和目标岗位](docs/demos/resume-01-intake.webp)

2. 提交后查看待分析状态。

   ![后台任务待分析状态](docs/demos/resume-02-processing.webp)

3. 返回简历库，筛选已完成的记录并打开分析详情。

   ![简历列表与分析状态筛选](docs/demos/resume-03-library.webp)

4. 模拟模型暂时不可用。自动重试耗尽后，分析页显示失败状态，可点击「重新分析」。

   ![简历分析失败和重新分析入口](docs/demos/resume-06-retry.webp)

5. 模拟服务恢复后，重新分析完成。

   ![手动重试后生成简历报告](docs/demos/resume-07-recovered.webp)

</details>

### 岗位 JD 与文字面试

从已分析的简历进入面试中心，粘贴目标岗位 JD，按方向、难度和题量开始练习。答题时可暂存草稿、收藏、回答追问或跳题。结束后回看逐题反馈和导出 PDF。

![岗位 JD、关联简历与题量配置](docs/demos/interview-01-jd-setup.webp)

<details>
<summary>展开作答、追问、记录与报告</summary>

1. 输入回答并收藏当前题，刷新后可恢复草稿。

   ![文字回答、草稿暂存和题目收藏](docs/demos/interview-02-answer-draft.webp)

2. 提交后进入追问，也可跳过当前题。

   ![文字面试追问与跳题入口](docs/demos/interview-03-follow-up.webp)

3. 在总记录页按类型和状态筛选，可继续未完成的面试。

   ![面试记录筛选与查看报告](docs/demos/interview-04-history.webp)

4. 打开报告回顾逐题反馈、筛选收藏题、导出 PDF，或按原配置开始新一场。

   ![文字面试报告与逐题回顾](docs/demos/interview-05-report.webp)

</details>

### 语音面试

在面试中心选择语音并设置时长。播放题目后可用麦克风回答，校对实时字幕并提交，也可直接输入。暂停后支持继续，结束后查看反馈和导出报告。

![语音面试实时字幕与提交入口](docs/demos/voice-captions.webp)

<details>
<summary>展开语音配置、暂停与报告</summary>

1. 在统一面试入口设置语音模式和计划时长。

   ![语音面试配置与时长](docs/demos/voice-setup.webp)

2. 暂停当前面试，之后点击恢复继续答题。

   ![语音面试暂停与恢复](docs/demos/voice-paused.webp)

3. 结束面试后查看已提交的回答和反馈。

   ![语音面试逐题反馈与 PDF 导出](docs/demos/voice-report.webp)

</details>

### 知识库管理与问答

批量导入复习资料后，可按分类检索、下载原件或重建向量。问答前选定资料范围，发送问题后逐段接收回复，点击引用核对原文。会话支持追问、改名和置顶。

![带原文引用的知识库问答](docs/demos/knowledge-04-citation.webp)

<details>
<summary>展开批量导入、重建向量、流式回答与追问</summary>

1. 选中两份演示文档并批量导入。

   ![批量导入知识库文档](docs/demos/knowledge-01-import.webp)

2. 查看索引状态，按分类筛选，按需下载或重建向量。

   ![资料分类、向量状态与重建入口](docs/demos/knowledge-02-index.webp)

3. 选定资料范围，观察流式回答。

   ![选定资料后的 SSE 增量输出](docs/demos/knowledge-03-stream.webp)

4. 发送追问，改名、置顶并重新打开会话。

   ![问答会话历史、追问和置顶](docs/demos/knowledge-05-conversation.webp)

</details>

### 题库与专项练习

从知识库面试总览选择资料，生成题目或手动添加题目，再批量启用。按题量与追问设置开始练习，完成后回看自己的回答、参考答案和原文出处。

![知识库面试总览与题库入口](docs/demos/knowledge-06-overview.webp)

<details>
<summary>展开出题、批量启用、练习和记录</summary>

1. 设置生成题目数量、难度和追问题量。

   ![根据资料生成题目和追问](docs/demos/knowledge-07-generate.webp)

2. 手动新增题目、参考答案和追问。

   ![手动新增题目和追问](docs/demos/knowledge-08-create-question.webp)

3. 查看草稿状态并批量启用题目。

   ![题库筛选和批量操作](docs/demos/knowledge-09-bank.webp)

4. 设置本轮题量和追问并开始练习。

   ![知识库专项练习设置](docs/demos/knowledge-10-practice-config.webp)

5. 按题作答，完成主问题和追问。

   ![知识库专项练习作答](docs/demos/knowledge-11-practice.webp)

6. 回顾已提交的回答、参考答案和检索片段。

   ![专项练习结果和参考答案](docs/demos/knowledge-12-practice-result.webp)

7. 从练习记录中打开已完成的会话。

   ![按资料查看知识库练习记录](docs/demos/knowledge-13-practice-records.webp)

</details>

### 面试日程

粘贴邀请后，系统提取公司、岗位、时间和会议链接，核对后保存。在周历上可拖拽改期，也可通过列表修改状态。设置提醒后，日程页会在面试临近时提示。

![周历面试安排和拖拽改期结果](docs/demos/schedule-calendar.webp)

<details>
<summary>展开邀请解析、改期、状态和提醒</summary>

1. 粘贴面试邀请。

   ![粘贴面试邀请](docs/demos/schedule-invitation.webp)

2. 核对解析出的时间、会议方式和链接。

   ![邀请解析结果和确认保存](docs/demos/schedule-parsed.webp)

3. 在列表中更改面试状态或编辑安排。

   ![面试列表和状态更新](docs/demos/schedule-status.webp)

4. 临近面试时，在日程页查看提醒。

   ![日程页的临近面试提醒](docs/demos/schedule-reminder.webp)

</details>

### 模型设置

添加 OpenAI 兼容服务，配置聊天和向量模型。测试连接成功后，从已保存的配置中选择当前启用的服务。

![模型配置保存、启用与当前服务](docs/demos/settings-active.webp)

<details>
<summary>展开连接测试</summary>

![通过模型设置测试聊天和向量接口](docs/demos/settings-connection.webp)

</details>

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
