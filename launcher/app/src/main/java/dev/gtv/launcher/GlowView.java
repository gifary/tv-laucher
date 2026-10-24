package dev.gtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Soft ambient glow behind the favourites, tinted by the focused app. */
public class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = Color.TRANSPARENT;
    private int lastW, lastH, lastColor = -1;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public void setColor(int argb) {
        if (argb == color) return;
        color = argb;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || color == Color.TRANSPARENT) return;
        if (w != lastW || h != lastH || color != lastColor) {
            int rgb = color & 0x00FFFFFF;
            int r = Color.red(rgb), g = Color.green(rgb), b = Color.blue(rgb);
            // Anchored at the bottom edge and fading out before the top, so the
            // glow melts into the grid instead of ending in a hard band.
            float radius = h * 1.45f;
            paint.setShader(new RadialGradient(w / 2f, h,
                    radius,
                    new int[]{
                            Color.argb(58, r, g, b),
                            Color.argb(26, r, g, b),
                            Color.argb(0, r, g, b)
                    },
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            lastW = w; lastH = h; lastColor = color;
        }
        canvas.drawRect(0, 0, w, h, paint);
    }
}
