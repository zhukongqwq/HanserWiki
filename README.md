# Hanser AI Wiki

面向个人中文 docx 文档库的检索与 AI 问答系统，提供 **Windows（C# WPF）** 与 **Android（Kotlin + Compose 自绘）** 双端客户端。本地文档（日记/随笔等）分词建索引，支持 BM25 关键词检索与「问题 → 关键词 → 检索 → 锚定 → 回答」的 RAG 式问答。

> ⚠️ 内容为 AI 生成，请勿断章取义和未经思考直接采用。

## 双端概览

| | Windows 版 | Android 版 |
|---|---|---|
| 技术栈 | C# WPF（.NET 10） | Kotlin + Jetpack Compose（minSdk 26） |
| 发布产物 | `Hanser-AI-Wiki-v{version}.zip` | `Hanser-AI-Wiki-v{version}.apk` |
| 数据目录 | exe 所在目录（自包含，随包可移） | 应用私有目录 |
| 界面风格 | 金色自绘主题 | 与 Windows 同款金色自绘主题 |

两端功能对齐：文档库检索、三 AI 问答流水线、文档更新（list.json 增量）、对话历史、重建索引、软件更新；两端版本号**各自独立**，但发布在**同一个 Release**（`version.json` 双平台段）。

## 下载与安装

前往 [Releases](https://github.com/zhukongqwq/HanserWiki/releases) 最新版下载：

- **Windows**：下载 `Hanser-AI-Wiki-v*.zip` → 解压到任意目录 → 运行 `Hanser AI Wiki.exe`（win-x64 自包含，无需安装 .NET；数据保存在 exe 旁，整个文件夹可随时移动/备份）
- **Android**：下载 `Hanser-AI-Wiki-v*.apk` 安装（首次安装需允许「未知来源」；升级请卸载旧版或直接覆盖安装）
- Release 名称显示双平台版本，如 `Hanser AI Wiki Windows v1.0.10 · Android v0.1.9`

## 使用说明

### 1. 准备文档库
首次运行时文档库为空，用以下任一方式填充：

- **文档更新**（推荐）：从远程更新源增量拉取文档
- **导入**（Windows 版提供 GUI 导入；Android 版可将 docx 放入应用数据目录）

### 2. 配置 AI（问答前必做）
设置 → API 配置（Android 入口：右上「设置」；Windows 入口：设置窗口「API 配置」标签）：

- **全局默认**：OpenAI 兼容接口的 `base_url` / `api_key` / `model`（未填回退环境变量 `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL`）
- **三 AI 独立配置**（可选）：Project Bunny（关键词）/ Prometheus（锚定）/ hanser（回答）可各自指定接口与模型，留空回退全局默认
- **最大输出（max_tokens）**：留空按模型自动取上限（内置常见模型映射表，DeepSeek v4 系列 1M）
- **模拟模式（dry-run）**：不调用真实大模型即可体验完整问答流程（免 API key 调试用）

### 3. 文档更新（增量拉取 docx）

更新源为 GitHub 仓库 Release 上的 `list.json` 清单（`{"data/文件名.docx": "sha256", ...}`），**每次点击都做文件级核对**：只下载缺失或哈希不一致的文件（网络受限可加镜像前缀，如 `https://gh-proxy.com/https://github.com/owner/repo.git`）。

- Windows：设置 → 数据与更新 → 仓库地址 → 检查更新
- Android：设置 → 📥 文档库更新；下载在**前台服务**中执行（通知栏显示进度，可息屏/后台继续；Android 13+ 首次会请求通知权限）

### 4. 检索与问答

- **问答**：主界面输入问题 → 流程气泡依次显示（小兔提取关键词 → 检索 → 普罗米修斯锚定 → 憨憨回答），回答附带「参考文档」折叠列表，点击可查看命中段落
- **文档库搜索**（Android）：主界面顶部「📚 文档库」→ 输入关键词 BM25 检索 → 点击结果阅读全文

### 5. 维护

- **重建索引**：修改自定义词典后必须重建（强制全量重分词）；Android 版有实时进度条
- **jieba 词库**（userdict.txt）：只加正文中实际成块出现的专有名词/黑话/缩写，勿加过长词组
- **对话历史**：自动存档，重启恢复（Windows：chat-history/ 每对话独立文件；Android：应用私有目录），可一键清空
- **软件更新**：读取 `version.json` 对应平台段比对，有新版可跳转下载（Windows 与 Android 独立检查）

## 版本与发布（version.json）

仓库根 `version.json` 存储双平台版本信息，两端各自独立检查更新：

```json
{
  "version": "1.0.10",
  "changelog": [ "…" ],
  "apps": {
    "windows": { "version": "1.0.10", "file": "Hanser-AI-Wiki-v1.0.10.zip" },
    "android": { "version": "0.1.9",  "file": "Hanser-AI-Wiki-v0.1.9.apk" }
  }
}
```

- 顶层 `version` 与 `apps.windows.version` 相同（Release 标签 `v{version}` 以 Windows 版本为准）
- **仅单端变更时只改对应平台版本**（如只改 Android 则 Windows 版本号保持不变）
- 推送 `main` 触发 GitHub Actions 自动构建两端并发布到**同一个 Release**（zip + apk），Release 名称显示双平台版本

## 开发构建

仓库结构：

```
├── version.json                # 双平台版本与更新日志（发布源）
├── CONTEXT.md                  # 领域术语表
├── docs/adr/                   # 架构决策记录
├── HanserWpf/                  # Windows 工程（C#）
│   ├── HanserWpf.slnx
│   ├── Hanser.Core/            # 核心逻辑（分词/索引/检索/LLM/智能体/更新源）
│   ├── HanserWpf/              # WPF 界面
│   ├── Hanser.SmokeTest/       # 无 GUI 冒烟验证
│   ├── Hanser.Tests/           # 单元测试（xUnit）
│   └── data/  userdict.txt …   # 本地数据
└── Android/                    # Android 工程（Kotlin/Compose）
    └── app/src/main/java/com/zhukongqwq/hanser/…
```

**Windows**（需 .NET 10 SDK）：

```bash
cd HanserWpf
dotnet build HanserWpf.slnx
dotnet test Hanser.Tests/Hanser.Tests.csproj
# 发布（zip 版由 GitHub Actions 自动完成）
dotnet publish HanserWpf/HanserWpf.csproj -c Release -r win-x64 --self-contained true -o dist
```

**Android**（需 JDK 17+、Android SDK、Gradle 9）：

```bash
cd Android
gradle :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest      # 单元测试（JVM）
```

发布新版流程：修改 `version.json`（对应平台版本 + 更新日志）→ 推送 `main` → workflow 自动构建并发布同一 Release。
