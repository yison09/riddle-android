package com.riddle.diary;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The diary page. Write with a finger or pen; after a pause of ~2.8s the
 * diary drinks your ink (the strokes fade into the paper) and hands the page
 * to the oracle. Tom's reply writes itself back stroke by stroke in a
 * flowing hand, then fades away. A conjured memory page rises through the
 * paper in faded ink; touch to return.
 */
public class DiaryView extends View {

    public interface Host {
        /** The page was committed (drunk); ask the oracle. */
        void onCommit(Bitmap page, long ts);
        /** The top-right corner was tapped. */
        void onSettingsTap();
    }

    private enum State { READY, DRINKING, THINKING, INKING, CONJURING }

    private static final long IDLE_COMMIT_MS = 2800;
    private static final float ERASE_RADIUS_DP = 26f;
    private static final float CORNER_TAP_DP = 64f;
    private static final float MOVE_TOL_DP = 20f;
    private static final float PAGE_MAX_W = 1024f;

    // --- user ink ---
    private static final class Stroke {
        final List<PointF> pts = new ArrayList<>();
        float width;
    }
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint replyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- state ---
    private State state = State.READY;
    private float drinkAlpha = 1f;      // user ink alpha (DRINKING)
    private float replyAlpha = 1f;      // reply ink alpha
    private long thinkStart;            // THINKING indicator phase
    private final ExecutorService synth = Executors.newSingleThreadExecutor();

    // --- reply ink ---
    private static final class InkGroup {
        final List<Handwriting.Line> lines;
        final boolean faded;
        float len;      // total path length
        float drawn;    // length written so far
        InkGroup(List<Handwriting.Line> lines, boolean faded) {
            this.lines = lines; this.faded = faded;
            for (Handwriting.Line l : lines)
                for (List<PointF> s : l.strokes)
                    for (int i = 1; i < s.size(); i++)
                        len += dist(s.get(i - 1), s.get(i));
        }
        boolean done() { return drawn >= len; }
    }
    private final ArrayList<InkGroup> inkQueue = new ArrayList<>();
    private InkGroup inking;
    private boolean streamDone = false;
    private float replyStartY;
    private float replyCursorY;
    private float replyTextSize;
    private float inkSpeed;             // px/s of pen motion
    private long lastFrameNs;
    private ValueAnimator endAnimator;
    private final Runnable finishReply = this::beginFinishReply;

    // --- conjured page ---
    private Bitmap conjureBmp;
    private float conjureAlpha;
    private String conjureDate = "";
    private String conjureReply = "";
    private float density;

    private Host host;
    private Typeface typeface;
    private final ValueAnimator drinkAnim;

    public DiaryView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        inkPaint.setColor(0xFF171717);
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        replyPaint.setColor(0xFF171717);
        replyPaint.setStyle(Paint.Style.STROKE);
        replyPaint.setStrokeCap(Paint.Cap.ROUND);
        replyPaint.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(0xFFFBFAF6); // aged paper
        setFocusable(true);

        drinkAnim = ValueAnimator.ofFloat(1f, 0f);
        drinkAnim.setDuration(1100);
        drinkAnim.addUpdateListener(an -> {
            drinkAlpha = (float) an.getAnimatedValue();
            invalidate();
        });
        drinkAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                Bitmap page = renderPage();
                strokes.clear();
                drinkAlpha = 1f;
                state = State.THINKING;
                thinkStart = SystemClock.uptimeMillis();
                invalidate();
                if (host != null) host.onCommit(page, System.currentTimeMillis());
            }
        });
    }

    public void setHost(Host h) { host = h; }
    public void setTypeface(Typeface tf) { typeface = tf; }

    // ---------------------------------------------------------------------
    // Public API for the host (oracle events)
    // ---------------------------------------------------------------------

    /** Queue one sentence of Tom's reply for handwriting animation. */
    public void enqueueSentence(String text) {
        if (text == null || text.trim().isEmpty() || typeface == null) return;
        final float size = Math.max(28f, getWidth() * 0.052f);
        final float maxWidth = getWidth() - 48f * density;
        synth.execute(() -> {
            List<Handwriting.Line> lines = Handwriting.synthesize(
                text, typeface, size, maxWidth, 24f * density, 0f);
            post(() -> {
                if (lines.isEmpty()) return;
                if (endAnimator != null) { endAnimator.cancel(); endAnimator = null; }
                replyAlpha = 1f;
                if (replyCursorY == 0) replyCursorY = replyStartY;
                // re-offset lines to the running cursor
                float shift = replyCursorY;
                for (Handwriting.Line l : lines)
                    for (List<PointF> s : l.strokes)
                        for (PointF p : s) p.y += shift;
                replyCursorY += (size * 1.22f) * lines.size();
                inkQueue.add(new InkGroup(lines, false));
                if (state == State.THINKING || state == State.READY) beginInking();
                invalidate();
            });
        });
    }

    /** The oracle stream has ended (replies may still be animating). */
    public void replyFinished() {
        post(() -> {
            streamDone = true;
            maybeScheduleFinish();
        });
    }

    /** Conjure a remembered page: it rises through the paper in faded ink. */
    public void conjure(Bitmap page, String dateText, String replyText) {
        post(() -> {
            conjureBmp = page;
            conjureDate = dateText == null ? "" : dateText;
            conjureReply = replyText == null ? "" : replyText;
            state = State.CONJURING;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 0.34f);
            a.setDuration(900);
            a.addUpdateListener(an -> { conjureAlpha = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        });
    }

    // ---------------------------------------------------------------------
    // Ink pipeline
    // ---------------------------------------------------------------------

    private void beginInking() {
        if (endAnimator != null) { endAnimator.cancel(); endAnimator = null; }
        state = State.INKING;
        if (inking == null && !inkQueue.isEmpty()) inking = inkQueue.remove(0);
        lastFrameNs = 0;
        invalidate();
    }

    private void maybeScheduleFinish() {
        if (state == State.INKING && streamDone && inking == null && inkQueue.isEmpty()
            && !finishReplyScheduled) {
            finishReplyScheduled = true;
            postDelayed(finishReply, 2400);
        }
    }
    private boolean finishReplyScheduled = false;

    private void beginFinishReply() {
        finishReplyScheduled = false;
        ValueAnimator a = ValueAnimator.ofFloat(replyAlpha, 0f);
        a.setDuration(900);
        a.addUpdateListener(an -> { replyAlpha = (float) an.getAnimatedValue(); invalidate(); });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                if (!inkQueue.isEmpty() || inking != null) return; // raced with new ink
                inkQueue.clear();
                inking = null;
                replyAlpha = 1f;
                replyCursorY = 0;
                streamDone = false;
                state = State.READY;
                invalidate();
            }
        });
        endAnimator = a;
        a.start();
    }

    private void commit() {
        if (state != State.READY || strokes.isEmpty()) return;
        int pts = 0;
        for (Stroke s : strokes) pts += s.pts.size();
        android.util.Log.i("RiddleOracle", "commit: " + strokes.size()
            + " strokes, " + pts + " pts, page " + getWidth() + "x" + getHeight());
        // Tom answers below where you wrote.
        float maxY = 0;
        for (Stroke s : strokes)
            for (PointF p : s.pts) maxY = Math.max(maxY, p.y);
        replyStartY = Math.max(getHeight() * 0.30f,
            Math.min(getHeight() * 0.86f, maxY + 48f * density));
        replyCursorY = 0;
        state = State.DRINKING;
        drinkAnim.start();
    }

    /** Rasterize the committed page to a small PNG-ready bitmap. */
    private Bitmap renderPage() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float scale = Math.min(1f, PAGE_MAX_W / w);
        Bitmap bmp = Bitmap.createBitmap(Math.round(w * scale), Math.round(h * scale),
            Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        c.scale(scale, scale);
        drawStrokes(c, strokes, inkPaint, 1f);
        return bmp;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (state == State.CONJURING) { drawConjured(c); return; }

        drawStrokes(c, strokes, inkPaint, drinkAlpha);
        if (state == State.THINKING) drawThinking(c);
        if (state == State.INKING) advanceAndDrawReply(c);
        else if (inking != null || !inkQueue.isEmpty()) drawReply(c);
    }

    /** Three pulsing ink dots while the oracle is thinking. */
    private void drawThinking(Canvas c) {
        float size = Math.max(6f * density, getWidth() * 0.011f);
        float cx = getWidth() / 2f;
        float cy = replyStartY + size * 2f;
        long t = SystemClock.uptimeMillis() - thinkStart;
        for (int i = 0; i < 3; i++) {
            float phase = (t - i * 260f) / 320f;
            float k = phase <= 0 ? 0f : (phase >= 1 ? 1f
                : (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)));
            replyPaint.setStyle(Paint.Style.FILL);
            replyPaint.setAlpha(Math.round(255 * (0.22f + 0.5f * k)));
            c.drawCircle(cx + (i - 1) * size * 2.8f, cy, size * (0.45f + 0.45f * k), replyPaint);
        }
        replyPaint.setStyle(Paint.Style.STROKE);
        postInvalidateDelayed(50);
    }

    private final Path reusePath = new Path();

    private void drawStrokes(Canvas c, List<Stroke> list, Paint paint, float alpha) {
        if (alpha <= 0.01f) return;
        int a = Math.round(255 * alpha);
        paint.setAlpha(a);
        Path path = reusePath;
        for (Stroke s : list) {
            if (s.pts.size() < 2) {
                if (s.pts.size() == 1) {
                    paint.setStrokeWidth(s.width);
                    PointF p = s.pts.get(0);
                    c.drawPoint(p.x, p.y, paint);
                }
                continue;
            }
            paint.setStrokeWidth(s.width);
            path.rewind();
            path.moveTo(s.pts.get(0).x, s.pts.get(0).y);
            for (int i = 1; i < s.pts.size(); i++) path.lineTo(s.pts.get(i).x, s.pts.get(i).y);
            c.drawPath(path, paint);
        }
    }

    private void advanceAndDrawReply(Canvas c) {
        long now = System.nanoTime();
        if (lastFrameNs == 0) lastFrameNs = now;
        float dt = Math.min(0.05f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;

        if (inking == null && !inkQueue.isEmpty()) inking = inkQueue.remove(0);
        if (inking != null) {
            inking.drawn += inkSpeed * dt;
            if (inking.done()) inking = null;
        }
        drawReply(c);
        if (inking == null) maybeScheduleFinish();
        else invalidate();
    }

    /** Draw all pending + current reply ink up to the drawn length. */
    private void drawReply(Canvas c) {
        if (inking == null && inkQueue.isEmpty()) return;
        List<InkGroup> all = new ArrayList<>();
        if (inking != null) all.add(inking);
        all.addAll(inkQueue);
        for (InkGroup g : all) {
            int a = Math.round(255 * replyAlpha * (g.faded ? 0.36f : 1f));
            if (a == 0) continue;
            replyPaint.setAlpha(a);
            drawGroup(c, g, g == inking ? g.drawn : g.len);
        }
    }

    private void drawGroup(Canvas c, InkGroup g, float upTo) {
        float acc = 0;
        Path path = new Path();
        for (Handwriting.Line line : g.lines) {
            for (List<PointF> stroke : line.strokes) {
                if (stroke.size() < 2) continue;
                replyPaint.setStrokeWidth(Math.max(1.6f, density * 0.9f));
                path.rewind();
                path.moveTo(stroke.get(0).x, stroke.get(0).y);
                boolean penDown = upTo <= 0;
                for (int i = 1; i < stroke.size(); i++) {
                    float seg = dist(stroke.get(i - 1), stroke.get(i));
                    if (!penDown) {
                        float remain = upTo - acc;
                        if (remain <= 0) break;
                        if (remain < seg) {
                            float t = remain / seg;
                            float x = stroke.get(i - 1).x + (stroke.get(i).x - stroke.get(i - 1).x) * t;
                            float y = stroke.get(i - 1).y + (stroke.get(i).y - stroke.get(i - 1).y) * t;
                            path.lineTo(x, y);
                            acc = upTo;
                            break;
                        }
                        acc += seg;
                    }
                    path.lineTo(stroke.get(i).x, stroke.get(i).y);
                    if (acc >= upTo) break;
                }
                c.drawPath(path, replyPaint);
                if (acc >= upTo) return;
            }
        }
    }

    private void drawConjured(Canvas c) {
        Paint dateP = new Paint(Paint.ANTI_ALIAS_FLAG);
        dateP.setTypeface(typeface);
        dateP.setTextSize(20f * density);
        dateP.setColor(Color.rgb(23, 23, 23));
        dateP.setAlpha(Math.round(255 * conjureAlpha / 0.34f * 0.55f));

        Paint replyP = new Paint(Paint.ANTI_ALIAS_FLAG);
        replyP.setTypeface(typeface);
        replyP.setTextSize(Math.max(24f, getWidth() * 0.042f));
        replyP.setColor(Color.rgb(23, 23, 23));
        replyP.setAlpha(Math.round(255 * conjureAlpha / 0.34f * 0.5f));

        float top = 90f * density;
        dateP.setTextAlign(Paint.Align.CENTER);
        c.drawText(conjureDate, getWidth() / 2f, top, dateP);

        if (conjureBmp != null) {
            float maxW = getWidth() * 0.86f;
            float maxH = getHeight() * 0.5f;
            float s = Math.min(maxW / conjureBmp.getWidth(), maxH / conjureBmp.getHeight());
            float dx = (getWidth() - conjureBmp.getWidth() * s) / 2f;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(s, s);
            m.postTranslate(dx, top + 24f * density);
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
            bp.setAlpha(Math.round(255 * conjureAlpha / 0.34f));
            c.drawBitmap(conjureBmp, m, bp);
            top += conjureBmp.getHeight() * s + 40f * density;
        }
        if (!conjureReply.isEmpty()) {
            replyP.setTextAlign(Paint.Align.LEFT);
            float x = 28f * density;
            float y = top + replyP.getTextSize();
            for (String ln : Handwriting.wrap(conjureReply, replyP, getWidth() - 56f * density)) {
                c.drawText(ln, x, y, replyP);
                y += replyP.getTextSize() * 1.25f;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    private float lastX, lastY;
    private boolean twoTapPending;
    private boolean moved;
    private long downTime;
    private float downX, downY;
    private boolean cornerTap;
    private Runnable commitRunnable = this::commit;

    private float er() { return ERASE_RADIUS_DP * density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();

        if (state == State.CONJURING) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                conjureBmp = null;
                conjureAlpha = 0;
                state = streamDone && (inking != null || !inkQueue.isEmpty())
                    ? State.INKING : State.READY;
                invalidate();
            }
            return true;
        }
        if (state != State.READY) return true; // drinking / thinking / inking: pen rests

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                removeCallbacks(commitRunnable);
                downTime = SystemClock.uptimeMillis();
                downX = x; downY = y;
                moved = false;
                twoTapPending = false;
                float corner = CORNER_TAP_DP * density;
                cornerTap = x > getWidth() - corner && y < corner;
                if (!cornerTap) startStroke(x, y, pressure(e));
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                moved = true; // a second finger lands: not a clean tap, and no ink from it
                if (current != null) { current = null; }
                twoTapPending = !cornerTap;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (cornerTap) {
                    if (dist(x, y, downX, downY) > MOVE_TOL_DP * density) cornerTap = false;
                    break;
                }
                if (dist(x, y, downX, downY) > MOVE_TOL_DP * density) moved = true;
                float dirtyL = x, dirtyT = y, dirtyR = x, dirtyB = y;
                for (int i = 0; i < e.getHistorySize() + 1; i++) {
                    float hx = i < e.getHistorySize() ? e.getHistoricalX(i) : x;
                    float hy = i < e.getHistorySize() ? e.getHistoricalY(i) : y;
                    float hp = i < e.getHistorySize() ? e.getHistoricalPressure(i) : pressure(e);
                    if (eraserMode) eraseAt(hx, hy);
                    else extendStroke(hx, hy, hp);
                    dirtyL = Math.min(dirtyL, hx); dirtyT = Math.min(dirtyT, hy);
                    dirtyR = Math.max(dirtyR, hx); dirtyB = Math.max(dirtyB, hy);
                }
                float pad = 32f * density;
                invalidate((int) (dirtyL - pad), (int) (dirtyT - pad),
                    (int) (dirtyR + pad), (int) (dirtyB + pad));
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                twoTapPending = false;
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (cornerTap) {
                    cornerTap = false;
                    if (!moved && host != null) host.onSettingsTap();
                    break;
                }
                boolean wasTwoTap = twoTapPending && !moved
                    && SystemClock.uptimeMillis() - downTime < 400;
                twoTapPending = false;
                if (wasTwoTap) {
                    eraserMode = !eraserMode;
                    current = null;
                    invalidate();
                    break;
                }
                finishStroke();
                if (!strokes.isEmpty()) postDelayed(commitRunnable, IDLE_COMMIT_MS);
                break;
            }
        }
        invalidate();
        return true;
    }

    private boolean eraserMode = false;
    public boolean isEraserMode() { return eraserMode; }

    private float pressure(MotionEvent e) {
        if (e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_STYLUS) {
            float p = e.getAxisValue(MotionEvent.AXIS_PRESSURE);
            if (p > 0) return Math.max(0.1f, Math.min(1f, p));
        }
        return 0.5f;
    }

    private void startStroke(float x, float y, float p) {
        current = new Stroke();
        current.pts.add(new PointF(x, y));
        current.width = baseWidth(p);
    }

    private void extendStroke(float x, float y, float p) {
        if (current == null) { startStroke(x, y, p); return; }
        PointF last = current.pts.get(current.pts.size() - 1);
        if (dist(last.x, last.y, x, y) < 2.5f * density) return;
        current.pts.add(new PointF(x, y));
        current.width = (current.width * 3f + baseWidth(p)) / 4f;
    }

    private void finishStroke() {
        if (current != null && current.pts.size() >= 1) strokes.add(current);
        current = null;
    }

    private float baseWidth(float p) {
        return (1.8f + 3.4f * p) * density;
    }

    private void eraseAt(float x, float y) {
        float r = er();
        boolean any = false;
        for (Iterator<Stroke> it = strokes.iterator(); it.hasNext(); ) {
            Stroke s = it.next();
            for (PointF p : s.pts) {
                if (dist(p.x, p.y, x, y) < r) { it.remove(); any = true; break; }
            }
        }
        if (any) invalidate();
    }

    private static float dist(PointF a, PointF b) {
        return dist(a.x, a.y, b.x, b.y);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        replyTextSize = Math.max(28f, w * 0.052f);
        replyStartY = h * 0.42f;
        replyCursorY = 0;
        inkSpeed = 420f * density;
    }
}
