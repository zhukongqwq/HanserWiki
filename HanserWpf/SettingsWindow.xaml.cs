using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Input;
using Hanser.Core;

namespace HanserWpf;

/// <summary>设置窗口：API 配置（config.yml）与 jieba 词库（userdict.txt）的编辑保存。</summary>
public partial class SettingsWindow : Window
{
    public SettingsWindow(int initialTab = 0)
    {
        InitializeComponent();
        SettingsTabs.SelectedIndex = initialTab;
        LoadConfig();
        LoadUserDict();
        LoadUpdateSource();
        LoadAbout();
        AutoCheckUpdateCheck.IsChecked = AppSettings.Load().AutoCheckUpdate;
    }

    /// <summary>关于页点击「检查更新」后为 true，主窗口据此触发更新流程。</summary>
    public bool CheckUpdateRequested { get; private set; }

    private void LoadAbout()
    {
        var info = VersionInfo.Load();
        AboutVersionText.Text = $"版本 {info.Version}（{info.Date}）";
        AboutChangelogBox.Text = string.Join("\n", info.Changelog.Select(c => "• " + c));
    }

    private static void OpenBrowser(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // 打开浏览器失败静默忽略
        }
    }

    private void AboutRepoButton_Click(object sender, RoutedEventArgs e)
        => OpenBrowser("https://github.com/zhukongqwq/AI-Hanser");

    private void AboutAuthorButton_Click(object sender, RoutedEventArgs e)
        => OpenBrowser("https://github.com/zhukongqwq");

    private void CheckUpdateButton_Click(object sender, RoutedEventArgs e)
    {
        CheckUpdateRequested = true;
        DialogResult = false; // 关闭设置窗口，由主窗口执行检查更新
    }

    private void LoadUpdateSource()
    {
        UpdateUrlBox.Text = GitHubSync.LoadConfig().Url;
    }

    private void LoadConfig()
    {
        var (baseUrl, apiKey, model) = LLMClient.LoadConfig();
        BaseUrlBox.Text = baseUrl;
        ApiKeyBox.Text = apiKey;
        ModelBox.Text = model;
    }

    private void LoadUserDict()
    {
        try
        {
            UserDictBox.Text = File.Exists(Paths.UserDictPath)
                ? File.ReadAllText(Paths.UserDictPath, Encoding.UTF8)
                : "";
        }
        catch (Exception exc)
        {
            AppDialog.Info(this, $"读取 userdict.txt 失败：{exc.Message}", "jieba 词库");
        }
    }

    /// <summary>YAML 字符串值加双引号并转义。</summary>
    private static string YamlQuote(string s)
        => "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    private async void SaveButton_Click(object sender, RoutedEventArgs e)
    {
        SaveButton.IsEnabled = false;
        var wait = new WaitWindow("设置", "正在保存设置并校验更新源…") { Owner = this };
        wait.Show();
        try
        {
            // 保存 API 配置到 HanserWpf/config.yml（保留注释头）
        try
        {
            var content = "# AI 服务配置（OpenAI 兼容接口）\n" +
                          "# 未填写的项回退到环境变量（OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL）\n" +
                          "openai:\n" +
                          $"  base_url: {YamlQuote(BaseUrlBox.Text.Trim())}\n" +
                          $"  api_key: {YamlQuote(ApiKeyBox.Text.Trim())}\n" +
                          $"  model: {YamlQuote(ModelBox.Text.Trim())}\n";
            File.WriteAllText(Paths.ConfigYmlPath, content, new UTF8Encoding(false));
        }
        catch (Exception exc)
        {
            AppDialog.Info(this, $"保存 config.yml 失败：{exc.Message}", "API 配置");
            return;
        }

        // 保存 jieba 词库到 Python/userdict.txt（统一换行符，保证末尾换行）
        try
        {
            var text = UserDictBox.Text.Replace("\r\n", "\n").Replace('\r', '\n');
            if (text.Length > 0 && !text.EndsWith("\n"))
                text += "\n";
            File.WriteAllText(Paths.UserDictPath, text, new UTF8Encoding(false));
        }
        catch (Exception exc)
        {
            AppDialog.Info(this, $"保存 userdict.txt 失败：{exc.Message}", "jieba 词库");
            return;
        }

        // 保存 GitHub 更新源配置：先校验仓库 list.json 存在，否则禁止保存
        var repoUrl = UpdateUrlBox.Text.Trim();
        if (repoUrl.Length > 0)
        {
            var (ok, msg) = await GitHubSync.ValidateListUrlAsync(repoUrl);
            if (!ok)
            {
                wait.Close();
                AppDialog.Info(this, $"无法保存更新源：\n{msg}", "更新源校验失败");
                return;
            }
            GitHubSync.SaveConfig(new GitHubSync.Config { Url = repoUrl });
        }

        // 保存应用设置（启动自动检查更新开关）
        AppSettings.Save(new AppSettings { AutoCheckUpdate = AutoCheckUpdateCheck.IsChecked == true });

            wait.Close();
            AppDialog.Info(this,
                "设置已保存。\n\n提示：修改 jieba 词库后，请在主窗口执行「重建索引」使分词生效。",
                "设置");
            DialogResult = true;
        }
        finally
        {
            wait.Close();
            SaveButton.IsEnabled = true;
        }
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
        => DialogResult = false;

    /// <summary>清空全部对话历史（chat-history/ 下全部 json）。</summary>
    private void ClearHistoryButton_Click(object sender, RoutedEventArgs e)
    {
        if (!AppDialog.Confirm(this, "确定清空全部对话历史？此操作不可恢复。", "清空对话历史"))
            return;
        try
        {
            ChatArchive.ClearAll();
            AppDialog.Info(this, "全部对话历史已清空。", "清空对话历史");
        }
        catch (Exception exc)
        {
            AppDialog.Info(this, $"清空失败：{exc.Message}", "清空对话历史");
        }
    }

    // ---------- 自绘标题栏 ----------

    /// <summary>标题栏拖拽移动窗口（按钮点击不触发拖拽）。</summary>
    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is System.Windows.Controls.Primitives.ButtonBase)
            return;
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
        => Close();
}
