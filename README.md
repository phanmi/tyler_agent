# Tyler Agent

> A personal AI assistant that helps you keep track of what you eat.

**Version:** V0.1.0-alpha

## What is Tyler?

Tyler is a small AI assistant that you can talk to through a chat window. It is still in its earliest days (an "alpha" release), so it does a few things well and leaves the rest for later.

Under the hood, Tyler is not just a chatbot — it can also *do things*. When you tell it something, it can decide to use a tool behind the scenes: read or write a file, remember who you are, or turn what you ate into a tidy record.

## What can Tyler do in V0.1.0-alpha?

Right now, three things.

### 1. Read and write files in a safe "sandbox"

Tyler can save and load files on your computer — but only inside one special folder, called a sandbox. The sandbox is like a fenced-off area: Tyler can freely create and read files inside it, but it cannot reach anything outside that folder. This keeps the rest of your computer safe.

### 2. Save your basic profile

There is a simple form in the interface where you can enter basic details about yourself (name, gender, age, job, notes, and what you expect from the assistant). When you click save, Tyler writes it to a file and remembers it, so future conversations can be more personal.

### 3. Understand the food you eat

You can tell Tyler what you ate in plain words — for example, "I had 200g of chicken breast." Tyler turns that into a small structured record containing the food name, amount, unit, calories, and the main macronutrients (protein, carbs, fat, fiber). When you don't give exact numbers, it estimates them from the food name and amount.

## 中文翻译 · V0.1.0-alpha 功能说明

Tyler 是一个能跟你对话的小型 AI 助手，目前还是最早的 alpha 版本。它不只是聊天，还能真正"动手做事"。

目前它能做三件事：

1. **在安全的"沙盒"里读写文件。** Tyler 能在你的电脑上保存和读取文件，但只能在一个专属的文件夹（沙盒）里进行。沙盒就像一块围起来的区域：Tyler 在里面可以自由地创建和读取文件，却碰不到这个文件夹之外的任何东西，从而保证电脑其余部分的安全。

2. **在界面里保存你的基本信息。** 界面上有一个简单的表单，你可以填写自己的基本信息（姓名、性别、年龄、职业、备注，以及对助手的期望）。点"保存"后，Tyler 会把它们写进文件并记住，让之后的对话更懂你。

3. **理解你吃的食物。** 你可以用大白话告诉 Tyler 你吃了什么，比如"我吃了 200 克鸡胸肉"。Tyler 会把它整理成一条结构化记录，包含食物名称、分量、单位、卡路里，以及主要的宏量营养素（蛋白质、碳水、脂肪、膳食纤维）。当你不给具体数字时，它会根据食物名称和分量来估算。

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 26 · Spring Boot 4.1.1 · OpenAI Java SDK (openai-java 4.54.0) |
| Frontend | React · TypeScript · Vite · Electron |
| Build | Maven (backend) · npm (frontend) |

## Project structure

```
tyler_agent/
├─ src/main/java/org/tyler/
│  ├─ TylerAgentApplication.java   # Spring Boot entry point
│  ├─ config/                      # app-level configuration (CORS for the desktop app)
│  ├─ controller/                  # HTTP layer: receives requests from the UI
│  ├─ service/                     # business logic: the chat loop + user profile
│  ├─ tool/                        # the tools the AI can call (see below)
│  ├─ filesandbox/                 # safe file IO, confined to one folder
│  ├─ model/                       # plain data records (food + user info)
│  ├─ filter/                      # request tracking (adds a requestId to every request)
│  └─ exceptionHandler/            # turns errors into clean HTTP responses
├─ src/main/resources/
│  ├─ application.yml              # configuration (model, port, workspace folder, ...)
│  └─ logback-spring.xml           # logging (console + rolling file)
├─ frontend/                       # React + TypeScript + Vite + Electron app (separate project)
├─ pom.xml
└─ userinfo.json                   # saved user profile (created at runtime)
```

## What each package does

A quick, plain-language tour of the main packages:

| Package | What it does |
|---|---|
| `config` | App-level configuration. It lets the Electron desktop window (which loads the UI from `file://`) call the local backend. |
| `controller` | The front door for web requests. It receives messages from the UI and passes them along, without doing any real work itself. |
| `service` | The brain. It runs the chat loop, decides when to call a tool, manages your saved profile and API key, and creates and reuses the OpenAI client. |
| `tool` | The toolbox. Each tool is one small capability the AI can choose to use (read a file, write a file, get the time, read your profile, record food). |
| `filesandbox` | The fenced-off file area. All reading and writing goes through here, and it refuses to touch anything outside the sandbox folder. |
| `model` | Plain data records — the shapes of the things Tyler works with (a food item, your user info). No logic, just data. |
| `filter` | Request tracking. It tags every request with an id so you can trace one conversation through the logs. |
| `exceptionHandler` | The cleanup crew. When something goes wrong, it turns the error into a tidy HTTP response instead of a crash. |

## The tools Tyler can call

| Tool | What it does |
|---|---|
| `getCurrentTime` | Returns the current date and time. |
| `GetUserInfo` | Reads your saved profile so Tyler "knows" you. |
| `readFile` | Reads a file inside the sandbox. |
| `writeFile` | Writes text to a file inside the sandbox (creating folders as needed). |
| `recordFood` | Turns what you ate into a structured record (name, amount, calories, macros, date). |

## How to run

### Prerequisites

- Backend: JDK 26, Maven
- Frontend: Node.js (v18+)

> **No environment variable needed.** You set your OpenAI API key from the UI — it is stored in a sandbox file (`apikey.txt`) and never leaves your machine. The backend starts fine without a key; you only need one when you actually start chatting.

### Backend

```bash
mvn spring-boot:run
# listens on http://localhost:8080
```

### Frontend (development)

```bash
cd frontend
npm install
npm run dev
# listens on http://localhost:5173; /api requests are proxied to 8080
```

Open http://localhost:5173. First, paste your OpenAI API key into the key field and save it, then start chatting.

- If the key is **empty**, the chat is disabled and the UI tells you to set a key.
- If the key is **invalid**, the chat returns an error saying the key is wrong or unavailable.

### Desktop app (Electron)

Electron wraps everything into one desktop window and starts the backend for you:

```bash
# 1. build the backend JAR (once)
mvn clean package

# 2. build the React production bundle
cd frontend
npm install
npm run build

# 3. launch the desktop app (starts backend + opens window)
npm run electron
```

Electron's main process:

1. locates a Java runtime (via `JAVA_HOME`, then `~/.jdks`, then `PATH`)
2. spawns the backend JAR (`target/tyler-agent-0.1.0.jar`)
3. waits until the backend is ready (polls `GET /api/apikey/status`)
4. opens the Tyler window, which loads the production build from `frontend/dist`

When you close the window, Electron shuts the backend down too, so no Java process is left behind. If the backend fails to start, Electron shows an error dialog and quits.

## Configuration (`application.yml`)

| Setting | Environment variable | Default | Description |
|---|---|---|---|
| `openai.model` | — | `gpt-5.6` | The OpenAI model to use |
| `openai.key-file-path` | `OPENAI_KEY_FILE_PATH` | `apikey.txt` | The sandbox file where the API key is stored |
| `agent.workspace-dir` | `AGENT_WORKSPACE_DIR` | `{user.home}/AppData/Local/tyler_agent` | The sandbox folder for file read/write |
| `userinfo.file-path` | `USERINFO_FILE_PATH` | `userinfo.json` | Where the user profile JSON is saved |
