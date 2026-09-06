# GitHub 远程更新源采用增量拉取

远程文档更新源为 GitHub 仓库（约定某目录存放 docx），客户端手动「检查更新」时只下载新增/变更的文件：通过 GitHub Git Trees API 获取仓库文件树（含每个文件的 blob sha），与本地 manifest（`.sync-manifest.json`，记录 文件路径→sha）对比，仅对新增/变更文件逐个从 raw 拉取合并进文档库，随后增量索引。曾考虑"整仓库 zip 下载后解压合并"与"Releases 附 zip 资产"两种全量方案，因用户要求增量更新（流量小、无发布流程）而选本方案；依赖 GitHub API（匿名限速足够本场景）。
