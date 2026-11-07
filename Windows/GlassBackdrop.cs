using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Point = System.Windows.Point;
using Image = System.Windows.Controls.Image;

internal sealed class GlassBackdrop : IDisposable
{
    private const int Padding = 24;
    private const uint ExcludeFromCapture = 0x11;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowDisplayAffinity(IntPtr window, uint affinity);

    private struct RimSample
    {
        internal int Output;
        internal Point Source;
        internal double Opacity;
        internal double Glint;
    }

    private readonly Window window;
    private readonly Image backdrop;
    private readonly Image rim;
    private readonly List<RimSample> rimSamples = new List<RimSample>();
    private readonly WriteableBitmap rimFrame;
    private readonly byte[] rimPixels;
    private readonly DispatcherTimer timer;
    private Bitmap capture;
    private byte[] pixels;
    private WriteableBitmap frame;

    internal GlassBackdrop(Window window)
    {
        this.window = window;
        backdrop = (Image)window.FindName("Backdrop");
        rim = (Image)window.FindName("Refraction");
        rimFrame = new WriteableBitmap((int)rim.Width, (int)rim.Height, 96, 96, PixelFormats.Pbgra32, null);
        rimPixels = new byte[rimFrame.PixelWidth * rimFrame.PixelHeight * 4];
        BuildRimMap((Geometry)window.Resources["CatOutline"]);
        timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80) };
        timer.Tick += (sender, args) => Update();
        window.Loaded += (sender, args) =>
        {
            // Exclude the preview so its own text and highlights cannot feed back into the glass.
            if (SetWindowDisplayAffinity(new WindowInteropHelper(window).Handle, ExcludeFromCapture))
                timer.Start();
        };
        window.Closed += (sender, args) => Dispose();
    }

    private void Update()
    {
        if (!window.IsVisible || window.WindowState == WindowState.Minimized)
            return;

        var origin = backdrop.PointToScreen(new Point());
        var end = backdrop.PointToScreen(new Point(backdrop.Width, backdrop.Height));
        int width = (int)Math.Round(end.X - origin.X);
        int height = (int)Math.Round(end.Y - origin.Y);
        if (width <= 0 || height <= 0)
            return;

        if (capture == null || capture.Width != width || capture.Height != height)
        {
            if (capture != null)
                capture.Dispose();
            capture = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppRgb);
            pixels = new byte[width * height * 4];
            frame = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgr32, null);
        }

        try
        {
            using (var graphics = Graphics.FromImage(capture))
                graphics.CopyFromScreen((int)origin.X, (int)origin.Y, 0, 0, capture.Size, CopyPixelOperation.SourceCopy);
        }
        catch (System.ComponentModel.Win32Exception)
        {
            backdrop.Source = null;
            rim.Source = null;
            return;
        }

        var area = new Rectangle(0, 0, width, height);
        var data = capture.LockBits(area, ImageLockMode.ReadOnly, System.Drawing.Imaging.PixelFormat.Format32bppRgb);
        try
        {
            Marshal.Copy(data.Scan0, pixels, 0, pixels.Length);
        }
        finally
        {
            capture.UnlockBits(data);
        }

        frame.WritePixels(new Int32Rect(0, 0, width, height), pixels, width * 4, 0);
        backdrop.Source = frame;

        UpdateRim(width, height);
    }

    private void BuildRimMap(Geometry outline)
    {
        const double rimDepth = 13;
        var figure = outline.GetFlattenedPathGeometry(0.25, ToleranceType.Absolute).Figures[0];
        var points = new List<Point> { figure.StartPoint };
        foreach (PathSegment segment in figure.Segments)
        {
            var polyline = segment as PolyLineSegment;
            if (polyline != null)
                points.AddRange(polyline.Points);
            else
                points.Add(((LineSegment)segment).Point);
        }
        points.Add(figure.StartPoint);

        int width = rimFrame.PixelWidth;
        for (int y = 0; y < rimFrame.PixelHeight; y++)
        for (int x = 0; x < width; x++)
        {
            var point = new Point(x + 0.5, y + 0.5);
            double closest = rimDepth * rimDepth;
            Vector offset = new Vector();
            for (int i = 1; i < points.Count; i++)
            {
                Vector edge = points[i] - points[i - 1];
                if (edge.LengthSquared == 0)
                    continue;
                double along = Vector.Multiply(point - points[i - 1], edge) / edge.LengthSquared;
                Vector delta = point - (points[i - 1] + Math.Max(0, Math.Min(1, along)) * edge);
                if (delta.LengthSquared < closest)
                {
                    closest = delta.LengthSquared;
                    offset = delta;
                }
            }

            if (closest >= rimDepth * rimDepth || closest < 0.0001)
                continue;
            double depth = Math.Sqrt(closest);
            Vector inward = offset / depth;
            double fade = 1 - depth / rimDepth;
            double feather = fade * fade * (3 - 2 * fade);
            Point source = point - inward * (10 * feather);
            rimSamples.Add(new RimSample
            {
                Output = (y * width + x) * 4,
                Source = new Point((source.X + Padding) / backdrop.Width, (source.Y + Padding) / backdrop.Height),
                Opacity = 0.84 * feather,
                Glint = 0.22 * Math.Abs(inward.Y) * fade * fade * fade
            });
        }
    }

    private void UpdateRim(int width, int height)
    {
        foreach (RimSample sample in rimSamples)
        {
            double x = sample.Source.X * width - 0.5;
            double y = sample.Source.Y * height - 0.5;
            int left = (int)x;
            int top = (int)y;
            int row = (top * width + left) * 4;
            int nextRow = row + width * 4;
            double fx = x - left;
            double fy = y - top;

            for (int channel = 0; channel < 3; channel++)
            {
                int a = row + channel;
                int b = nextRow + channel;
                double upper = pixels[a] * (1 - fx) + pixels[a + 4] * fx;
                double lower = pixels[b] * (1 - fx) + pixels[b + 4] * fx;
                double color = upper * (1 - fy) + lower * fy;
                rimPixels[sample.Output + channel] = (byte)((color + (255 - color) * sample.Glint) * sample.Opacity);
            }
            rimPixels[sample.Output + 3] = (byte)(255 * sample.Opacity);
        }

        int rimWidth = rimFrame.PixelWidth;
        rimFrame.WritePixels(new Int32Rect(0, 0, rimWidth, rimFrame.PixelHeight), rimPixels, rimWidth * 4, 0);
        rim.Source = rimFrame;
    }

    public void Dispose()
    {
        timer.Stop();
        if (capture != null)
            capture.Dispose();
    }
}
