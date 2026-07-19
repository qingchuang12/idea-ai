# AI Copilot - IntelliJ IDEA Plugin

一个功能强大的 AI 编程助手插件，支持多种 AI 后端驱动模式。

## 核心功能

### 1. UI 侧边栏（ToolWindow）
- 在 IDE 右侧创建 "AI Copilot" 工具窗口
- **TabbedPanel** 布局包含三个标签页：
  - **聊天页**：展示对话消息（支持 Markdown 渲染 + 代码高亮）
  - **Web 嵌入页**：通过 JCEF 加载用户配置的外部网页
  - **上下文页**：展示当前已添加到会话中的文件列表和文本片段

### 2. 多驱动模式支持

#### 驱动 A - 原生 API 模式
- 兼容绝大多数 AI 网站后端（OpenAI、Azure、Anthropic、Gemini 等）
- 支持自定义 Base URL
- 支持自定义 Headers（用于鉴权）
- 支持自定义 Request Body 模板
- 支持流式响应（SSE）

#### 驱动 B - WebView 嵌入式模式
- 利用 JCEF 加载用户指定的 URL
- 支持 ChatGPT Web、Claude Web、Kimi 等
- 支持跨域及 Cookie 持久化

#### 驱动 C - 本地模型模式
- 开箱即用的 Ollama/LM Studio 支持
- 默认配置 localhost:11434

### 3. 编辑器交互
- **Add Selection to AI Chat**：编辑器右键菜单，将选中文本添加到上下文
- **Add File(s) to AI Context**：Project 视图右键菜单，添加文件到上下文
  - 文本文件：读取内容并智能截断
  - 图片文件：转为 Base64 支持多模态

### 4. 会话与数据持久化
- 使用 `PersistentStateComponent` 保存设置
- 聊天记录本地存储（XML）
- 支持新建会话和历史会话管理

## 技术栈

- **语言**：Kotlin + Java 混合开发
- **构建工具**：Gradle (build.gradle.kts)
- **UI 框架**：Swing + IntelliJ JBUI
- **HTTP 客户端**：OkHttp 4.x
- **JSON 处理**：Gson
- **Markdown 渲染**：CommonMark
- **异步处理**：Kotlin Coroutines
- **WebView**：JCEF (Java Chromium Embedded Framework)

## 项目结构

```
ai-copilot/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts           # Gradle 设置
├── gradle.properties             # Gradle 属性
└── src/main/
    ├── kotlin/com/aicopilot/
    │   ├── action/               # Action 类（右键菜单）
    │   │   ├── AddSelectionToChatAction.kt
    │   │   ├── AddFileToContextAction.kt
    │   │   └── NewChatAction.kt
    │   ├── model/                # 数据模型
    │   │   └── Models.kt
    │   ├── service/              # 服务层
    │   │   ├── ChatService.kt
    │   │   ├── ContextManager.kt
    │   │   └── MarkdownRenderer.kt
    │   ├── settings/             # 配置管理
    │   │   ├── AIApplicationSettings.kt
    │   │   ├── AIConfigurable.kt
    │   │   └── ChatHistoryState.kt
    │   ├── ui/                   # UI 组件
    │   │   └── AIChatPanel.kt
    │   └── util/                 # 工具类
    │       └── ExceptionHandler.kt
    └── resources/META-INF/
        ├── plugin.xml            # 插件配置
        └── jcef-config.xml       # JCEF 配置
```

## 安装与构建

### 前置要求
- JDK 17+
- IntelliJ IDEA 2023.3+

### 构建步骤

```bash
# 克隆项目
git clone <repository-url>
cd ai-copilot

# 构建插件
./gradlew buildPlugin

# 运行 IDE 测试
./gradlew runIde
```

构建产物位于：`build/distributions/ai-copilot-*.zip`

### 安装插件

1. 打开 IntelliJ IDEA
2. 进入 `Settings > Plugins`
3. 点击齿轮图标，选择 `Install Plugin from Disk...`
4. 选择 `build/distributions/ai-copilot-*.zip`

## 配置说明

### 原生 API 模式配置示例

**OpenAI:**
- Base URL: `https://api.openai.com/v1`
- API Key: `sk-xxx`
- Model Name: `gpt-3.5-turbo`

**Azure OpenAI:**
- Base URL: `https://your-resource.openai.azure.com/openai/deployments/your-deployment`
- API Key: `your-azure-key`
- Custom Headers: `{"api-key": "your-api-key"}`

**本地 Ollama:**
- Driver Mode: `LOCAL_OLLAMA`
- Local Model URL: `http://localhost:11434`
- Local Model Name: `llama2`

### WebView 模式配置

- Driver Mode: `WEBVIEW`
- WebView URL: `https://chat.openai.com` (或其他支持的网站)

## 异常处理

插件内置完善的异常处理机制：
- 网络超时：友好提示用户检查网络
- 认证错误：引导用户检查 API Key 配置
- 无效 URL：提示检查 Base URL 设置
- 所有错误均通过 `ExceptionHandler` 统一处理并显示用户友好的弹窗

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！
