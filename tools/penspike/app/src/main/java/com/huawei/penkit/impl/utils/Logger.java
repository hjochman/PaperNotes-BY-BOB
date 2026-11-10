package com.huawei.penkit.impl.utils;

import android.util.Log;

/** Eigener Ersatz fuer die vom Host erwartete Logger-Klasse (Signaturen aus dem dex-Dump). */
public final class Logger {
    private Logger() {
    }

    public static void i(String tag, String msg) {
        Log.i("PenKit/" + tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e("PenKit/" + tag, msg);
    }
}
