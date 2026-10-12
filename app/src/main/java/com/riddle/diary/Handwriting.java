package com.riddle.diary;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tom's hand, ported from riddle's src/script.rs:
 * rasterize the reply in Dancing Script, thin it to single-pixel pen paths
 * (Zhang-Suen), trace the skeleton into ordered polylines, and hand them
 * back for stroke-by-stroke animation.
 */
public final class Handwriting {

    private Handwriting() {}

    /** One rasterized line, reduced to skeleton pen strokes in canvas coords. */
    public static final class Line {
        public final List<List<PointF>> strokes;
        public final float x, y, width, height;
        Line(List<List<PointF>> strokes, float x, float y, float width, float height) {
            this.strokes = strokes;
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    /** Wrap text into lines that fit maxWidthPx at the given text size. */
    public static List<String> wrap(String text, Paint p, float maxWidthPx) {
        List<String> lines = new ArrayList<>();
        String[] paras = text.split("\n", -1);
        for (String para : paras) {
            String cur = "";
            for (String word : para.split("\\s+")) {
                if (word.isEmpty()) continue;
                String cand = cur.isEmpty() ? word : cur + " " + word;
                if (p.measureText(cand) <= maxWidthPx || cur.isEmpty()) {
                    cur = cand;
                } else {
                    lines.add(cur);
                    cur = word;
                }
            }
            if (!cur.isEmpty()) lines.add(cur);
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /**
     * Synthesize handwriting for {@code text}; lines are laid out starting at
     * (x0, y0), wrapped to maxWidthPx, at sizePx glyph height.
     */
    public static List<Line> synthesize(String text, Typeface face, float sizePx,
                                        float maxWidthPx, float x0, float y0) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(sizePx);
        p.setColor(0xFF111111);

        List<Line> out = new ArrayList<>();
        float lineHeight = sizePx * 1.22f;
        float y = y0;
        for (String lineText : wrap(text, p, maxWidthPx)) {
            if (lineText.isEmpty()) { y += lineHeight; continue; }
            Line l = rasterizeLine(p, lineText, x0, y, sizePx);
            if (l != null) out.add(l);
            y += lineHeight;
        }
        return out;
    }

    /** Rasterize one line, thin it, trace it. Returns null if no ink. */
    private static Line rasterizeLine(Paint p, String text, float x0, float y0, float sizePx) {
        float wF = p.measureText(text);
        int w = Math.max(1, (int) Math.ceil(wF) + 6);
        int h = Math.max(1, (int) Math.ceil(sizePx * 1.5f) + 6);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = -fm.top + 2f;
        c.drawText(text, 3f, baseline, p);

        // Boolean mask: inked = alpha > 128.
        boolean[] mask = new boolean[w * h];
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int inked = 0;
        for (int i = 0; i < px.length; i++) {
            if (((px[i] >>> 24) & 0xFF) > 128) { mask[i] = true; inked++; }
        }
        bmp.recycle();
        if (inked == 0) return null;

        thin(mask, w, h);
        List<List<PointF>> strokes = trace(mask, w, h);
        if (strokes.isEmpty()) return null;
        for (List<PointF> s : strokes)
            for (PointF pt : s) { pt.x += x0; pt.y += y0; }
        return new Line(strokes, x0, y0, w, h);
    }

    /** Zhang-Suen thinning: reduce the mask to 1px-wide skeleton lines. */
    static void thin(boolean[] m, int w, int h) {
        boolean changed;
        do {
            changed = false;
            for (int phase = 0; phase < 2; phase++) {
                List<Integer> clear = new ArrayList<>();
                for (int y = 1; y < h - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        if (!m[y * w + x]) continue;
                        boolean[] p = {
                            m[(y - 1) * w + x], m[(y - 1) * w + x + 1], m[y * w + x + 1],
                            m[(y + 1) * w + x + 1], m[(y + 1) * w + x], m[(y + 1) * w + x - 1],
                            m[y * w + x - 1], m[(y - 1) * w + x - 1],
                        };
                        int b = 0;
                        for (boolean v : p) if (v) b++;
                        if (b < 2 || b > 6) continue;
                        int a = 0;
                        for (int i = 0; i < 8; i++) if (!p[i] && p[(i + 1) % 8]) a++;
                        if (a != 1) continue;
                        boolean c1, c2;
                        if (phase == 0) {
                            c1 = !(p[0] && p[2] && p[4]);
                            c2 = !(p[2] && p[4] && p[6]);
                        } else {
                            c1 = !(p[0] && p[2] && p[6]);
                            c2 = !(p[0] && p[4] && p[6]);
                        }
                        if (c1 && c2) clear.add(y * w + x);
                    }
                }
                if (!clear.isEmpty()) {
                    changed = true;
                    for (int i : clear) m[i] = false;
                }
            }
        } while (changed);
    }

    /** Trace the skeleton into polyline strokes, ordered left-to-right. */
    static List<List<PointF>> trace(boolean[] m, int w, int h) {
        java.util.function.BiPredicate<Integer, Integer> at = (x, y) ->
            x >= 0 && y >= 0 && x < w && y < h && m[y * w + x];

        java.util.function.BiFunction<Integer, Integer, Integer> deg = (x, y) -> {
            int n = 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    if ((dx != 0 || dy != 0) && at.test(x + dx, y + dy)) n++;
            return n;
        };

        boolean[] visited = new boolean[w * h];
        // Endpoints (degree 1) first, then any remaining pixels (loops).
        List<int[]> starts = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (at.test(x, y) && deg.apply(x, y) == 1) starts.add(new int[]{x, y});
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (at.test(x, y)) starts.add(new int[]{x, y});

        List<List<PointF>> strokes = new ArrayList<>();
        for (int[] s : starts) {
            int sx = s[0], sy = s[1];
            if (visited[sy * w + sx]) continue;
            List<PointF> path = new ArrayList<>();
            path.add(new PointF(sx, sy));
            visited[sy * w + sx] = true;
            int cx = sx, cy = sy;
            while (true) {
                int nx = -1, ny = -1;
                for (int dy = -1; dy <= 1 && nx < 0; dy++)
                    for (int dx = -1; dx <= 1 && nx < 0; dx++) {
                        int tx = cx + dx, ty = cy + dy;
                        if ((dx != 0 || dy != 0) && at.test(tx, ty) && !visited[ty * w + tx]) {
                            nx = tx; ny = ty;
                        }
                    }
                if (nx < 0) break;
                visited[ny * w + nx] = true;
                path.add(new PointF(nx, ny));
                cx = nx; cy = ny;
            }
            if (path.size() >= 3) strokes.add(path);
        }
        strokes.sort(Comparator.comparingDouble(s -> {
            float min = Float.MAX_VALUE;
            for (PointF pt : s) min = Math.min(min, pt.x);
            return min;
        }));
        return strokes;
    }
}
