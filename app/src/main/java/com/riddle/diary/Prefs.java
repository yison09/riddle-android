package com.riddle.diary;

import android.content.Context;
import android.content.SharedPreferences;

/** Diary configuration, kept in SharedPreferences. */
final class Prefs {

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("riddle", Context.MODE_PRIVATE);
    }

    static String base(Context c) {
        return p(c).getString("base", "https://api.openai.com/v1");
    }

    static String key(Context c) {
        return p(c).getString("key", "");
    }

    static String model(Context c) {
        return p(c).getString("model", "gpt-4o-mini");
    }

    static String reasoning(Context c) {
        return p(c).getString("reasoning", "");
    }

    static int maxTokens(Context c) {
        return p(c).getInt("maxTokens", 2000);
    }

    static boolean remember(Context c) {
        return p(c).getBoolean("remember", true);
    }

    static void save(Context c, String base, String key, String model,
                     String reasoning, int maxTokens, boolean remember) {
        p(c).edit()
            .putString("base", base.isEmpty() ? "https://api.openai.com/v1" : base)
            .putString("key", key)
            .putString("model", model.isEmpty() ? "gpt-4o-mini" : model)
            .putString("reasoning", reasoning)
            .putInt("maxTokens", maxTokens)
            .putBoolean("remember", remember)
            .apply();
    }
}
