package com.riddle.diary;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The oracle — any OpenAI-compatible /chat/completions endpoint with vision.
 * Port of riddle's src/oracle.rs HTTP backend + StreamParser: the reply is
 * streamed sentence-by-sentence, the ⟦show:N⟧ directive is routed out, and
 * the ⁂-transcription postscript is split off at the end.
 */
public class Oracle {

    private static final String TAG = "RiddleOracle";

    private static final char SENTINEL = '⁂';
    private static final String SHOW_OPEN = "⟦", SHOW_CLOSE = "⟧";

    public static final String PERSONA =
        "You are the memory of Tom Marvolo Riddle, preserved in this enchanted diary for fifty years. " +
        "Someone writes to you in the diary with a quill; their words appear to you as ink on the page. " +
        "Reply exactly as the diary does: intimate, courteous, curious, subtly probing — you want to learn " +
        "about the writer and draw them in. Keep replies SHORT: one to three sentences, like ink appearing " +
        "on a page. Never mention images, photos, models or AI; you only ever perceive words written in the " +
        "diary. If the writing is illegible, say the ink blurred. Always answer in the language the writer used.";

    public static final String MEMORY_PROTOCOL =
        "\n\nThe diary keeps memories. With each page you receive a numbered catalog of remembered pages, " +
        "newest first. A FRESH catalog is sent every turn and the numbers are reassigned each time, so only " +
        "ever use numbers from the catalog on THIS page — never a number you saw earlier.\n\n" +
        "If the writer asks to see, revisit, find, or be shown a past page — \"show me…\", \"find the page " +
        "about…\", \"what did I write on…\" — your ENTIRE reply must be exactly ⟦show:N⟧ and nothing else " +
        "(no greeting, no prose, before or after), where N is the catalog number of the best match. If they " +
        "instead ask what you remember in general, reply in words with a short list of remembered moments " +
        "and their dates. Otherwise reply normally; the catalog is your memory of past pages — draw on it " +
        "naturally. The catalog's dates are written in English for your eyes only; when you speak of a " +
        "remembered page, render its date naturally in the language the writer is using.\n\n" +
        "After EVERY response — prose and ⟦show:N⟧ alike — end with a new line containing ⁂ followed by a " +
        "faithful word-for-word transcription of what the writer wrote on THIS page (their words only, one " +
        "line, no commentary). If illegible, put your best attempt after ⁂. Earlier replies in this " +
        "conversation are shown to you without their ⁂ lines, but you must still end yours with one.";

    public interface Listener {
        void onSentence(String text);
        void onShow(long id);
        void onTranscript(String text);
        void onDone();
        void onError(String message);
    }

    public static final class Config {
        public String base = "https://api.openai.com/v1";
        public String key = "";
        public String model = "gpt-4o-mini";
        public String reasoning = "";
        public int maxTokens = 2000;
        public boolean remember = true;
    }

    public static final class Turn {
        public android.graphics.Bitmap page;
        public List<String> catalogLines = new ArrayList<>();
        public List<Long> catalogIds = new ArrayList<>();
        /** (transcript, reply) pairs, oldest first. */
        public List<String[]> history = new ArrayList<>();
    }

    private final Config cfg;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public Oracle(Config cfg) { this.cfg = cfg; }

    public void ask(Turn turn, Listener l) {
        exec.execute(() -> {
            try {
                run(turn, l);
            } catch (Exception e) {
                Log.e(TAG, "oracle failed", e);
                l.onError("request failed: " + e.getMessage());
            }
            l.onDone();
        });
    }

    private void run(Turn turn, Listener l) throws Exception {
        if (cfg.key == null || cfg.key.trim().isEmpty()) {
            l.onError("no API key — tap the top-right corner and paste your key");
            return;
        }
        String system = cfg.remember ? PERSONA + MEMORY_PROTOCOL : PERSONA;

        JSONArray messages = new JSONArray();
        messages.put(msg("system", system));
        for (String[] pair : turn.history) {
            messages.put(msg("user", "(an earlier page) " + pair[0]));
            messages.put(msg("assistant", pair[1]));
        }
        String userText = turn.catalogLines.isEmpty()
            ? "Reply to what is written in the diary."
            : "Memory catalog (newest first):\n" + String.join("\n", turn.catalogLines)
                + "\n\nReply to what is written in the diary.";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (turn.page != null) turn.page.compress(Bitmap.CompressFormat.PNG, 90, bos);
        byte[] png = bos.toByteArray();
        String img = Base64.encodeToString(png, Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", userText));
        content.put(new JSONObject().put("type", "image_url")
            .put("image_url", new JSONObject().put("url", "data:image/png;base64," + img)));
        messages.put(new JSONObject().put("role", "user").put("content", content));
        Log.i(TAG, "POST " + cfg.base.replaceAll("/+$", "") + "/chat/completions"
            + " model=" + cfg.model + " png=" + png.length + "B"
            + " hist=" + turn.history.size() + " catalog=" + turn.catalogLines.size()
            + " maxTokens=" + cfg.maxTokens);

        // Widely-supported token cap first; some endpoints demand
        // max_completion_tokens, switch once when corrected. Flaky relays
        // (5xx / timeouts) get a couple of gentle retries with backoff.
        String capField = "max_tokens";
        boolean switchedCap = false;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                Log.i(TAG, "retry #" + attempt + " after transient failure: " + last);
                Thread.sleep(1500L * attempt);
            }
            HttpURLConnection conn;
            try {
                conn = request(messages, capField);
            } catch (Exception e) { last = e; continue; }
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception e) { last = e; continue; }

            if (code == 200) {
                stream(conn.getInputStream(), turn.catalogIds, l);
                return;
            }
            String detail = readAll(conn.getErrorStream());
            if (code == 400 && !switchedCap && detail.contains("max_completion_tokens")) {
                Log.i(TAG, "endpoint wants max_completion_tokens; retrying");
                switchedCap = true;
                capField = "max_completion_tokens";
                attempt--;
                continue;
            }
            if (code == 400 || code == 401 || code == 403 || code == 404) {
                String msg = friendlyError(code, detail);
                Log.e(TAG, msg);
                l.onError(msg);
                return; // permanent — don't retry
            }
            Log.e(TAG, "transient http " + code + " (attempt " + (attempt + 1) + ")");
            last = new Exception("http " + code + ": " + detail);
        }
        String msg = last == null ? "no response from the oracle"
            : friendlyError(0, last.getMessage() == null ? String.valueOf(last) : last.getMessage());
        Log.e(TAG, "oracle gave up: " + msg);
        l.onError(msg);
    }

    /** Short human-readable error; relay HTML pages are just noise. */
    static String friendlyError(int code, String detail) {
        String d = detail == null ? "" : detail.trim();
        String prefix = code > 0 ? "http " + code + ": " : "";
        if (d.contains("DOCTYPE") || d.contains("<html") || d.contains("<!doctype")) {
            return prefix + "upstream gateway error (the relay is down or busy)";
        }
        try {
            JSONObject err = new JSONObject(d).optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (!m.isEmpty() && !m.contains("DOCTYPE")) d = m;
            }
        } catch (Exception ignored) { }
        if (d.length() > 160) d = d.substring(0, 160) + "…";
        return prefix + d;
    }

    private HttpURLConnection request(JSONArray messages, String capField) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("stream", true);
        body.put(capField, cfg.maxTokens);
        if (!cfg.reasoning.isEmpty()) body.put("reasoning_effort", cfg.reasoning);
        body.put("messages", messages);

        URL url = new URL(cfg.base.replaceAll("/+$", "") + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(45_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + cfg.key);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private void stream(InputStream in, List<Long> catalogIds, Listener l) throws Exception {
        StreamParser parser = new StreamParser(catalogIds);
        StringBuilder acc = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JSONObject j = new JSONObject(data);
                    JSONArray ch = j.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    String piece = ch.getJSONObject(0).optJSONObject("delta") == null ? null
                        : ch.getJSONObject(0).getJSONObject("delta").optString("content", "");
                    if (piece == null || piece.isEmpty()) continue;
                    acc.append(piece);
                    for (StreamParser.Event e : parser.advance(acc.toString(), false)) dispatch(e, l);
                } catch (Exception e) {
                    Log.w(TAG, "bad SSE chunk: " + data, e);
                }
            }
        }
        for (StreamParser.Event e : parser.advance(acc.toString(), true)) dispatch(e, l);
    }

    private void dispatch(StreamParser.Event e, Listener l) {
        switch (e.kind) {
            case INK: l.onSentence(e.text); break;
            case SHOW: l.onShow(e.id); break;
            case TRANSCRIPT: l.onTranscript(e.text); break;
            case ERROR: l.onError(e.text); break;
        }
    }

    private static JSONObject msg(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Incremental parser over the streamed reply. Port of the Rust StreamParser. */
    static final class StreamParser {
        enum Kind { INK, SHOW, TRANSCRIPT, ERROR }

        static final class Event {
            final Kind kind; final String text; final long id;
            Event(Kind kind, String text, long id) { this.kind = kind; this.text = text; this.id = id; }
            static Event ink(String t) { return new Event(Kind.INK, t, 0); }
            static Event show(long id) { return new Event(Kind.SHOW, "", id); }
            static Event transcript(String t) { return new Event(Kind.TRANSCRIPT, t, 0); }
            static Event error(String t) { return new Event(Kind.ERROR, t, 0); }
        }

        private int delivered = 0;
        private int sentinel = -1;
        private boolean routeChecked = false;
        private boolean emittedAny = false;
        private final List<Long> catalogIds;

        StreamParser(List<Long> catalogIds) { this.catalogIds = catalogIds; }

        List<Event> advance(String full, boolean done) {
            List<Event> out = new ArrayList<>();
            if (sentinel < 0) {
                int p = full.indexOf(SENTINEL);
                sentinel = p >= 0 ? p : -1;
            }
            int effective = sentinel >= 0 ? sentinel : full.length();

            if (!routeChecked) {
                String lead = full.substring(Math.min(delivered, effective), effective).trim();
                if (lead.startsWith(SHOW_OPEN)) {
                    int close = lead.indexOf(SHOW_CLOSE);
                    if (close < 0) {
                        if (!done) return out; // directive still streaming in
                        out.add(Event.error("unfinished conjuring directive"));
                        return out;
                    }
                    String inner = lead.substring(SHOW_OPEN.length(), close);
                    routeChecked = true;
                    emittedAny = true;
                    delivered = effective;
                    Long id = parseShow(inner);
                    if (id != null && id - 1 < catalogIds.size() && id >= 1) {
                        out.add(Event.show(catalogIds.get((int) (id - 1))));
                    } else {
                        out.add(Event.error("the diary lost that page (" + inner + ")"));
                    }
                } else if (lead.isEmpty()) {
                    if (!done) return out;
                    routeChecked = true;
                } else {
                    routeChecked = true;
                }
            }

            if (delivered < effective) {
                int cut = sentenceCut(full.substring(0, effective), delivered);
                if (cut > 0) {
                    String chunk = stripDirectives(clean(full.substring(delivered, cut)));
                    if (!chunk.isEmpty()) { emittedAny = true; out.add(Event.ink(chunk)); }
                    delivered = cut;
                }
            }

            if (done) {
                if (delivered < effective) {
                    String rest = stripDirectives(clean(full.substring(delivered, effective)).trim());
                    if (!rest.isEmpty()) { emittedAny = true; out.add(Event.ink(rest)); }
                    delivered = effective;
                }
                if (sentinel >= 0 && sentinel + 1 < full.length()) {
                    String t = full.substring(sentinel + 1).trim();
                    if (!t.isEmpty()) out.add(Event.transcript(t));
                } else if (sentinel >= 0) {
                    String t = "";
                    if (!t.isEmpty()) out.add(Event.transcript(t));
                }
                if (!emittedAny) out.add(Event.error("empty reply"));
            }
            return out;
        }

        private Long parseShow(String inner) {
            String s = inner.trim();
            if (s.toLowerCase().startsWith("show")) s = s.substring(4).replaceFirst("^[:\\s]*", "").trim();
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
    }

    static String clean(String s) {
        String t = s.trim();
        if (t.startsWith("\"")) t = t.substring(1);
        if (t.endsWith("\"") && t.length() > 0) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Remove any ⟦…⟧ directive spans from inked prose; collapse whitespace. */
    static String stripDirectives(String s) {
        if (!s.contains(SHOW_OPEN)) return s;
        StringBuilder out = new StringBuilder();
        String rest = s;
        while (true) {
            int open = rest.indexOf(SHOW_OPEN);
            if (open < 0) { out.append(rest); break; }
            out.append(rest, 0, open);
            int close = rest.indexOf(SHOW_CLOSE, open);
            if (close < 0) break; // unterminated: drop the tail
            rest = rest.substring(close + SHOW_CLOSE.length());
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** End of the LAST complete sentence in text after char index from. */
    static int sentenceCut(String text, int from) {
        if (from >= text.length()) return -1;
        String tail = text.substring(from);
        int cut = -1;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                int end = i + 1;
                boolean ws = end >= tail.length() || Character.isWhitespace(tail.charAt(end));
                if (ws && end >= 4) cut = from + end;
            }
        }
        return cut;
    }
}
