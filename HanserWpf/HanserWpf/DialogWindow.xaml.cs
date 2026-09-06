using System.Windows;
using System.Windows.Input;

namespace HanserWpf;

/// <summary>自绘提示/确认对话框窗口（无边框圆角，替代 Windows 原生 MessageBox）。</summary>
public partial class DialogWindow : Window
{
    public bool Confirmed { get; private set; }
    public string InputValue => InputBox.Text;

    public DialogWindow(string title, string message, bool isConfirm)
        : this(title, message, isConfirm, false, "") { }

    public DialogWindow(string title, string message, bool isConfirm, bool isPrompt, string initialValue)
    {
        InitializeComponent();
        TitleText.Text = title;
        MessageText.Text = message;
        CancelButton.Visibility = isConfirm || isPrompt ? Visibility.Visible : Visibility.Collapsed;
        if (isPrompt)
        {
            InputBox.Visibility = Visibility.Visible;
            InputBox.Text = initialValue;
            InputBox.SelectAll();
            InputBox.Focus();
        }
    }

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
            OkButton_Click(sender, e);
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is System.Windows.Controls.Primitives.ButtonBase)
            return;
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }

    private void OkButton_Click(object sender, RoutedEventArgs e)
    {
        Confirmed = true;
        DialogResult = true;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
        => DialogResult = false;
}

/// <summary>自绘对话框入口：Info 提示 / Confirm 确认（返回是否确定）。</summary>
public static class AppDialog
{
    public static void Info(Window owner, string message, string title = "提示")
    {
        var win = new DialogWindow(title, message, false) { Owner = owner };
        win.ShowDialog();
    }

    public static bool Confirm(Window owner, string message, string title = "确认")
    {
        var win = new DialogWindow(title, message, true) { Owner = owner };
        return win.ShowDialog() == true && win.Confirmed;
    }

    /// <summary>输入对话框（重命名等）：返回输入文本；取消返回 null。</summary>
    public static string? Prompt(Window owner, string message, string initialValue = "", string title = "输入")
    {
        var win = new DialogWindow(title, message, isConfirm: false, isPrompt: true, initialValue) { Owner = owner };
        if (win.ShowDialog() != true || !win.Confirmed)
            return null;
        var value = win.InputValue.Trim();
        return value.Length > 0 ? value : null;
    }
}
