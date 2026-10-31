package net.room309.stations;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/** A stick figure that walks across the screen, used on the MOVE! screen between rounds. */
public class StickWalkerView extends View {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final long start = SystemClock.uptimeMillis();

    public StickWalkerView(Context context, int color) {
        super(context);
        body.setColor(color);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeCap(Paint.Cap.ROUND);
        body.setStrokeJoin(Paint.Join.ROUND);
        ground.setColor(color);
        ground.setAlpha(90);
        ground.setStyle(Paint.Style.STROKE);
        ground.setStrokeCap(Paint.Cap.ROUND);
        arrow.setColor(color);
        arrow.setAlpha(150);
        arrow.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float size = h * 0.9f;                     // figure height
        float stroke = Math.max(4f, size * 0.06f);
        body.setStrokeWidth(stroke);
        ground.setStrokeWidth(stroke * 0.6f);

        // Walk left to right, looping every 4.5 seconds.
        float cycle = 4.5f;
        float p = (t % cycle) / cycle;
        float margin = size * 0.6f;
        float x = -margin + p * (w + 2 * margin);

        float groundY = h * 0.97f;
        float phase = (float) (t * Math.PI * 2 * 1.4);   // steps per second
        float swing = (float) Math.sin(phase);
        float bob = (float) Math.abs(Math.cos(phase)) * size * 0.025f;

        float legLen = size * 0.42f;
        float hipY = groundY - legLen * 0.97f - bob;
        float neckY = hipY - size * 0.33f;
        float headR = size * 0.1f;
        float headY = neckY - headR * 1.15f;
        float armLen = size * 0.3f;

        // ground and a direction arrow
        c.drawLine(w * 0.04f, groundY + stroke, w * 0.96f, groundY + stroke, ground);
        float ax = w * 0.82f, ay = h * 0.35f, as = size * 0.16f;
        arrowPath.reset();
        arrowPath.moveTo(ax - as * 1.6f, ay - as * 0.28f);
        arrowPath.lineTo(ax, ay - as * 0.28f);
        arrowPath.lineTo(ax, ay - as * 0.75f);
        arrowPath.lineTo(ax + as, ay);
        arrowPath.lineTo(ax, ay + as * 0.75f);
        arrowPath.lineTo(ax, ay + as * 0.28f);
        arrowPath.lineTo(ax - as * 1.6f, ay + as * 0.28f);
        arrowPath.close();
        c.drawPath(arrowPath, arrow);

        // legs (opposite swing)
        drawLimb(c, x, hipY, legLen, 0.5f * swing);
        drawLimb(c, x, hipY, legLen, -0.5f * swing);
        // body, slightly leaning forward
        float lean = size * 0.04f;
        c.drawLine(x, hipY, x + lean, neckY, body);
        // arms swing opposite to legs
        float shoulderY = neckY + size * 0.04f;
        drawLimb(c, x + lean, shoulderY, armLen, -0.6f * swing);
        drawLimb(c, x + lean, shoulderY, armLen, 0.6f * swing);
        // head
        c.drawCircle(x + lean * 1.3f, headY, headR, body);

        postInvalidateOnAnimation();
    }

    /** A straight limb hanging down from (x, y), swung by angle (radians) toward the walking direction. */
    private void drawLimb(Canvas c, float x, float y, float len, float angle) {
        float ex = x + (float) Math.sin(angle) * len;
        float ey = y + (float) Math.cos(angle) * len;
        c.drawLine(x, y, ex, ey, body);
    }
}
