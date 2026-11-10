package local.penspike;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.eink.view.edit.HWEditView;
import android.eink.view.edit.HWPoint;
import android.eink.view.edit.IHWTrack;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Spike 3: Laesst sich die auf dem Geraet vorhandene Handschrifterkennung
 * (PenKitRecg-master.apk) aus einer Fremd-App laden und aufrufen?
 * Eigener Code. Die Aufrufreihenfolge folgt der Beschreibung im HiNote-Dekompilat
 * (nur als Anleitung gelesen): init(Context,String), recognize(float[],float[],int[]).
 */
public class Spike3Activity extends Activity {
    private static final String TAG = "PenSpike3";
    private static final String SRC = "/hw_product/app/HwStylusFeature/PenKitRecg-master.apk";
    private static final String ENGINE = "com.huawei.penkit.feature.recg.HwRecognizeEngineImpl";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private HWEditView ink;
    private Object engine;
    private Class<?> engineClass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        addButton(row, "Engine laden", this::loadEngine);
        addButton(row, "Erkennen", this::recognize);
        addButton(row, "Leeren", () -> ink.clear());
        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        FrameLayout stack = new FrameLayout(this);
        root.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        ink = new HWEditView(this);
        ink.getFBSurface().setAutoHandWrite(true);
        ink.getFBSurface().setAhwPenType(2);
        ink.getFBSurface().setAhwPenWidth(4f);
        ink.getFBSurface().setAhwPenColor(Color.BLACK);
        ink.getFBSurface().setAhwToolTypes(new int[]{2});
        ink.setState(HWEditView.STATE_HADNWRITE);
        stack.addView(ink, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (getIntent().getBooleanExtra("auto", false)) {
            ui.postDelayed(() -> {
                try {
                    loadEngine();
                } catch (Throwable t) {
                    Log.e(TAG, "Auto-Laden FEHLER", t);
                    status.setText("FEHLER " + t);
                }
            }, 1500);
        }
    }

    private interface Action {
        void run() throws Throwable;
    }

    private void addButton(LinearLayout row, String text, Action a) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            try {
                a.run();
            } catch (Throwable t) {
                Log.e(TAG, "Aktion '" + text + "' FEHLER", t);
                status.setText("'" + text + "': FEHLER " + t);
            }
        });
        row.addView(b);
    }

    private void loadEngine() throws Throwable {
        File local = new File(getCacheDir(), "PenKitRecg-master.apk");
        if (!local.exists()) {
            try (InputStream in = new FileInputStream(SRC);
                 OutputStream out = new FileOutputStream(local)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
        // Eine geladene APK darf nicht beschreibbar sein (Android 10 ignoriert das noch, trotzdem sauber).
        local.setReadOnly();
        Log.i(TAG, "APK kopiert: " + local.length() + " Bytes");

        String libDir = local.getAbsolutePath() + "!/lib/arm64-v8a";
        DexClassLoader cl = new DexClassLoader(local.getAbsolutePath(),
                getCodeCacheDir().getAbsolutePath(), libDir, getClassLoader());
        engineClass = cl.loadClass(ENGINE);
        Log.i(TAG, "Klasse geladen: " + engineClass);

        // Assets der Erkennungs-APK im eigenen AssetManager sichtbar machen (versteckte API).
        try {
            Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            Object r = add.invoke(getAssets(), local.getAbsolutePath());
            Log.i(TAG, "addAssetPath -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "addAssetPath nicht moeglich", t);
        }

        engine = engineClass.getConstructor().newInstance();
        Locale l = getResources().getConfiguration().getLocales().get(0);
        String lang = l.getLanguage() + "_" + l.getCountry();
        Method init = engineClass.getDeclaredMethod("init", Context.class, String.class);
        Object r = init.invoke(engine, this, lang);
        Log.i(TAG, "init(" + lang + ") -> " + r);
        status.setText("Engine geladen (" + lang + "). Jetzt schreiben und 'Erkennen' tippen.");
    }

    private void recognize() throws Throwable {
        if (engine == null) {
            status.setText("Engine nicht geladen");
            return;
        }
        List<?> list = ink.getTrackList();
        int total = 0;
        for (Object o : list) {
            IHWTrack t = (IHWTrack) o;
            if (t.getPenType() != 6 && t.getPoints().size() >= 2) {
                total += t.getPoints().size();
            }
        }
        float[] xs = new float[total];
        float[] ys = new float[total];
        int[] st = new int[total];
        int i = 0;
        for (Object o : list) {
            IHWTrack t = (IHWTrack) o;
            List<HWPoint> pts = t.getPoints();
            if (t.getPenType() == 6 || pts.size() < 2) {
                continue;
            }
            for (int k = 0; k < pts.size(); k++) {
                xs[i] = pts.get(k).x;
                ys[i] = pts.get(k).y;
                st[i] = k == 0 ? 0 : (k == pts.size() - 1 ? 1 : 2);
                i++;
            }
        }
        Log.i(TAG, "recognize: Tracks=" + list.size() + ", Punkte=" + total);
        Method m = engineClass.getDeclaredMethod("recognize", float[].class, float[].class, int[].class);
        long t0 = System.currentTimeMillis();
        Object res = m.invoke(engine, xs, ys, st);
        long dt = System.currentTimeMillis() - t0;
        // Das Ergebnis enthaelt erkannten Text, aber keine Zugangsdaten; Laenge begrenzen.
        String s = String.valueOf(res);
        Log.i(TAG, "recognize dauerte " + dt + " ms, Ergebnis: " + (s.length() > 600 ? s.substring(0, 600) : s));
        status.setText("Ergebnis (" + dt + " ms): " + s);
    }
}
