package app.meowpuut.android;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

final class MatteSurface extends Drawable {
    private static final Bitmap GRAIN = grain();
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texture = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final int top;
    private final int bottom;
    private final float radius;
    private final float edge;
    private final boolean textured;
    private final int highlight;

    MatteSurface(int top, int bottom, float radius, float edge, boolean dark, boolean textured) {
        this.top = top;
        this.bottom = bottom;
        this.radius = radius;
        this.edge = edge;
        this.textured = textured;
        if (textured) {
            texture.setShader(new BitmapShader(GRAIN, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        highlight = dark ? 0x34ffffff : 0xb0ffffff;
    }

    private static Bitmap grain() {
        int side = 96;
        int[] pixels = new int[side * side];
        Random random = new Random(41);
        for (int i = 0; i < pixels.length; i++) {
            int shade = random.nextInt(9) - 4;
            pixels[i] = Math.abs(shade) << 24 | (shade > 0 ? 0xffffff : 0);
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        shape.set(bounds);
        shape.inset(edge / 2, edge / 2);
        face.setShader(new LinearGradient(0, shape.top, 0, shape.bottom,
                top, bottom, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, shape.top, 0, shape.bottom,
                new int[]{highlight, 0x0dffffff, 0x38000000}, new float[]{0f, .48f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawRoundRect(shape, radius, radius, face);
        if (textured) canvas.drawRoundRect(shape, radius, radius, texture);
        canvas.drawRoundRect(shape, radius, radius, rim);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
