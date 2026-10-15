package com.riddle.diary;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The diary page. Write with a finger or pen; after a pause of ~2.8s the
 * diary drinks your ink (the strokes fade into the paper) and hands the page
 * to the oracle. Tom's reply writes itself back stroke by stroke in a
 * flowing hand, then fades away. A conjured memory page rises through the
 * paper in faded ink; touch to return.
 *
 * Rendering is layered into three off-screen bitmaps so each frame is O(1)
 * w.r.t. how much has been written: bgBmp (paper, rules, margins), pageBmp
 * (this round's committed user ink), replyBmp (settled reply ink). Only the
 * stroke currently under the finger and the sentence currently being written
 * are rebuilt as Paths each frame.
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

    // --- paper layers (all rebuilt only on size change) ---
    private Bitmap bgBmp;      // paper colour, grain, rules, gutter, holes, date
    private Bitmap pageBmp;    // this round's user ink (settled strokes)
    private Bitmap replyBmp;   // settled reply ink (fades as a whole)
    private Canvas bgCv, pageCv, replyCv;
    private boolean hasInk;    // pageBmp holds ink this round
    private boolean hinted;    // empty-page hint already shown once
    private String bgDate = ""; // date baked into the background
    private byte[] pendingDraft; // restored draft waiting for the first layout

    // --- user ink ---
    private static final class Stroke {
        final List<PointF> pts = new ArrayList<>();
        float width;
    }
    private final List<Stroke> strokes = new ArrayList<>(); // data only (y-extent, emptiness)
    private Stroke current;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint replyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint alphaBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path reusePath = new Path();

    // --- state ---
    private State state = State.READY;
    private float drinkAlpha = 1f;      // page ink alpha (DRINKING)
    private float replyAlpha = 1f;      // settled reply ink alpha (finishing)
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
        eraserPaint.setStyle(Paint.Style.FILL);
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setBackgroundColor(0xFFFAF7EF); // paper fallback while bitmaps build
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
                if (pageCv != null) pageCv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                hasInk = false;
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
            // Nothing was ever queued (empty reply, or synthesis produced no ink):
            // leave the thinking state now instead of sitting in it forever.
            if (state == State.THINKING && inking == null && inkQueue.isEmpty()) {
                state = State.READY;
                invalidate();
                return;
            }
            maybeScheduleFinish();
        });
    }

    /** Conjure a remembered page: it rises through the paper in faded ink. */
    public void conjure(Bitmap page, String dateText, String replyText) {
        post(() -> {
            recycleConjured();
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

    /** The decoded memory page is owned by this view; drop it when done. */
    private void recycleConjured() {
        if (conjureBmp != null && !conjureBmp.isRecycled()) conjureBmp.recycle();
        conjureBmp = null;
    }

    // ---------------------------------------------------------------------
    // Draft persistence — the page survives the app being killed
    // ---------------------------------------------------------------------

    /** Serialize the un-committed ink so a killed app can restore the page. */
    public byte[] dumpDraft() {
        List<Stroke> all = new ArrayList<>(strokes);
        if (current != null && !current.pts.isEmpty()) all.add(current);
        if (all.isEmpty()) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(all.size());
            for (Stroke s : all) {
                out.writeFloat(s.width);
                out.writeInt(s.pts.size());
                for (PointF p : s.pts) {
                    out.writeFloat(p.x);
                    out.writeFloat(p.y);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Restore a draft; safe to call before the view has been laid out. */
    public void loadDraft(byte[] data) {
        if (data == null || data.length == 0) return;
        if (pageCv == null) { pendingDraft = data; return; }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int n = in.readInt();
            if (n <= 0 || n > 20000) return;
            for (int i = 0; i < n; i++) {
                Stroke s = new Stroke();
                s.width = in.readFloat();
                int cnt = in.readInt();
                if (cnt <= 0 || cnt > 200000) return;
                for (int k = 0; k < cnt; k++) {
                    s.pts.add(new PointF(in.readFloat(), in.readFloat()));
                }
                strokes.add(s);
                settleStroke(s);
            }
            hasInk = !strokes.isEmpty();
            hinted = hasInk;
            invalidate();
        } catch (Exception e) {
            strokes.clear();
            hasInk = false;
        }
        pendingDraft = null;
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
                if (replyCv != null) replyCv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
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
        streamDone = false;   // a fresh turn starts here
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
        if (pageBmp != null) c.drawBitmap(pageBmp, 0, 0, bitmapPaint);
        return bmp;
    }

    /** Settle one finished stroke into pageBmp (O(stroke), once). */
    private void settleStroke(Stroke s) {
        if (pageCv == null || s.pts.isEmpty()) return;
        inkPaint.setAlpha(255);
        if (s.pts.size() == 1) {
            PointF p = s.pts.get(0);
            inkPaint.setStrokeWidth(s.width);
            pageCv.drawPoint(p.x, p.y, inkPaint);
            return;
        }
        inkPaint.setStrokeWidth(s.width);
        reusePath.rewind();
        reusePath.moveTo(s.pts.get(0).x, s.pts.get(0).y);
        for (int i = 1; i < s.pts.size(); i++)
            reusePath.lineTo(s.pts.get(i).x, s.pts.get(i).y);
        pageCv.drawPath(reusePath, inkPaint);
    }

    /** Settle a completed reply group into replyBmp (drawn once, not per frame). */
    private void settleGroup(InkGroup g) {
        if (replyCv == null) return;
        int a = Math.round(255 * (g.faded ? 0.36f : 1f));
        if (a == 0) return;
        replyPaint.setAlpha(a);
        drawGroupInto(replyCv, g, g.len);
    }

    // ---------------------------------------------------------------------
    // Paper background (rebuilt only when the view size changes)
    // ---------------------------------------------------------------------

    private void buildBackground(int w, int h) {
        Bitmap old = bgBmp;
        bgBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bgCv = new Canvas(bgBmp);
        if (old != null && !old.isRecycled()) old.recycle();
        bgCv.drawColor(0xFFFAF7EF); // warm ivory

        // paper grain: 128px tile of faint speckle, tiled over the page
        Bitmap tile = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Random rnd = new Random(42);
        for (int i = 0; i < 900; i++) {
            int x = rnd.nextInt(128), y = rnd.nextInt(128);
            int a = 6 + rnd.nextInt(10);
            boolean dark = rnd.nextBoolean();
            tile.setPixel(x, y, dark ? Color.argb(a, 96, 88, 72)
                                     : Color.argb(a, 255, 255, 255));
        }
        Paint grain = new Paint(Paint.FILTER_BITMAP_FLAG);
        grain.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        bgCv.drawRect(0, 0, w, h, grain);
        tile.recycle();

        // horizontal rules (skip the top band where the date lives)
        float gap = 28f * density;
        Paint rule = new Paint();
        rule.setColor(0xFFE9E3D3);
        rule.setStrokeWidth(Math.max(1f, density));
        float first = h * 0.16f;
        for (float y = first; y < h - 8f * density; y += gap)
            bgCv.drawLine(10f * density, y, w - 10f * density, y, rule);

        // double red margin line on the left page
        Paint red = new Paint();
        red.setColor(0x88E4AEB0);
        red.setStrokeWidth(Math.max(1f, density));
        float mx1 = 64f * density, mx2 = 66.5f * density;
        bgCv.drawLine(mx1, 0, mx1, h, red);
        bgCv.drawLine(mx2, 0, mx2, h, red);

        // centre gutter: shadow gradients on both pages + binding line
        float cx = w / 2f;
        float gw = 22f * density;
        Paint gl = new Paint();
        gl.setShader(new LinearGradient(cx - gw, 0, cx, 0,
            0x18000000, 0x00000000, Shader.TileMode.CLAMP));
        bgCv.drawRect(cx - gw, 0, cx, h, gl);
        Paint gr = new Paint();
        gr.setShader(new LinearGradient(cx, 0, cx + gw, 0,
            0x00000000, 0x0A000000, Shader.TileMode.CLAMP));
        bgCv.drawRect(cx, 0, cx + gw, h, gr);
        Paint gut = new Paint();
        gut.setColor(0xFFCFC7B3);
        gut.setStrokeWidth(Math.max(1.5f, 1.2f * density));
        bgCv.drawLine(cx, 0, cx, h, gut);

        // binding holes down the far-left edge
        Paint holeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        holeFill.setColor(0xFFEFEADC);
        Paint holeEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        holeEdge.setStyle(Paint.Style.STROKE);
        holeEdge.setStrokeWidth(Math.max(1f, 0.8f * density));
        holeEdge.setColor(0xFFD5CEBB);
        int holes = 6;
        float hx = 15f * density, hr = 4.5f * density;
        for (int i = 0; i < holes; i++) {
            float hy = h * (0.12f + 0.76f * i / (holes - 1f));
            bgCv.drawCircle(hx, hy, hr, holeFill);
            bgCv.drawCircle(hx, hy, hr, holeEdge);
        }

        // date header, top right
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(0xFFB4AC97);
        dp.setTextSize(14f * density);
        dp.setTextAlign(Paint.Align.RIGHT);
        String date = todayStr();
        bgDate = date;
        bgCv.drawText(date, w - 20f * density, h * 0.075f + dp.getTextSize() / 2f, dp);
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyy年M月d日 · E", Locale.CHINESE).format(new Date());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // If the app was left open past midnight, redraw the header with today's date.
        if (hasFocus && bgBmp != null && getWidth() > 0 && !todayStr().equals(bgDate)) {
            buildBackground(getWidth(), getHeight());
            invalidate();
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (state == State.CONJURING) { drawConjured(c); return; }

        if (bgBmp != null) c.drawBitmap(bgBmp, 0, 0, bitmapPaint);

        // this round's user ink (fades while drinking)
        if (pageBmp != null && hasInk) {
            if (state == State.DRINKING && drinkAlpha < 1f) {
                alphaBitmapPaint.setAlpha(Math.round(255 * drinkAlpha));
                c.drawBitmap(pageBmp, 0, 0, alphaBitmapPaint);
                alphaBitmapPaint.setAlpha(255);
            } else {
                c.drawBitmap(pageBmp, 0, 0, bitmapPaint);
            }
        }

        // settled reply ink (fades as a whole while finishing)
        if (replyBmp != null) {
            boolean fading = replyAlpha < 1f;
            if (fading) {
                alphaBitmapPaint.setAlpha(Math.round(255 * replyAlpha));
                c.drawBitmap(replyBmp, 0, 0, alphaBitmapPaint);
                alphaBitmapPaint.setAlpha(255);
            } else {
                c.drawBitmap(replyBmp, 0, 0, bitmapPaint);
            }
        }

        if (state == State.THINKING) drawThinking(c);
        if (state == State.INKING) advanceAndDrawReply(c);
        else if (inking != null || !inkQueue.isEmpty()) drawReply(c);

        // the stroke currently under the finger — the only live Path
        if (current != null && !current.pts.isEmpty()) drawLiveStroke(c);

        // empty-page hint
        if (state == State.READY && !hinted && strokes.isEmpty()
            && inking == null && inkQueue.isEmpty() && replyCursorY == 0) {
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(0xFFC9C1AE);
            hp.setTextSize(19f * density);
            c.drawText("在这里写点什么…", 76f * density, h0() + hp.getTextSize(), hp);
            hinted = true;
        }
    }

    private float h0() { return getHeight() * 0.16f + 20f * density; }

    private void drawLiveStroke(Canvas c) {
        inkPaint.setAlpha(255);
        Stroke s = current;
        if (s.pts.size() == 1) {
            PointF p = s.pts.get(0);
            inkPaint.setStrokeWidth(s.width);
            c.drawPoint(p.x, p.y, inkPaint);
            return;
        }
        inkPaint.setStrokeWidth(s.width);
        reusePath.rewind();
        reusePath.moveTo(s.pts.get(0).x, s.pts.get(0).y);
        for (int i = 1; i < s.pts.size(); i++)
            reusePath.lineTo(s.pts.get(i).x, s.pts.get(i).y);
        c.drawPath(reusePath, inkPaint);
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

    private void advanceAndDrawReply(Canvas c) {
        long now = System.nanoTime();
        if (lastFrameNs == 0) lastFrameNs = now;
        float dt = Math.min(0.05f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;

        if (inking == null && !inkQueue.isEmpty()) inking = inkQueue.remove(0);
        if (inking != null) {
            inking.drawn += inkSpeed * dt;
            if (inking.done()) {
                settleGroup(inking);   // done sentences never redraw again
                inking = null;
            }
        }
        drawReply(c);
        if (inking == null) maybeScheduleFinish();
        else invalidate();
    }

    /** Draw the sentence being written (only up to the drawn length). */
    private void drawReply(Canvas c) {
        if (inking == null) return;
        int a = Math.round(255 * replyAlpha * (inking.faded ? 0.36f : 1f));
        if (a == 0) return;
        replyPaint.setAlpha(a);
        drawGroupInto(c, inking, inking.drawn);
    }

    /** Render one group's strokes up to path-length {@code upTo}. */
    private void drawGroupInto(Canvas c, InkGroup g, float upTo) {
        if (upTo <= 0) return;
        float acc = 0;
        Path path = reusePath;
        for (Handwriting.Line line : g.lines) {
            for (List<PointF> stroke : line.strokes) {
                if (stroke.size() < 2) continue;
                replyPaint.setStrokeWidth(Math.max(1.6f, density * 0.9f));
                path.rewind();
                path.moveTo(stroke.get(0).x, stroke.get(0).y);
                for (int i = 1; i < stroke.size(); i++) {
                    float seg = dist(stroke.get(i - 1), stroke.get(i));
                    float remain = upTo - acc;
                    if (remain <= 0) return;
                    if (remain < seg) {
                        float t = remain / seg;
                        float x = stroke.get(i - 1).x
                            + (stroke.get(i).x - stroke.get(i - 1).x) * t;
                        float y = stroke.get(i - 1).y
                            + (stroke.get(i).y - stroke.get(i - 1).y) * t;
                        path.lineTo(x, y);
                        c.drawPath(path, replyPaint);
                        return;
                    }
                    acc += seg;
                    path.lineTo(stroke.get(i).x, stroke.get(i).y);
                }
                c.drawPath(path, replyPaint);
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
    private boolean twoFingerTap;
    private boolean moved;
    private long downTime;
    private float downX, downY;
    private boolean cornerTap;
    private Runnable commitRunnable = this::commit;

    private float er() { return ERASE_RADIUS_DP * density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int act = e.getActionMasked();
        if (act != MotionEvent.ACTION_MOVE) {
            android.util.Log.i("RiddleTouch", "act=" + act + " ptrs=" + e.getPointerCount()
                + " (" + Math.round(x) + "," + Math.round(y) + ") eraser=" + eraserMode
                + " state=" + state);
        }

        if (state == State.CONJURING) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                recycleConjured();
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
                lastX = x; lastY = y;
                moved = false;
                twoFingerTap = false;
                float corner = CORNER_TAP_DP * density;
                cornerTap = x > getWidth() - corner && y < corner;
                if (!cornerTap) startStroke(x, y, pressure(e));
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A second finger lands: drop the live stroke (no ink from it),
                // and remember this as a two-finger-tap candidate if fingers stay still.
                twoFingerTap = !cornerTap && current != null;
                if (current != null) { current = null; }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (cornerTap) {
                    if (dist(x, y, downX, downY) > MOVE_TOL_DP * density) cornerTap = false;
                    break;
                }
                if (dist(x, y, downX, downY) > MOVE_TOL_DP * density) moved = true;
                // dirty box: every point of this batch + previous sample + margin
                float dirtyL = Math.min(x, lastX), dirtyT = Math.min(y, lastY);
                float dirtyR = Math.max(x, lastX), dirtyB = Math.max(y, lastY);
                for (int i = 0; i < e.getHistorySize() + 1; i++) {
                    float hx = i < e.getHistorySize() ? e.getHistoricalX(i) : x;
                    float hy = i < e.getHistorySize() ? e.getHistoricalY(i) : y;
                    float hp = i < e.getHistorySize() ? e.getHistoricalPressure(i) : pressure(e);
                    if (eraserMode) eraseAt(hx, hy);
                    else extendStroke(hx, hy, hp);
                    dirtyL = Math.min(dirtyL, hx); dirtyT = Math.min(dirtyT, hy);
                    dirtyR = Math.max(dirtyR, hx); dirtyB = Math.max(dirtyB, hy);
                }
                lastX = x; lastY = y;
                float pad = eraserMode ? er() + 8f * density : 32f * density;
                invalidate((int) (dirtyL - pad), (int) (dirtyT - pad),
                    (int) (dirtyR + pad), (int) (dirtyB + pad));
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                break; // fingers are lifting; ACTION_UP makes the final call
            }
            case MotionEvent.ACTION_CANCEL: {
                // Interrupted (call, dialog, gesture nav): settle the live stroke
                // so it isn't left dangling and the page can still commit.
                if (!eraserMode) finishStroke();
                else current = null;
                twoFingerTap = false;
                cornerTap = false;
                if (!strokes.isEmpty()) postDelayed(commitRunnable, IDLE_COMMIT_MS);
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (cornerTap) {
                    cornerTap = false;
                    if (!moved && host != null) host.onSettingsTap();
                    break;
                }
                boolean wasTwoTap = twoFingerTap && !moved
                    && SystemClock.uptimeMillis() - downTime < 400;
                twoFingerTap = false;
                if (wasTwoTap) {
                    eraserMode = !eraserMode;
                    current = null;
                    invalidate();
                    break;
                }
                if (eraserMode) current = null;   // erasing leaves no dot
                else finishStroke();
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
        hinted = true;
    }

    private void extendStroke(float x, float y, float p) {
        if (current == null) { startStroke(x, y, p); return; }
        PointF last = current.pts.get(current.pts.size() - 1);
        if (dist(last.x, last.y, x, y) < 2.5f * density) return;
        current.pts.add(new PointF(x, y));
        current.width = (current.width * 3f + baseWidth(p)) / 4f;
    }

    private void finishStroke() {
        if (current != null && !current.pts.isEmpty()) {
            strokes.add(current);
            settleStroke(current);       // drawn once into pageBmp
            hasInk = true;
        }
        current = null;
    }

    private float baseWidth(float p) {
        return (1.8f + 3.4f * p) * density;
    }

    private void eraseAt(float x, float y) {
        float r = er();
        if (pageCv != null) pageCv.drawCircle(x, y, r, eraserPaint);
        boolean removed = false;
        for (int si = strokes.size() - 1; si >= 0; si--) {
            Stroke s = strokes.get(si);
            boolean hit = false;
            for (PointF p : s.pts) {
                if (dist(p.x, p.y, x, y) < r) { hit = true; break; }
            }
            if (!hit) continue;
            // Erase only what the eraser actually touched: keep the untouched
            // runs of the stroke instead of dropping the whole line.
            strokes.remove(si);
            strokes.addAll(si, splitStroke(s, x, y, r));
            removed = true;
        }
        if (removed && strokes.isEmpty()) hasInk = pageHasVisibleInk();
    }

    /** The runs of a stroke's points that lie outside the eraser. */
    private static List<Stroke> splitStroke(Stroke s, float x, float y, float r) {
        List<Stroke> out = new ArrayList<>();
        Stroke cur = null;
        for (PointF p : s.pts) {
            if (dist(p.x, p.y, x, y) < r) { cur = null; continue; }
            if (cur == null) {
                cur = new Stroke();
                cur.width = s.width;
                out.add(cur);
            }
            cur.pts.add(p);
        }
        return out;
    }

    /** Emptiness check after erasing (the bitmap may still hold fragments). */
    private boolean pageHasVisibleInk() {
        if (pageBmp == null) return false;
        int w = pageBmp.getWidth(), h = pageBmp.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y += 16) {
            pageBmp.getPixels(row, 0, w, 0, y, w, 1);
            for (int px : row) if ((px & 0xFF000000) != 0) return true;
        }
        return false;
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

        if (w <= 0 || h <= 0) return;
        buildBackground(w, h);
        pageBmp = migrate(pageBmp, w, h);
        pageCv = new Canvas(pageBmp);
        replyBmp = migrate(replyBmp, w, h);
        replyCv = new Canvas(replyBmp);
        if (pendingDraft != null) {
            byte[] d = pendingDraft;
            pendingDraft = null;
            loadDraft(d);
        }
    }

    /** Recreate a layer at a new size, carrying over existing pixels. */
    private Bitmap migrate(Bitmap old, int w, int h) {
        Bitmap nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (old != null) {
            new Canvas(nb).drawBitmap(old, 0, 0, null);
            old.recycle();
        }
        return nb;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(commitRunnable);
        removeCallbacks(finishReply);
        if (endAnimator != null) { endAnimator.cancel(); endAnimator = null; }
        if (drinkAnim.isRunning()) drinkAnim.cancel();
        synth.shutdownNow();   // the synthesis worker dies with the view
    }
}
