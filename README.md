# Interview Workbench · 面试工作台

从空目录开发的 Java + Vue 面试练习应用。支持简历分析、带进度保存的模拟面试和带原文出处的知识库问答。

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

## 三条流程

| 功能 | 实现 |
| --- | --- |
| 简历分析 | 粘贴简历或上传文本型 PDF、UTF-8 TXT/Markdown，按岗位给出改进意见 |
| 模拟面试 | Java 后端和 AI 应用两组题目。事务内持久化答题记录并更新题号，同一题的晚到提交返回 409 |
| 知识库问答 | 重叠分块，按关键词和可选向量混合排序。给模型的证据带编号，界面单独展示本次实际检索到的原文 |

默认运行本地演示模式。简历和面试反馈来自规则，知识库返回检索片段。模拟面试题来自内置题库。

## 启用模型

在启动后端的终端设置以下环境变量。API 密钥只保存在本地环境中。

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
