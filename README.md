# Tyler Agent

> 一个辅助减肥的 AI Agent 雏形。

## 项目简介

Tyler 是一个基于 **Spring Boot + OpenAI** 的 AI Agent，目标是成为用户的「辅助减肥助手」。

目前项目处于 **雏形阶段**：已经搭好了 Agent 的通用基础设施——多轮对话、工具调用循环、用户画像存储、文件沙箱、日志追踪。而减肥领域的具体业务（如饮食记录、运动打卡、体重趋势追踪、卡路里计算等）**尚未实现**，属于下一步的迭代方向。

简单说：现在是一台「能听懂、能调用工具、能读写文件、能记住你是谁」的 Agent 引擎，还差「减肥」这个灵魂业务。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 26 · Spring Boot 4.1.1 · OpenAI Java SDK（openai-java 4.54.0） |
| 前端 | React · TypeScript · Vite |
| 构建 | Maven（后端） · npm（前端） |

## 项目结构

```
tyler_agent/
├─ src/main/java/org/tyler/
│  ├─ TylerAgentApplication.java   # Spring Boot 启动入口
│  ├─ config/                      # OpenAIClient 装配
│  ├─ filter/                      # 请求追踪 Filter（requestId → MDC）
│  ├─ controller/                  # HTTP 层（薄控制器，只做路由与参数绑定）
│  ├─ service/                     # 业务层（Agent 编排、用户信息）
│  ├─ filesandbox/                 # 文件沙箱（唯一文件 IO 出口，读/写能力隔离）
│  ├─ tool/                        # LLM 可调用的工具集
│  └─ exceptionHandler/            # 全局异常处理（统一 400/500 返回）
├─ src/main/resources/
│  ├─ application.yml              # 配置（模型、端口、工作目录等）
│  └─ logback-spring.xml           # 日志（控制台 + 滚动文件）
├─ frontend/                       # React + TS + Vite 前端（独立项目）
│  └─ src/
│     ├─ App.tsx                   # 根组件（消息状态所有者）
│     ├─ api.ts                    # 对后端的 fetch 封装
│     ├─ types.ts                  # 类型契约（Message / UserInfo）
│     └─ components/               # MessageBubble / MessageInput / UserInfoForm
├─ pom.xml
└─ userinfo.json                   # 用户画像落盘文件（运行时生成）
```

## 已实现的能力

### 1. 多轮对话（非流式）
- 前端累积式聊天界面，逐条堆叠用户与助手的气泡。
- 后端 `POST /api/agent/chat`，请求 `{ "message": "..." }`，响应 `{ "reply": "..." }`。
- 由 OpenAI 的 **Responses API** 驱动，单轮非流式。

### 2. 工具调用循环（Function Calling）
- `AgentService` 会循环调用 OpenAI，直到模型不再请求工具，或达到兜底上限（`MAX_TOOL_ROUNDS = 5`，防止死循环）。
- 内置 4 个工具：

| 工具名 | 作用 |
|---|---|
| `getCurrentTime` | 返回服务器本地时区的当前日期与时间 |
| `GetUserInfo` | 读取已保存的用户画像，让 AI「认识你」 |
| `readFile` | 读取工作区内的文件（路径受限） |
| `writeFile` | 把文本写入工作区内的文件（自动建目录） |

### 3. 用户画像存储
- 前端提供「用户信息」表单（姓名、性别、年龄、职业、备注、对 AI 的期望），点「保存」写盘。
- 后端 `GET /api/userinfo` 读取、`POST /api/userinfo` 校验后落盘为 JSON 文件。
- 落盘结构：

```json
{
  "User": { "Name": "", "Gender": "", "Age": null, "JobType": "" },
  "Other": { "Info": "", "expectationFromLLM": "" }
}
```

- 约束：`Age` 必须是整数（可留空为 `null`）；`Gender` 只能是「男 / 女 / 其他」或空；所有字段均可留空。
- 每次启动前端会自动读取并回填表单。

### 4. 文件沙箱
- 所有文件读写都被限制在一个工作目录内，防止路径穿越（`../`）访问目录之外的文件。
- 默认工作目录为 `{user.home}/AppData/Local/tyler_agent`，可用环境变量 `AGENT_WORKSPACE_DIR` 覆盖。
- 通过「读 / 写」两个能力接口做到能力隔离：

| 类 / 接口 | 职责 |
|---|---|
| `IFileSandboxRead` | 只读契约：`exists` + `read` |
| `IFileSandboxWrite` | 只写契约：`exists` + `write` |
| `FileSandbox` | 唯一实现，同时实现两个接口（唯一的文件 IO 出口） |

- `FileSandbox` 是唯一的文件 IO 实现；调用方不直接依赖它，而是按自身需要注入最小能力接口（Interface Segregation Principle）——同一个 `FileSandbox` bean 能同时提供两种 capability：
  - `UserInfoService` 同时注入 `IFileSandboxRead`（读）和 `IFileSandboxWrite`（写）；
  - `ReadFileTool` 只注入 `IFileSandboxRead`（类型层面就没有 `write`）；
  - `WriteFileTool` 只注入 `IFileSandboxWrite`（类型层面就没有 `read`）。

### 5. 日志与请求追踪
- SLF4J + Logback，INFO 级别记录元数据（耗时、消息长度、工具名、model），正文走 DEBUG。
- 滚动文件日志落在 `logs/tyler-agent.log`，按大小 + 日期滚动、保留 14 天。
- `RequestIdFilter` 用 MDC 的 `requestId` 串起一次请求的完整调用链。

## 如何运行

### 前置条件
- 后端：JDK 26、Maven
- 前端：Node.js（v18+）
- 环境变量：`OPENAI_API_KEY`（用于调用 OpenAI）

### 后端

```bash
mvn spring-boot:run
# 监听 http://localhost:8080
```

### 前端（开发态）

```bash
cd frontend
npm install
npm run dev
# 监听 http://localhost:5173，/api 请求自动代理到 8080
```

打开 `http://localhost:5173`，先填「用户信息」点保存，然后即可开始聊天。

## 配置项（`application.yml`）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `openai.model` | — | `gpt-5.6` | 使用的 OpenAI 模型 |
| `openai.api-key` | `OPENAI_API_KEY` | 空 | OpenAI API 密钥 |
| `agent.workspace-dir` | `AGENT_WORKSPACE_DIR` | `{user.home}/AppData/Local/tyler_agent` | 工具读写文件的沙箱目录 |
| `userinfo.file-path` | `USERINFO_FILE_PATH` | `userinfo.json` | 用户画像 JSON 落盘路径 |

## Roadmap（减肥方向，待实现）

- [ ] 饮食记录：记录每餐食物与大致热量
- [ ] 运动打卡：记录运动类型、时长、消耗
- [ ] 体重趋势：按日期记录体重并画趋势图
- [ ] 目标设定：根据用户画像（身高、体重、目标）给出个性化建议
- [ ] 前端生产构建接入：`vite build` 产物替换 `static/`，纳入 Maven 打包流程
