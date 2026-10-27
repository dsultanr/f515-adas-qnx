package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/** Схема дистанции (из F515 ADAS): дорога движется со скоростью машины, позиция цели интерполируется.
 * Данные — поток fcwd с QNX; состояние 0 нет, 1 предупреждение, 2 тревога. Полосы модели не рисуются. */
public class AdasRadarView extends View {
    private static final float WIDTH = 1200, HEIGHT = 420;
    private static final int MUTED = 0xFF89939D, TEXT = 0xFFE6EBEF;
    private static final int NORMAL = 0xFF92B5BE, AMBER = 0xFFE9B663, RED = 0xFFEF7775;
    private static final int[] SIDES = {-1, 1}, MARKS = {10, 30, 60};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final LinearGradient roadGradient = new LinearGradient(0, 98, 0, 420,
            0xFF161C22, 0xFF293139, Shader.TileMode.CLAMP);
    private final LinearGradient bodyGradient = new LinearGradient(-60, 0, 60, 0,
            new int[]{0xFF63717D, 0xFFD0D8DE, 0xFF9AA7B1, 0xFF52606C},
            new float[]{0, .30f, .73f, 1}, Shader.TileMode.CLAMP);
    private final LinearGradient glassGradient = new LinearGradient(0, -70, 0, -18,
            0xFF384851, 0xFF11191F, Shader.TileMode.CLAMP);
    private float speed = Float.NaN, distance, relativeSpeedMs, displayedDistance, laneTravel;
    private boolean detected, active, attached;
    private int warning = 0;
    private long sampleAt, drawnAt;
    private float lateral;
    private String speedText = "—", distanceText = "—", closingText = "—";
    private String headwayText = "—", ttcText = "—", confidenceText = "";
    private String backendText = "Ожидание данных", motionText = "Нет автомобиля впереди";
    private String perceptionText = "";
    private final Runnable frame = new Runnable() {
        @Override public void run() { invalidate(); }
    };

    public AdasRadarView(Context context) { super(context); init(); }
    public AdasRadarView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        body.setShader(bodyGradient); glass.setShader(glassGradient);
        setContentDescription("Схема дистанции до автомобиля впереди");
    }

    public void updateTelemetry(float speedKmh, float dist, float vRelMs, float ttc,
            int state, boolean found, float score, boolean linkActive, String backend) {
        speed = speedKmh;
        relativeSpeedMs = Float.isFinite(vRelMs) ? vRelMs : 0;
        active = linkActive;
        boolean valid = active && found && Float.isFinite(dist) && dist > 0 && dist <= 200;
        if (valid && !detected) displayedDistance = dist;
        detected = valid; distance = valid ? dist : 0;
        warning = valid ? state : 0;
        sampleAt = SystemClock.uptimeMillis();
        speedText = Float.isFinite(speed) && speed >= 0 ? format("%.0f", speed) : "—";
        distanceText = valid ? format("%.1f", dist) : "—";
        closingText = valid && Float.isFinite(vRelMs) ? format("%+.0f", relativeSpeedMs * 3.6f) : "—";
        headwayText = valid && speed > 5 ? format("%.1f", dist * 3.6f / speed) : "—";
        ttcText = valid && ttc > 0 && ttc < 60 ? format("%.1f", ttc) : "—";
        confidenceText = valid ? format("Достоверность %.0f%%", score * 100) : "";
        motionText = !valid ? "Нет автомобиля впереди" : !Float.isFinite(vRelMs) ? "Уточнение движения цели" : relativeSpeedMs < -.3f ? "Приближается"
                : relativeSpeedMs > .3f ? "Удаляется" : "Дистанция стабильна";
        backendText = backend == null ? "Ожидание данных" : backend;
        invalidate();
    }
    private static String format(String pattern, float value) {
        return String.format(Locale.getDefault(), pattern, value);
    }
    /** Боковое смещение цели, м (+ вправо). */
    public void setLateral(float metres) {
        lateral = Float.isFinite(metres) ? Math.max(-5.4f, Math.min(5.4f, metres)) : 0;
    }
    public void updatePerception(String status){perceptionText=status==null?"":status;invalidate();}
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; drawnAt = 0; invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        attached = false; removeCallbacks(frame); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); removeCallbacks(frame); drawnAt = 0;
        if (visibility == VISIBLE) invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = drawnAt == 0 ? 0 : Math.min(.1f, Math.max(0, (now - drawnAt) / 1000f));
        drawnAt = now;
        boolean fresh = now - sampleAt < 1500;
        boolean moving = fresh && Float.isFinite(speed) && speed > .5f;
        if (moving) laneTravel = (laneTravel + speed / 3.6f * dt) % 12;
        if (detected && fresh) {
            float prediction = distance + relativeSpeedMs * Math.min(.22f, (now - sampleAt) / 1000f);
            float target = Math.max(1, Math.min(200, prediction));
            displayedDistance += (target - displayedDistance) * (1 - (float)Math.exp(-dt / .13f));
        }
        float scale = Math.min(getWidth() / WIDTH, getHeight() / HEIGHT);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
        canvas.scale(scale, scale);
        drawRoad(canvas);
        if (detected && fresh) drawLead(canvas);
        drawEgoArc(canvas); drawHeader(canvas, fresh); drawMetrics(canvas, fresh);
        canvas.restore();
        removeCallbacks(frame);
        // At most 30 animation frames/s while visible. No perpetual ValueAnimator.
        if (attached && isShown() && fresh) {
            postDelayed(frame, moving || detected ? 33 : Math.max(1, 1500 - (now - sampleAt)));
        }
    }

    private void drawHeader(Canvas c, boolean fresh) {
        label(c, "ОБЗОР ДИСТАНЦИИ", 28, 29, 13, MUTED, medium);
        label(c, fresh ? backendText + (perceptionText.isEmpty()?"":"  ·  "+perceptionText) : "Ожидание телеметрии", 28, 51, 12, MUTED, regular);
        text.setTextAlign(Paint.Align.RIGHT);
        label(c, fresh ? speedText : "—", 1101, 40, 36, TEXT, light);
        text.setTextAlign(Paint.Align.LEFT);
        label(c, "км/ч", 1112, 39, 14, MUTED, regular);
        stroke(c, 28, 67, 1172, 67, 0xFF2B343D, 1);
        label(c, "Схема движения", 28, 401, 11, 0xFF69747E, regular);
    }
    private float perspective(float metres) { return 1 / (1 + Math.max(0, metres) / 18); }
    private float roadY(float metres) { return 96 + 310 * perspective(metres); }
    private float roadX(float lane, float metres) { return 436 + lane * 136 * perspective(metres); }
    private void drawRoad(Canvas c) {
        path.reset();
        path.moveTo(roadX(-3, 100), roadY(100)); path.lineTo(roadX(3, 100), roadY(100));
        path.lineTo(roadX(3, 0), roadY(0)); path.lineTo(roadX(-3, 0), roadY(0)); path.close();
        fill.setShader(roadGradient); c.drawPath(path, fill); fill.setShader(null);
        for (int side : SIDES) {
            stroke(c, roadX(side * 3, 100), roadY(100), roadX(side * 3, 0), roadY(0), 0xFF35404A, 1.2f);
            // Four metres of paint, eight-metre gaps, moving at measured ego speed.
            for (float z = -laneTravel; z < 100; z += 12) {
                float near = Math.max(0, z), far = Math.max(0, z + 4);
                if (far <= 0) continue;
                float half = .010f;
                path.reset(); path.moveTo(roadX(side - half, near), roadY(near));
                path.lineTo(roadX(side + half, near), roadY(near));
                path.lineTo(roadX(side + half, far), roadY(far));
                path.lineTo(roadX(side - half, far), roadY(far)); path.close();
                fill.setColor(0xFF84919C); c.drawPath(path, fill);
            }
        }
        for (int metres : MARKS) {
            float y = roadY(metres), x = roadX(-1, metres);
            stroke(c, x - 19, y, x - 8, y, 0xFF52606A, 1);
            text.setTextAlign(Paint.Align.RIGHT);
            label(c, metres + " м", x - 26, y + 4, 11, 0xFF7B8791, regular);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }
    private int accent() {
        return warning == 2 ? RED
                : warning == 1 ? AMBER : NORMAL;
    }
    private void drawLead(Canvas c) {
        float q = perspective(Math.max(5, displayedDistance));
        float y = 96 + 310 * q, scale = .38f + .92f * q;
        c.save(); c.translate(roadX(lateral/1.8f,Math.max(5,displayedDistance)), y); c.scale(scale, scale);
        fill.setColor(0x60000000); rect.set(-73, -5, 73, 17); c.drawOval(rect, fill);
        rounded(c, -56, -21, -42, 7, 4, 0xFF0B1015);
        rounded(c, 42, -21, 56, 7, 4, 0xFF0B1015);
        path.reset(); path.moveTo(-60, -8); path.lineTo(-57, -42);
        path.quadTo(-54, -51, -43, -55); path.lineTo(-35, -76);
        path.quadTo(0, -83, 35, -76); path.lineTo(43, -55);
        path.quadTo(54, -51, 57, -42); path.lineTo(60, -8);
        path.quadTo(0, 5, -60, -8); path.close(); c.drawPath(path, body);
        path.reset(); path.moveTo(-39, -53); path.lineTo(-31, -72);
        path.quadTo(0, -77, 31, -72); path.lineTo(39, -53);
        path.quadTo(0, -48, -39, -53); path.close(); c.drawPath(path, glass);
        stroke(c, -35, -76, 35, -76, 0xFFDBE2E7, 1.5f);
        stroke(c, -47, -44, 47, -44, 0xFFB6C1C9, 1.2f);
        // Lamp housings remain neutral: no brake-light observation is available.
        rounded(c, -54, -38, -27, -33, 2, 0xFF743C43);
        rounded(c, 27, -38, 54, -33, 2, 0xFF743C43);
        rounded(c, -13, -27, 13, -18, 2, 0xFF444F58);
        rounded(c, -48, -12, 48, -6, 3, 0xFF39454F);
        stroke(c, -51, -9, 51, -9, 0xFF8F9CA6, 1);
        stroke(c, -70, 9, -70, 18, accent(), 2);
        stroke(c, -70, 18, 70, 18, accent(), 2);
        stroke(c, 70, 18, 70, 9, accent(), 2);
        c.restore();
        float labelY = y - 88 * scale - 12;
        text.setTextAlign(labelY >= 101 ? Paint.Align.CENTER : Paint.Align.LEFT);
        label(c, distanceText + " м", labelY >= 101 ? 436 : 436 + 74 * scale + 12,
                Math.max(101, labelY), 22, TEXT, medium);
        text.setTextAlign(Paint.Align.LEFT);
    }
    private void drawEgoArc(Canvas c) {
        rect.set(344, 374, 528, 434);
        line.setColor(NORMAL);
        line.setStrokeWidth(3);
        c.drawArc(rect, 198, 144, false, line);
    }
    private void drawMetrics(Canvas c, boolean fresh) {
        boolean target = detected && fresh;
        rounded(c, 884, 91, 1172, 397, 14, 0xFF1B232B);
        String status = !fresh ? "Нет свежих данных" : !active ? "Детектор не активен"
                : !target ? "Нет цели впереди"
                : warning == 2 ? "Опасное сближение"
                : warning == 1 ? "Малая дистанция" : "Автомобиль впереди";
        fill.setColor(target ? accent() : MUTED); c.drawCircle(907, 116, 3, fill);
        label(c, status, 921, 121, 15, target ? TEXT : MUTED, medium);
        label(c, target ? distanceText : "—", 905, 182, 48, TEXT, light);
        label(c, "м · оценка", 1076, 181, 13, MUTED, regular);
        label(c, target ? motionText : "Ожидание обнаружения", 906, 207, 13, MUTED, regular);
        stroke(c, 906, 225, 1150, 225, 0xFF35404A, 1);
        metric(c, "Интервал", target ? headwayText : "—", "с", 254);
        metric(c, "Относительная скорость", target ? closingText : "—", "км/ч", 289);
        metric(c, "Время до сближения", target ? ttcText : "—", "с", 324);
        label(c, target ? confidenceText : "Дорожная камера · вход 4 · QNX DSP", 906, 375, 11, MUTED, regular);
    }
    private void metric(Canvas c, String name, String value, String unit, float y) {
        label(c, name, 906, y, 12, MUTED, regular);
        text.setTextAlign(Paint.Align.RIGHT);
        label(c, value + " " + unit, 1150, y, 16, TEXT, medium);
        text.setTextAlign(Paint.Align.LEFT);
    }
    private void rounded(Canvas c, float l, float t, float r, float b, float radius, int color) {
        fill.setColor(color); rect.set(l, t, r, b); c.drawRoundRect(rect, radius, radius, fill);
    }
    private void stroke(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
        line.setColor(color); line.setStrokeWidth(width); c.drawLine(x1, y1, x2, y2, line);
    }
    private void label(Canvas c, String value, float x, float y, float size, int color, Typeface font) {
        text.setTypeface(font); text.setTextSize(size); text.setColor(color); c.drawText(value, x, y, text);
    }
}
