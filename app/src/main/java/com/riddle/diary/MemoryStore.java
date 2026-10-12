package com.riddle.diary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The diary remembers: every finished page (the actual pen strokes as a PNG,
 * a transcription, and Tom's reply) lives in plain files under
 * filesDir/memories — last 400 pages kept. Port of riddle's memory model.
 */
public class MemoryStore {

    public static final int KEEP = 400;

    public static final class Page {
        public long id;
        public long ts;
        public String transcript;
        public String reply;
    }

    private final File dir;
    private final File indexFile;
    private final List<Page> pages = new ArrayList<>();
    private long nextId = 1;

    public MemoryStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "memories");
        dir.mkdirs();
        indexFile = new File(dir, "pages.json");
        load();
    }

    public synchronized Page add(long ts, String transcript, String reply, Bitmap page) {
        Page p = new Page();
        p.id = nextId++;
        p.ts = ts;
        p.transcript = transcript == null ? "" : transcript;
        p.reply = reply == null ? "" : reply;
        pages.add(p);
        if (page != null) {
            try (FileOutputStream out = new FileOutputStream(pngOf(p.id))) {
                page.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                // a page the diary can't keep is simply forgotten
            }
        }
        while (pages.size() > KEEP) {
            Page old = pages.remove(0);
            //noinspection ResultOfMethodCallIgnored
            pngOf(old.id).delete();
        }
        save();
        return p;
    }

    public synchronized List<Page> pages() { return new ArrayList<>(pages); }

    public synchronized Page byId(long id) {
        for (Page p : pages) if (p.id == id) return p;
        return null;
    }

    public Bitmap load(Page p) {
        File f = pngOf(p.id);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** Catalog lines shown to the model, newest first, numbered from 1. */
    public synchronized List<String> catalogLines() {
        List<String> out = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("MMMM d, yyyy, HH:mm", Locale.ENGLISH);
        int n = 0;
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page p = pages.get(i);
            String gist = p.transcript.isEmpty() ? "(blank page)" : gist(p.transcript);
            out.add((++n) + ". " + fmt.format(new Date(p.ts)) + " — " + gist);
        }
        return out;
    }

    /** Catalog ids in the same order as catalogLines(). */
    public synchronized List<Long> catalogIds() {
        List<Long> out = new ArrayList<>();
        for (int i = pages.size() - 1; i >= 0; i--) out.add(pages.get(i).id);
        return out;
    }

    /** Recent (transcript, reply) pairs, oldest first. */
    public synchronized List<String[]> history(int n) {
        List<String[]> out = new ArrayList<>();
        for (int i = Math.max(0, pages.size() - n); i < pages.size(); i++) {
            Page p = pages.get(i);
            if (!p.transcript.isEmpty()) out.add(new String[]{p.transcript, p.reply});
        }
        return out;
    }

    private File pngOf(long id) { return new File(dir, "page-" + id + ".png"); }

    private static String gist(String s) {
        String[] words = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length && i < 10; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return words.length > 10 ? sb.append("…").toString() : sb.toString();
    }

    private void load() {
        if (!indexFile.exists()) return;
        try (FileInputStream in = new FileInputStream(indexFile)) {
            byte[] b = new byte[(int) indexFile.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONArray arr = new JSONArray(new String(b, 0, off, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                Page p = new Page();
                p.id = j.getLong("id");
                p.ts = j.getLong("ts");
                p.transcript = j.optString("transcript", "");
                p.reply = j.optString("reply", "");
                pages.add(p);
                nextId = Math.max(nextId, p.id + 1);
            }
        } catch (Exception e) {
            pages.clear();
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Page p : pages) {
                JSONObject j = new JSONObject();
                j.put("id", p.id);
                j.put("ts", p.ts);
                j.put("transcript", p.transcript);
                j.put("reply", p.reply);
                arr.put(j);
            }
            try (FileOutputStream out = new FileOutputStream(indexFile)) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            // the index is a convenience; the PNGs are the truth
        }
    }
}
