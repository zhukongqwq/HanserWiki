using System.Windows;

namespace HanserWpf;

/// <summary>自绘等待窗口（网络操作等耗时任务期间的加载提示，避免用户以为程序卡死）。</summary>
public partial class WaitWindow : Window
{
    public WaitWindow(string title, string message)
    {
        InitializeComponent();
        TitleText.Text = title;
        MessageText.Text = message;
    }
}
