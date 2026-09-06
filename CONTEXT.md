# Hanser AI Wiki（单仓库：HanserWpf/ + Android/）

面向个人中文 docx 文档库的检索与 AI 问答系统：本地文档（日记/随笔）分词建索引，支持 BM25 关键词检索与「问题 → 关键词 → 检索 → 锚定 → 回答」的 RAG 式问答。两套客户端（Windows C# WPF 与 Android Kotlin/Compose）共享同一套业务语义与远程协议。

## Language

**文档（Document）**: `data/` 目录下的中文 docx 文件，是索引与检索的基本单元。

**文档库（Document Library）**: `data/` 全部 docx 与其索引构成的检索集合。

**文档更新（Document Update）**: 从远程更新源按 **文件级核对** 拉取文档：下载 `list.json`（{路径: sha256}）后**每次都对本地文件逐个 sha256 比对**，只下载缺失/不一致项，随后增量索引。清单缓存（`.sync-list.json`）一致**不跳过核对**（保证本地文件完整性；早期「清单一致即跳过」的清单级语义已废弃）。
_Avoid_: 同步、拉新

**重建索引（Rebuild）**: 对全部文档强制重新分词建索引；修改 userdict 后执行。

**更新源（Update Source）**: 配置的 GitHub 仓库 Release 上的 `list.json`（含镜像前缀支持）。

**分词（Tokenize）**: 统一分词（jieba 词典；索引与查询必须同入口，否则检索失效）。userdict 为自定义词典。

**关键词 / 检索 / 命中 / 摘要**: BM25（k1=1.5, b=0.75）相关度排序、命中词上下文摘要。

**AI 问答流水线（Pipeline）**: Project Bunny 提取关键词 → 检索 → Prometheus 两路锚定 → hanser 作答（≤6000 字/篇）。OpenAI 兼容接口，全局 + 三 AI 独立配置；max_tokens 按模型自动取上限。

**对话存档（Chat Archive）**: 会话 json 文件，重启恢复、可清空。

**软件更新（App Update）**: 读取仓库根 `version.json`（`apps.windows` / `apps.android` 各自版本与产物文件名），两平台独立检查、**发布在同一 Release**（zip + apk）。

**后台文档更新（Foreground Document Update）**: Android 端文档更新在前台服务中执行：通知栏常驻下载进度，可切后台继续；App 前台时设置页内嵌同步进度；完成后发结果通知。

## Platforms

**Windows（HanserWpf/）**: C# WPF，数据自包含于应用目录（发布版 = exe 目录）。

**Android（Android/）**: Kotlin + Jetpack Compose 自绘（金色主题，与 Windows 一致），数据在应用私有目录。
