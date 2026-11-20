# 候场 · 面试工作台

面向 Java 后端和 AI 应用求职者的练习工具。可以导入复习资料做带原文引用的问答，也可以上传简历、模拟面试并回看报告。

**Java 21 · Spring Boot 3.5 · Vue 3 · TypeScript · H2**

[在线演示（需账号）](https://8.163.102.189/) · [本地启动](#快速开始) · [实现要点](#实现要点)

| 知识库管理 | 问答助手 |
| --- | --- |
| ![知识库文档列表和分类筛选](docs/screenshots/knowledge-library.png) | ![问答会话和可展开的原文引用](docs/screenshots/knowledge-chat.png) |

## 可以做什么

| 模块 | 操作流程 |
| --- | --- |
| 知识库与问答 | 批量导入 PDF、TXT、Markdown 或粘贴文本，按资料范围提问、追问并查看原文。可以从资料出题、编辑和启用题目，完成练习后回看记录。 |
| 简历管理 | 导入 PDF、DOCX、TXT、Markdown 或粘贴文本，填写目标岗位后提交分析。从列表查看进度、重试失败任务、下载原件或导出 PDF 报告。 |
| 模拟面试 | 按方向、难度和题量开场，支持基于岗位描述的练习。进度随答题保存，可回顾逐题反馈和导出报告。语音模式支持暂停恢复，使用浏览器的识别与朗读能力，也可以输入文字。 |
| 日程与设置 | 按日、周、月或列表查看面试日程。保存多套模型服务配置，可测试连接并切换当前服务。 |

## 快速开始

环境需要 **Java 21、Maven 3.9+、Node.js 20+、pnpm**。构建和运行后端的命令都从 `backend/` 执行，本地数据库和模型密钥文件会写入 `backend/data/`。

### 单 JAR 运行

先在仓库根目录构建 Vue，再让 Maven 把前端产物打进可执行 JAR。

```bash
git clone https://github.com/lxh204/Ai-interview-workbench.git
cd Ai-interview-workbench
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend build
cd backend
mvn clean package
java -jar target/interview-workbench-1.0.0.jar
```

打开 [http://127.0.0.1:8092](http://127.0.0.1:8092)。默认使用演示模式，简历分析返回规则反馈，问答返回本地检索结果，可以直接练习内置面试题。

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

- **简历去重和任务恢复**。按文件 SHA-256 与目标岗位做联合唯一约束。上传后写入待分析任务，页面轮询状态。后端重启时，重新调度中断的处理任务。[简历任务代码](backend/src/main/java/com/interviewlab/workbench/resume/ResumeReviews.java)
- **答题进度与并发提交**。在一个数据库事务里校验当前题号、推进进度和保存答题记录。重复或过期的提交返回 `409`，页面可以重新读取最新进度。[面试服务代码](backend/src/main/java/com/interviewlab/workbench/interview/InterviewService.java)
- **检索范围与出处**。先按会话选定的文档过滤，再将问题交给关键词检索与可选的向量排序。连接聊天模型后，通过 SSE 推送回复增量，校验最终回复的引用编号，并单独展示实际命中的片段。[检索与回答代码](backend/src/main/java/com/interviewlab/workbench/knowledge/KnowledgeAnswers.java) · [SSE 接口](backend/src/main/java/com/interviewlab/workbench/knowledge/KnowledgeChatController.java)

## 模型与运行配置

在「设置」中保存 OpenAI 兼容模型服务，测试连接后启用，也可以在启动后端前设置环境变量。

| 环境变量 | 含义 |
| --- | --- |
| `AI_BASE_URL` | 服务的 OpenAI 兼容基址，填写到 `/v1` 等服务版本路径 |
| `AI_API_KEY` | 服务密钥 |
| `AI_CHAT_MODEL` | 聊天模型名称 |
| `AI_EMBEDDING_MODEL` | 可选。启用后，新导入或重建索引的文档会生成向量，可与关键词检索混合使用 |
| `WORKBENCH_HOST` / `WORKBENCH_PORT` | 后端监听地址和端口，默认 `127.0.0.1:8092` |
| `WORKBENCH_DB_URL` | H2 JDBC 地址，默认使用后端工作目录下的 `data/workbench` |
| `WORKBENCH_PDF_FONT` | PDF 导出使用的中文 TrueType 字体路径。Windows 默认尝试系统黑体 |

在页面保存的 API Key 经加密后写入数据库。备份数据时一并保留 `backend/data/model-store.key`，并将整个 `data/` 目录留在本机。启用云端模型后，相关简历文本、面试回答和检索片段会发给所配置的服务。配置向量模型后，导入或重建索引时会将文档片段发给该服务。语音能力取决于浏览器和麦克风授权。当前配置面向本机使用，公网部署需要添加认证和访问控制。

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

后端测试包括异步简历去重、答题冲突、知识库练习、日程和模型配置。模型协议用本地模拟服务测试，前端构建包含 TypeScript 类型检查。
