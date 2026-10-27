package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import java.util.Locale;

/** Схема кадра дорожной камеры (1920x1080) с рамками fcwd, коридором своей полосы и ведущей целью.
 * Видео по сети не передаётся — только рамки, поэтому фон схематичный. */
public class CameraBoxesView extends View {
    // коридор своей полосы — те же доли кадра, что FcwCfg в fcwd (tracker.h)
    private static final float HORIZON = .50f, HOOD = .95f, CX = .515f, TOP = .03f, BOT = .34f;
    private static final int BG = 0xFF0F151B, ROAD = 0xFF1A222A, GRID = 0xFF2B343D, MUTED = 0xFF89939D, TEXT = 0xFFE6EBEF;
    private static final int OWN = 0xFFE6EBEF, ADJ = 0xFF7FB8D6, OTHER = 0xFF5E6A75, AMBER = 0xFFE9B663, RED = 0xFFEF7775;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private FcwdFrame frame;
    private long frameAt;

    public CameraBoxesView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription("Схема кадра камеры с обнаруженными машинами");
    }

    public void update(FcwdFrame f) { frame = f; frameAt = SystemClock.uptimeMillis(); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float s = Math.min(W / 1920f, H / 1080f), ox = (W - 1920 * s) / 2, oy = (H - 1080 * s) / 2;
        fill.setColor(BG); r.set(ox, oy, ox + 1920 * s, oy + 1080 * s); c.drawRoundRect(r, 10, 10, fill);
        // дорога и коридор своей полосы
        float yh = oy + 1080 * HORIZON * s, yb = oy + 1080 * HOOD * s;
        float cx = ox + 1920 * CX * s;
        path.reset();
        path.moveTo(cx - 1920 * TOP / 2 * s, yh); path.lineTo(cx + 1920 * TOP / 2 * s, yh);
        path.lineTo(cx + 1920 * BOT / 2 * s, yb); path.lineTo(cx - 1920 * BOT / 2 * s, yb); path.close();
        fill.setColor(ROAD); c.drawPath(path, fill);
        line.setColor(GRID); line.setStrokeWidth(2); c.drawPath(path, line);
        line.setColor(GRID); c.drawLine(ox, yh, ox + 1920 * s, yh, line);
        fill.setColor(0xFF161C22); c.drawRect(ox, yb, ox + 1920 * s, oy + 1080 * s, fill);   // капот
        label(c, "горизонт", ox + 10, yh - 6, 11, MUTED);
        FcwdFrame f = frame;
        boolean fresh = f != null && SystemClock.uptimeMillis() - frameAt < 1500;
        if (!fresh) { label(c, "Нет потока от fcwd", ox + 14, oy + 24, 14, MUTED); return; }
        FcwdFrame.Obj tgt = f.target();
        for (FcwdFrame.Obj o : f.objs) {
            boolean lead = tgt != null && o.id == tgt.id;
            int col = o.cls == 1 ? OWN : o.cls == 2 ? ADJ : OTHER;
            if (lead && f.state == 2) col = RED; else if (lead && f.state == 1) col = AMBER;
            line.setColor(col); line.setStrokeWidth(lead ? 4 : 2);
            r.set(ox + o.x1 * s, oy + o.y1 * s, ox + o.x2 * s, oy + o.y2 * s);
            c.drawRect(r, line);
            float d = FcwdFrame.groundDistance(o);
            String tag = Float.isFinite(d) && d < 150 ? String.format(Locale.US, "%.0f м", d) : "";
            if (lead && f.ttc > 0 && f.ttc < 30) tag += String.format(Locale.US, " · TTC %.1f с", f.ttc);
            label(c, tag, r.left, Math.max(oy + 14, r.top - 6), 12, col);
        }
        String head = String.format(Locale.US, "DSP %.0f мс · %.1f к/с · объектов %d", f.inferMs, f.fps, f.objs.length);
        label(c, head, ox + 14, oy + 24, 13, MUTED);
        if (f.vpActive || f.vpCipo) {
            String vp = String.format(Locale.US, "VP: %.1f м, %+.1f м/с, IDM %+.1f м/с²%s", f.vpD, f.vpVel, f.vpAccel,
                    f.vpCutIn ? " · перестроение" : "");
            label(c, vp, ox + 14, oy + 44, 13, f.vpLevel > 0 ? AMBER : MUTED);
        }
        if (f.state > 0) {
            text.setTextAlign(Paint.Align.RIGHT);
            label(c, f.state == 2 ? "ТРЕВОГА" : "РИСК СТОЛКНОВЕНИЯ", ox + 1920 * s - 14, oy + 26, 16, f.state == 2 ? RED : AMBER);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void label(Canvas c, String v, float x, float y, float size, int color) {
        text.setTextSize(size); text.setColor(color); c.drawText(v, x, y, text);
    }
}
