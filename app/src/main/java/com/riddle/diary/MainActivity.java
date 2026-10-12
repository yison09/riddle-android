package com.riddle.diary;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The diary of Tom Riddle, for Android. Write on the page; after a pause the
 * diary drinks your ink and Tom replies in a flowing hand.
 *
 * 轻点右上角打开设置（填 OpenAI 兼容 API）。写下字后停笔约 3 秒，日记吸墨、
 * Tom 用花体英文回信；回信写完会淡去。双指点按切换橡皮擦。写下
 * "show me what I wrote about …" 可召回旧页。
 */
public class MainActivity extends Activity implements DiaryView.Host {

    private DiaryView diary;
    private MemoryStore memory;
    private Oracle oracle;
    private Typeface typeface;

    private long commitTs;
    private final StringBuilder fullReply = new StringBuilder();
    private String lastTranscript = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        diary = new DiaryView(this, null);
        setContentView(diary);
        diary.setHost(this);

        typeface = Typeface.createFromAsset(getAssets(), "DancingScript.ttf");
        diary.setTypeface(typeface);

        memory = new MemoryStore(this);
        oracle = new Oracle(config());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        View v = getWindow().getDecorView();
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------------
    // DiaryHost
    // ---------------------------------------------------------------------

    @Override
    public void onCommit(Bitmap page, long ts) {
        commitTs = ts;
        fullReply.setLength(0);
        lastTranscript = null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        page.compress(Bitmap.CompressFormat.PNG, 90, bos);

        Oracle.Turn turn = new Oracle.Turn();
        turn.png = bos.toByteArray();
        if (config().remember) {
            turn.catalogLines = memory.catalogLines();
            turn.catalogIds = memory.catalogIds();
            turn.history = memory.history(4);
        }

        oracle.ask(turn, new Oracle.Listener() {
            @Override public void onSentence(String text) {
                fullReply.append(text).append(' ');
                diary.enqueueSentence(text);
            }
            @Override public void onShow(long id) {
                MemoryStore.Page p = memory.byId(id);
                if (p == null) {
                    diary.enqueueSentence("The diary lost that page.");
                    return;
                }
                Bitmap old = memory.load(p);
                diary.conjure(old, formatDate(p.ts), p.reply);
            }
            @Override public void onTranscript(String text) { lastTranscript = text; }
            @Override public void onDone() {
                if (config().remember) {
                    String reply = fullReply.toString().trim();
                    String transcript = lastTranscript != null ? lastTranscript : "";
                    memory.add(commitTs, transcript, reply, page);
                }
                diary.replyFinished();
            }
            @Override public void onError(String message) {
                diary.enqueueSentence("The ink blurred — " + message);
            }
        });
    }

    @Override
    public void onSettingsTap() { showSettings(); }

    private String formatDate(long ts) {
        return new SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(new Date(ts));
    }

    // ---------------------------------------------------------------------
    // Settings (top-right corner tap)
    // ---------------------------------------------------------------------

    private Oracle.Config config() {
        Oracle.Config c = new Oracle.Config();
        c.base = Prefs.base(this);
        c.key = Prefs.key(this);
        c.model = Prefs.model(this);
        c.reasoning = Prefs.reasoning(this);
        c.maxTokens = Prefs.maxTokens(this);
        c.remember = Prefs.remember(this);
        return c;
    }

    private void showSettings() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * dp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("写下字后停笔约 3 秒：日记吸墨，Tom 用花体回信。\n"
            + "双指点按 = 橡皮擦。写下 \"show me …\" 召回旧页。");
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, pad / 2);
        box.addView(hint);

        EditText base = field(box, "API Base URL", Prefs.base(this), InputType.TYPE_CLASS_TEXT);
        EditText key = field(box, "API Key", Prefs.key(this),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = field(box, "Model（需支持图像）", Prefs.model(this), InputType.TYPE_CLASS_TEXT);
        EditText reasoning = field(box, "reasoning_effort（留空即可）", Prefs.reasoning(this),
            InputType.TYPE_CLASS_TEXT);
        EditText maxTokens = field(box, "Max tokens", String.valueOf(Prefs.maxTokens(this)),
            InputType.TYPE_CLASS_NUMBER);

        CheckBox remember = new CheckBox(this);
        remember.setText("记住每一页（日记有记忆）");
        remember.setChecked(Prefs.remember(this));
        box.addView(remember);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("The Diary — 设置")
            .setView(scroll)
            .setPositiveButton("保存", (d, w) -> {
                Prefs.save(this,
                    base.getText().toString().trim(),
                    key.getText().toString().trim(),
                    model.getText().toString().trim(),
                    reasoning.getText().toString().trim(),
                    parseInt(maxTokens.getText().toString(), 2000),
                    remember.isChecked());
                oracle = new Oracle(config());
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private EditText field(LinearLayout box, String label, String value, int inputType) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setGravity(Gravity.START);
        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setText(value);
        et.setSingleLine(true);
        row.addView(tv);
        row.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(lp);
        box.addView(row);
        return et;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
