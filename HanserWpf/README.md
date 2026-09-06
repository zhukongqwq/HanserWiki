# Hanser AI Wiki

面向个人中文 docx 文档库的检索与 AI 问答桌面应用（WPF，.NET 10）。本地文档分词建索引，支持 BM25 关键词检索与「问题 → 关键词 → 检索 → 锚定 → 回答」的 RAG 式问答，AI 回答流式输出。

## 功能特性

- **文档库检索**：支持 `docx` / `md` / `txt` 文档，jieba 分词 + BM25 相关度排序（k1=1.5, b=0.75）
- **AI 问答流水线**：Project Bunny（关键词）→ 数据库检索 → Prometheus（锚定）→ hanser（流式回答，Markdown 渲染）；三阶段流程以打字机小气泡呈现
- **聊天式界面**：气泡对话、折叠的检索文档列表（点击跳转查看并高亮相关段落）、对话历史（chat-history/ 每对话独立文件，可切换 / 重命名 / 删除）
- **文档更新**：配置 list.json 清单（文件 sha256）增量下载缺失/变更文档，支持 `gh-proxy.com` 等镜像前缀
- **应用版本检查**：启动自动 / 手动检查，从发布仓库比对版本，有新版一键跳转下载
- **导入与索引**：GUI 导入 docx；启动后台增量扫描；可手动重建索引（词库变更后）
- **自绘 UI**：圆角无边框窗口、自绘滚动条与对话框（无系统原生窗口感）

## 使用

1. 在 [Releases](https://github.com/zhukongqwq/HanserWiki/releases) 下载最新 `Hanser AI Wiki.exe`（单文件自包含，无需安装 .NET）
2. 首次运行：数据目录（`data/`）为空，通过「文档更新」或「导入」填充文档
3. 设置 → 数据与更新：
   - **仓库地址**：填写文档更新源（仓库内需有 `releases/download/latest/list.json` 清单，内容为 `{"data/文件名.docx": "sha256", ...}`）；网络受限可加镜像前缀 `https://gh-proxy.com/https://github.com/owner/repo.git`
   - **启动时自动检查更新（应用版本）**：默认开启
4. 设置 → jieba 词库：编辑自定义词典（`userdict.txt`），保存后执行「重建索引」生效
5. 设置 → 关于：查看版本与更新日志、「检查更新」、访问 GitHub 仓库

## 构建

```bash
# 本地构建（需 .NET 10 SDK）
dotnet build

# 发布单文件 exe（win-x64 自包含）
dotnet publish HanserWpf/HanserWpf.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist

# 运行测试
dotnet test Hanser.Tests/Hanser.Tests.csproj
```

## 自动发布（GitHub Actions）

推送 `main`（或手动 Run workflow / 推送 `v*` 标签）自动触发 `.github/workflows/build.yml`：

- `dotnet publish` 构建单文件自包含 exe
- 读取 `version.json`：版本号作为 Release 标签（`v{version}`）、更新日志作为 Release 描述
- 上传 `Hanser AI Wiki.exe` 并创建/更新 Release

发布新版流程：修改 `version.json`（版本号 + 更新日志）→ 推送 `main` → 自动生成对应版本 Release。

## 目录结构

```
├── .github/workflows/build.yml   # 自动构建与发布
├── Hanser.Core/                  # 核心逻辑（分词/索引/检索/LLM/智能体）
├── HanserWpf/                    # WPF 界面
├── Hanser.SmokeTest/             # 无 GUI 冒烟验证
├── Hanser.Tests/                 # 单元测试（xUnit）
└── version.json                  # 版本号与更新日志
```
