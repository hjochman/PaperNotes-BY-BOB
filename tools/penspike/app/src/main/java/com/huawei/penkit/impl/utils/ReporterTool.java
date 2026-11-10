package com.huawei.penkit.impl.utils;

import android.content.Context;
import android.util.Log;

/** Eigener Ersatz: meldet nichts, schreibt nur ins Log. */
public final class ReporterTool {
    private ReporterTool() {
    }

    public static void reportMonitorInfo(Context context, int a, int b, int c) {
        Log.i("PenKit/Report", "monitor " + a + "," + b + "," + c);
    }
}
