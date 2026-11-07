using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Threading;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;

internal static class GlassPreview
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--ime")
            RunCandidateHost(args[1]);
        else
            new Application().Run(CreateWindow());
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CopyData
    {
        internal IntPtr Tag;
        internal int Bytes;
        internal IntPtr Text;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongW")]
    private static extern int SetWindowLong(IntPtr window, int index, int value);

    [DllImport("user32.dll")]
    private static extern bool ChangeWindowMessageFilterEx(IntPtr window, uint message, uint action, IntPtr change);

    private static void RunCandidateHost(string parent)
    {
        Process owner = null;
        if (parent != "desktop")
        {
            int pid;
            if (!int.TryParse(parent.Split('-')[0], out pid))
                return;
            try { owner = Process.GetProcessById(pid); }
            catch (ArgumentException) { return; }
        }

        AppContext.SetSwitch("Switch.System.Windows.Input.Stylus.DisableStylusAndTouchSupport", true);
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var receiverParameters = new HwndSourceParameters("MeowpuutCandidates-" + parent)
        {
            WindowStyle = unchecked((int)0x80000000),
            Width = 1,
            Height = 1
        };
        using (owner)
        using (var receiver = new HwndSource(receiverParameters))
        {
            Window window;
            using (var layout = typeof(GlassPreview).Assembly.GetManifestResourceStream("GlassPreview.xaml"))
                window = (Window)XamlReader.Load(layout);
            window.ShowActivated = false;
            window.ShowInTaskbar = false;
            window.Topmost = true;
            window.WindowStartupLocation = WindowStartupLocation.Manual;
            window.IsHitTestVisible = false;
            window.SizeToContent = SizeToContent.Height;
            window.SourceInitialized += (sender, args) =>
            {
                var handle = new WindowInteropHelper(window).Handle;
                const int extendedStyle = -20;
                const int transparentNoActivate = 0x08000020;
                SetWindowLong(handle, extendedStyle, GetWindowLong(handle, extendedStyle) | transparentNoActivate);
            };
            ChangeWindowMessageFilterEx(receiver.Handle, 0x004A, 1, IntPtr.Zero);
            var strip = (StackPanel)window.FindName("Candidates");
            Canvas.SetTop(strip, 84);
            var header = (FrameworkElement)window.FindName("CompositionHeader");
            var pageChip = (Border)window.FindName("PageChip");
            var longCandidate = (Border)window.FindName("LongCandidate");
            var longCandidateText = (TextBlock)window.FindName("LongCandidateText");
            var primaryNumber = (TextBlock)window.FindName("PrimaryNumber");
            var primaryBadge = (Border)window.FindName("PrimaryBadge");
            var preedit = (TextBlock)window.FindName("Preedit");
            var pageIndicator = (TextBlock)window.FindName("PageIndicator");
            var previewPaw = (System.Windows.Shapes.Path)window.FindName("JapanesePaw");
            Geometry paw = previewPaw.Data;
            previewPaw.Visibility = Visibility.Collapsed;
            if (SystemParameters.HighContrast)
            {
                ((System.Windows.Shapes.Rectangle)window.FindName("GlassSurface")).Fill = SystemColors.WindowBrush;
                preedit.Foreground = SystemColors.WindowTextBrush;
                pageIndicator.Foreground = SystemColors.WindowTextBrush;
                pageChip.Background = SystemColors.WindowBrush;
                pageChip.BorderBrush = SystemColors.WindowTextBrush;
                longCandidate.Background = SystemColors.WindowBrush;
                longCandidate.BorderBrush = SystemColors.WindowTextBrush;
                longCandidateText.Foreground = SystemColors.WindowTextBrush;
                primaryBadge.Background = SystemColors.HighlightBrush;
                primaryBadge.BorderBrush = SystemColors.HighlightTextBrush;
                primaryNumber.Foreground = SystemColors.HighlightTextBrush;
            }
            else
                new GlassBackdrop(window);

            IntPtr foreground = IntPtr.Zero;
            receiver.AddHook(delegate(IntPtr hwnd, int message, IntPtr sender, IntPtr data, ref bool handled)
            {
                if (message == 0x0010)
                {
                    handled = true;
                    app.Dispatcher.BeginInvoke(new Action(app.Shutdown));
                    return IntPtr.Zero;
                }
                const int copyDataMessage = 0x004A;
                if (message != copyDataMessage)
                    return IntPtr.Zero;
                var packet = (CopyData)Marshal.PtrToStructure(data, typeof(CopyData));
                if (packet.Tag.ToInt64() != 0x4D50 || packet.Bytes < 2 || packet.Bytes > 8192 || packet.Bytes % 2 != 0)
                    return IntPtr.Zero;

                string text = Marshal.PtrToStringUni(packet.Text, packet.Bytes / 2 - 1);
                handled = true;
                if (text.Length == 0)
                {
                    app.Dispatcher.BeginInvoke(new Action(() =>
                    {
                        if (sender == foreground)
                            window.Hide();
                    }));
                    return new IntPtr(1);
                }

                string[] fields = text.Split('\n');
                int x, y;
                if (fields.Length < 3 || fields.Length > 10 || !int.TryParse(fields[0], out x)
                    || !int.TryParse(fields[1], out y) || sender != GetForegroundWindow())
                    return IntPtr.Zero;

                app.Dispatcher.BeginInvoke(new Action(() =>
                {
                    if (sender != GetForegroundWindow())
                        return;
                    foreground = sender;
                    string[] state = fields[2].Split('\t');
                    int highlighted;
                    if (state.Length < 2 || !int.TryParse(state[1], out highlighted))
                        highlighted = 0;
                    preedit.Text = state[0];
                    header.Visibility = Visibility.Visible;
                    pageIndicator.Text = state.Length > 2 ? state[2] : "";
                    bool expanded = state.Length > 3 && state[3] == "1";
                    int japaneseCount = 0;
                    for (int i = 3; i < fields.Length; i++)
                        if (IsJapanese(fields[i])) japaneseCount++;
                    if (expanded || japaneseCount > 1)
                        pageIndicator.Text += (pageIndicator.Text.Length > 0 ? "   ·   " : "") + (expanded ? "F4 收合" : "F4 展開");
                    pageChip.Visibility = pageIndicator.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
                    int mainIndex = MainCandidate(fields, highlighted);
                    UpdateCandidates(strip, fields, highlighted, mainIndex, expanded, paw);
                    primaryNumber.Text = (mainIndex + 1).ToString();
                    string main = mainIndex >= 0 ? fields[mainIndex + 3].Split('	')[0] : "";
                    SetSentence(longCandidateText, main, state[0]);
                    longCandidate.Visibility = main.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
                    var source = PresentationSource.FromVisual(window) as HwndSource;
                    var scale = source == null ? Matrix.Identity : source.CompositionTarget.TransformFromDevice;
                    var point = scale.Transform(new Point(x, y));
                    window.Left = point.X;
                    window.Top = point.Y;
                    window.Show();
                }));
                return new IntPtr(1);
            });
            var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
            timer.Tick += (sender, args) =>
            {
                if (owner != null && owner.HasExited)
                {
                    timer.Stop();
                    app.Shutdown();
                }
                else if (window.IsVisible && GetForegroundWindow() != foreground)
                    window.Hide();
            };
            timer.Start();
            app.Run();
        }
    }

    private static void UpdateCandidates(StackPanel strip, string[] fields, int highlighted,
                                         int mainIndex, bool expanded, Geometry paw)
    {
        const double available = 618;
        const double minimum = 54;
        var indices = new System.Collections.Generic.List<int>();
        int reservedJapanese = -1;
        for (int index = 0; index < fields.Length - 3; index++)
        {
            if (index == mainIndex) continue;
            if (!expanded && IsJapanese(fields[index + 3])) {
                if (reservedJapanese < 0 || index == highlighted) reservedJapanese = index;
            } else {
                indices.Add(index);
            }
        }
        if (reservedJapanese >= 0)
        {
            indices.Add(reservedJapanese);
            indices.Sort();
        }
        int count = indices.Count;
        var buttons = new Button[count];
        var preferred = new double[count];

        double extra = 0;
        bool contrast = SystemParameters.HighContrast;
        var rose = new LinearGradientBrush(Color.FromRgb(143, 102, 91), Color.FromRgb(88, 58, 63), 78);
        rose.Freeze();
        var rim = new SolidColorBrush(Color.FromArgb(140, 247, 205, 184));
        rim.Freeze();
        strip.Children.Clear();

        for (int i = 0; i < count; i++)
        {
            int index = indices[i];
            string[] candidate = fields[index + 3].Split('\t');
            var content = new DockPanel { Margin = new Thickness(9, 0, 9, 0) };
            content.Children.Add(new TextBlock
            {
                Text = (index + 1).ToString(),
                FontSize = 15,
                Opacity = contrast || index == highlighted ? 1 : 0.72,
                Margin = new Thickness(0, 0, 7, 0),
                VerticalAlignment = VerticalAlignment.Center
            });
            if (IsJapanese(fields[index + 3]))
            {
                var mark = new System.Windows.Shapes.Path
                {
                    Data = paw,
                    Fill = contrast ? SystemColors.WindowTextBrush : Brushes.PeachPuff,
                    Width = 13,
                    Height = 13,
                    Stretch = Stretch.Uniform,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(5, 0, 0, 0)
                };
                DockPanel.SetDock(mark, Dock.Right);
                content.Children.Add(mark);
            }
            var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            words.Children.Add(new TextBlock
            {
                Text = candidate[0],
                FontSize = candidate.Length > 1 && candidate[1].Length > 0 ? 18 : 23,
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            if (candidate.Length > 1 && candidate[1].Length > 0)
                words.Children.Add(new TextBlock { Text = candidate[1], FontSize = 10, Opacity = contrast ? 1 : 0.72 });
            content.Children.Add(words);
            var button = new Button
            {
                Content = content,
                Height = 38,
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                Focusable = false,
                Margin = new Thickness(0, 0, 4, 0)
            };
            if (contrast)
                button.Foreground = index == highlighted ? SystemColors.HighlightTextBrush : SystemColors.WindowTextBrush;
            if (index == highlighted)
            {
                button.Background = contrast ? (Brush)SystemColors.HighlightBrush : rose;
                button.BorderBrush = contrast ? SystemColors.HighlightTextBrush : rim;
            }
            AutomationProperties.SetName(button, (index + 1) + " " + candidate[0] + (IsJapanese(fields[index + 3]) ? " 日語" : ""));
            strip.Children.Add(button);
            content.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            double naturalWidth = content.DesiredSize.Width + 4;
            preferred[i] = Math.Min(230, Math.Max(minimum, naturalWidth));
            extra += preferred[i] - minimum;
            buttons[i] = button;
        }

        double scale = extra > 0 ? Math.Min(1, (available - count * (minimum + 4)) / extra) : 1;
        for (int i = 0; i < count; i++)
            buttons[i].Width = minimum + (preferred[i] - minimum) * scale;
    }

    private static bool IsJapanese(string candidate)
    {
        return candidate.EndsWith("	ja", StringComparison.Ordinal);
    }

    private static int MainCandidate(string[] fields, int highlighted)
    {
        int count = fields.Length - 3;
        if (highlighted >= 0 && highlighted < count)
        {
            string[] selected = fields[highlighted + 3].Split('	');
            if (!IsJapanese(fields[highlighted + 3]) && selected[0].Length > 10 &&
                (selected.Length <= 1 || selected[1].Length == 0))
                return highlighted;
        }
        if (count > 0 && !IsJapanese(fields[3]) && fields[3].Split('	')[0].Length > 10)
            return 0;
        return -1;
    }

    private static void SetSentence(TextBlock label, string text, string reading)
    {
        label.Inlines.Clear();
        int tail = text.Length;
        while (tail > 0 && text[tail - 1] < 128)
            tail--;
        string raw = text.Substring(tail);
        if (raw.Length == 0 || !reading.EndsWith(raw, StringComparison.Ordinal))
        {
            label.Inlines.Add(new Run(text));
            return;
        }
        label.Inlines.Add(new Run(text.Substring(0, tail)));
        var unconverted = new Run(raw) { FontStyle = FontStyles.Italic };
        if (!SystemParameters.HighContrast)
            unconverted.Foreground = Brushes.BurlyWood;
        label.Inlines.Add(unconverted);
    }

    private static Window CreateWindow()
    {
        Window window;
        using (var layout = typeof(GlassPreview).Assembly.GetManifestResourceStream("GlassPreview.xaml"))
            window = (Window)XamlReader.Load(layout);

        var strip = (StackPanel)window.FindName("Candidates");
        string[] samples = { "我", "握", "窩", "臥", "斡", "を" };
        int selected = 0;
        bool highContrast = SystemParameters.HighContrast;
        var rose = new LinearGradientBrush(Color.FromRgb(106, 75, 67), Color.FromRgb(126, 95, 87), 15);
        Action<int> select = index =>
        {
            var previous = (Button)strip.Children[selected];
            previous.Background = Brushes.Transparent;
            if (highContrast)
                previous.Foreground = SystemColors.WindowTextBrush;
            selected = index;
            var current = (Button)strip.Children[selected];
            current.Background = highContrast ? (Brush)SystemColors.HighlightBrush : rose;
            if (highContrast)
                current.Foreground = SystemColors.HighlightTextBrush;
        };

        for (int i = 0; i < samples.Length; i++)
        {
            int index = i;
            int number = i < 5 ? i + 1 : 7;
            var content = new StackPanel { Orientation = Orientation.Horizontal };
            content.Children.Add(new TextBlock
            {
                Text = number.ToString(),
                FontSize = 20,
                Opacity = 0.62,
                Margin = new Thickness(0, 0, 18, 0),
                VerticalAlignment = VerticalAlignment.Center
            });
            content.Children.Add(new TextBlock { Text = samples[i], FontSize = 27 });
            var button = new Button { Content = content };
            AutomationProperties.SetName(button, number + " " + samples[i]);
            button.Click += (sender, args) => select(index);
            strip.Children.Add(button);
        }
        if (highContrast)
        {
            ((System.Windows.Shapes.Rectangle)window.FindName("GlassSurface")).Fill = SystemColors.WindowBrush;
            foreach (Button button in strip.Children)
                button.Foreground = SystemColors.WindowTextBrush;
        }
        if (!highContrast)
            new GlassBackdrop(window);
        select(0);

        window.PreviewKeyDown += (sender, args) =>
        {
            Key key = args.Key;
            if (key == Key.Escape)
                window.Close();
            else if (key >= Key.D1 && key <= Key.D5)
                select(key - Key.D1);
            else if (key == Key.D7)
                select(samples.Length - 1);
            else if (key == Key.Left || key == Key.Right)
                select((selected + samples.Length + (key == Key.Left ? -1 : 1)) % samples.Length);
            else
                return;

            args.Handled = true;
        };
        window.MouseLeftButtonDown += (sender, args) => window.DragMove();
        return window;
    }
}
