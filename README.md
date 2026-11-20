# Interview Workbench · 面试工作台

从空目录实现的 Java + Vue 面试练习应用。工作台沿用旧演示版的导航分组和页面流程，数据保存在自己的 H2 数据库。

## 本地启动

需要 Java 21、Maven 3.9、Node.js 20 或以上版本和 pnpm。

在第一个终端启动后端：

```powershell
cd backend
mvn spring-boot:run
```

在第二个终端启动前端：

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

浏览器打开 http://127.0.0.1:5182。后端默认监听 http://127.0.0.1:8092，Vue 开发服务器转发 API 请求。首次启动会在后端的 `data/` 目录创建本地 H2 库。界面有可直接试用的示例。

## 工作台功能

| 区域 | 功能 |
| --- | --- |
| 知识与练习 | 知识库管理、带原文出处的问答会话、知识库题库和练习记录 |
| 面试准备 | 简历上传、去重、异步分析与导出。可配置的文字与语音面试、面试记录、报告和日程 |
| 系统 | 多模型服务的新增、切换、验证和移除 |

默认运行本地演示模式。简历和面试反馈来自规则，知识库使用可复现的关键词检索。连接模型后，知识库问答支持 SSE 流式输出。向量配置启用后，新上传或重新索引的资料可使用混合检索。

语音面试使用浏览器的语音识别与朗读功能，取决于浏览器支持和麦克风授权。界面也提供文本输入。报告导出需要本机中文 TrueType 字体。Windows 默认尝试使用黑体，也可以设置 WORKBENCH_PDF_FONT 为字体文件路径。

## 启用模型

可在「设置」中新增、测试和启用模型，也可在启动后端的终端设置以下环境变量。通过页面添加的密钥加密后写入本地数据库，解密密钥保存在 data/model-store.key。

| 环境变量 | 说明 |
| --- | --- |
| `AI_BASE_URL` | 模型服务提供的 OpenAI 兼容基址，例如以 `/v1` 结束的地址 |
| `AI_API_KEY` | 服务访问密钥 |
| `AI_CHAT_MODEL` | 聊天模型名称 |
| `AI_EMBEDDING_MODEL` | 可选，配置后新导入的片段保存向量，问答混合使用关键词与向量检索 |

百炼的基址和模型名称以账户控制台提供的接入信息为准。更换向量模型后，旧文档保留关键词检索能力，重新导入可生成新模型的向量。

开启模型模式时，提交的简历内容、回答或与问题相关的资料会送到所配置的模型服务。当前版本面向本机练习，公网部署需要加上认证和访问控制。

## 验证

```powershell
mvn -f backend/pom.xml verify
pnpm --dir frontend build
```

## 单 JAR 运行

先构建 Vue，再构建后端。Maven 会把前端静态页面打进可执行 JAR。

```powershell
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend build
mvn -f backend/pom.xml clean package
cd backend
java -jar target/interview-workbench-1.0.0.jar
```

浏览器打开 http://127.0.0.1:8092。
