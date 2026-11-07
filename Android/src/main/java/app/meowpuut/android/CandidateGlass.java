package app.meowpuut.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class CandidateGlass extends Drawable {
    private final float density;
    private final boolean dark;
    private final boolean cats;
    private final KeyboardPalette palette;
    private final int strength;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path detail = new Path();

    CandidateGlass(float density, boolean dark, boolean cats, KeyboardPalette palette, int strength) {
        this.density = density;
        this.dark = dark;
        this.cats = cats;
        this.palette = palette;
        this.strength = strength;
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(density, density);
        float w = getBounds().width() / density;
        float h = getBounds().height() / density;
        if (w < 75 || h < 24) {
            canvas.restoreToCount(save);
            return;
        }
        outline.reset();
        if (!cats) {
            outline.addRoundRect(1, 1, w - 1, h - 1, h / 2, h / 2, Path.Direction.CW);
        } else {
            catOutline(w, h);
        }
        paint.setStyle(Paint.Style.FILL);
        int board = palette.board(dark);
        int lit = MeowpuutKeyboard.tint(board, Color.WHITE, dark ? .31f : .78f);
        int body = MeowpuutKeyboard.tint(board, Color.WHITE, dark ? .14f : .48f);
        int depth = MeowpuutKeyboard.tint(board, Color.BLACK, dark ? .17f : .04f);
        int returnLight = MeowpuutKeyboard.tint(board, palette.accent(dark), .045f * (strength + 1));
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{lit, body, depth, returnLight},
                new float[]{0f, .24f, .81f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(outline, paint);
        int reflection = canvas.save();
        canvas.clipPath(outline);
        paint.setShader(new RadialGradient(w * .66f, -h * .45f, w * .53f,
                dark ? 0x48ffffff : 0x9affffff, 0x00ffffff, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        canvas.restoreToCount(reflection);
        paint.setShader(null);
        if (cats) drawCats(canvas, w, h);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.7f);
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xd4ffffff, 0x32ffffff, dark ? 0x90000000 : 0x48000000},
                new float[]{0f, .47f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(outline, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    private void catOutline(float w, float h) {
        outline.moveTo(2, 20);
        outline.quadTo(4, -1, 10, 1);
        outline.lineTo(20, 7);
        outline.quadTo(29, -5, 35, 8);
        outline.lineTo(w - 34, 8);
        outline.quadTo(w - 29, -5, w - 19, 7);
        outline.lineTo(w - 9, 1);
        outline.quadTo(w - 4, -2, w - 2, h - 20);
        outline.quadTo(w - 3, h - 1, w - 22, h - 1);
        outline.lineTo(22, h - 1);
        outline.quadTo(3, h - 1, 2, 20);
        outline.close();

    }

    private void drawCats(Canvas canvas, float w, float h) {
        int clip = canvas.save();
        canvas.clipPath(outline);

        paint.setShader(new LinearGradient(0, 0, 42, 0, 0xff161316, 0x00161316, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 42, h, paint);
        paint.setShader(null);
        float center = h / 2;
        for (int x : new int[]{5, 19}) {
            detail.reset();
            detail.moveTo(x, center - 6);
            detail.lineTo(x + 10, center - (x == 5 ? 3 : 10));
            detail.quadTo(x + 8, center + 7, x, center - 6);
            paint.setColor(0xfffff0df);
            canvas.drawPath(detail, paint);
            paint.setColor(0xff81602b);
            canvas.drawOval(x + 5, center - 7, x + 7, center, paint);
        }

        paint.setShader(new RadialGradient(w - 24, center - 8, 24,
                0xfff5e5d4, 0xffb59881, Shader.TileMode.CLAMP));
        canvas.drawOval(w - 35, center - 13, w - 3, center + 13, paint);
        paint.setShader(null);
        paint.setColor(0xffb1947e);
        canvas.drawOval(w - 30, center - 8, w - 7, center + 8, paint);
        paint.setColor(0xff426f89);
        canvas.drawOval(w - 26, center - 6, w - 23, center, paint);
        canvas.drawOval(w - 12, center - 6, w - 9, center, paint);
        detail.reset();
        detail.moveTo(w - 21, center + 4);
        detail.quadTo(w - 19, center + 7, w - 17, center + 4);
        detail.quadTo(w - 16, center + 7, w - 14, center + 4);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0xff2a2020);
        canvas.drawPath(detail, paint);

        detail.reset();
        detail.moveTo(Math.min(94, w * .32f), h - 3);
        detail.cubicTo(46, h, 32, h - 2, 17, h - 5);
        detail.quadTo(9, h - 12, 22, h - 11);
        paint.setColor(dark ? 0xff32272f : 0xfff4e5e5);
        paint.setStrokeWidth(3);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(detail, paint);
        canvas.restoreToCount(clip);

    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
