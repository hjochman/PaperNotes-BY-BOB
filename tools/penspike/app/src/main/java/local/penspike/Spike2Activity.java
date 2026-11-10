package local.penspike;

import android.app.Activity;
import android.eink.view.IAhwTouchListener;
import android.eink.view.edit.HWEditView;
import android.eink.view.edit.HWPoint;
import android.eink.view.edit.IHWEditParam;
import android.eink.view.edit.IHWTrack;
import android.eink.view.edit.IHWTrackCreator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Spike 2: Verhalten von HWEditView als Stiftschicht ueber einer fremden Flaeche.
 * Eigener Code. Geprueft werden: Transparenz ueber eigener View, getTrackList/setTrackList
 * (Speichern, Laden), Zoom durch Umrechnen der Tracks, Radierer, Lasso, Undo, Finger-Events
 * am Parent, Punkte aus dem Touch-Listener gegen Punkte der Track-Liste.
 */
public class Spike2Activity extends Activity {
    private static final String TAG = "PenSpike2";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private HWEditView ink;
    private GridView grid;
    private File saveFile;

    private int listenerPoints;
    private int fingerEvents;
    private int stylusEvents;
    private float zoom = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        saveFile = new File(getExternalFilesDir(null), "tracks.json");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        root.addView(row1);
        root.addView(row2);

        addButton(row1, "Schreiben", () -> ink.setState(HWEditView.STATE_HADNWRITE));
        addButton(row1, "Radierer", () -> ink.setState(HWEditView.STATE_ERASE));
        addButton(row1, "Lasso", () -> ink.setState(HWEditView.STATE_SELECT));
        addButton(row1, "Undo", () -> ink.undo());
        addButton(row1, "Redo", () -> ink.redo());
        addButton(row1, "Leeren", () -> ink.clear());
        addButton(row1, "Info", this::logTracks);

        addButton(row2, "Spirale", this::synthSpiral);
        addButton(row2, "Speichern", this::saveTracks);
        addButton(row2, "Laden", this::loadTracks);
        addButton(row2, "Zoom x2", () -> applyZoom(2f));
        addButton(row2, "Zoom /2", () -> applyZoom(0.5f));
        addButton(row2, "Mitte loeschen", this::eraseMiddle);
        addButton(row2, "Capture", this::captureToFile);

        LinearLayout row3 = new LinearLayout(this);
        root.addView(row3, 2);
        addButton(row3, "Rad. Stil 0 (Track)", () -> setEraser(0, true));
        addButton(row3, "Rad. Stil 1 (Quick)", () -> setEraser(1, true));
        addButton(row3, "Rad. Stil 2 (Range)", () -> setEraser(2, false));
        addButton(row3, "Rad. Stil 0 OHNE Hintergrund", () -> setEraser(0, false));
        addButton(row3, "Rand an/aus", () -> {
            eraserBorder = !eraserBorder;
            ink.setEraserShowBorder(eraserBorder);
            status.setText("Radierer-Rand: " + eraserBorder);
        });

        status = new TextView(this);
        status.setTextSize(13);
        root.addView(status);

        TouchCountLayout stack = new TouchCountLayout();
        grid = new GridView();
        stack.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        try {
            ink = new HWEditView(this);
            ink.getFBSurface().setAutoHandWrite(true);
            ink.getFBSurface().setAhwPenType(2);
            ink.getFBSurface().setAhwPenWidth(4f);
            ink.getFBSurface().setAhwPenColor(Color.BLACK);
            ink.getFBSurface().setAhwToolTypes(new int[]{2});
            ink.setState(HWEditView.STATE_HADNWRITE);
            ink.getFBSurface().setAhwTouchListener(new IAhwTouchListener() {
                @Override
                public void onAhwAddPoint(int action, float x, float y, long time, float pressure) {
                    listenerPoints++;
                    if (action == MotionEvent.ACTION_UP) {
                        final int n = listenerPoints;
                        listenerPoints = 0;
                        ui.postDelayed(() -> logAfterStroke(n), 300);
                    }
                }

                @Override
                public void onAhwAddTrack(float a, float b, float c, float d) {
                }

                @Override
                public void onAhwSetPen(int type, float width, int color, long tag) {
                }
            });
            ink.setOnEditListener((v, p) -> Log.i(TAG, "onEdit type=" + p.getType()
                    + " (0=edit 1=undo 2=redo) canUndo=" + v.canUndo() + " canRedo=" + v.canRedo()));
            ink.setOnSelectCompleteListener(new HWEditView.OnSelectCompleteListener() {
                @Override
                public void onSelectCancel(HWEditView view) {
                    Log.i(TAG, "Lasso: Auswahl abgebrochen");
                }

                @Override
                public void onSelectComlete(HWEditView view) {
                    Log.i(TAG, "Lasso: Auswahl fertig");
                }
            });
            stack.addView(ink, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Log.i(TAG, "HWEditView ueber GridView erstellt");
        } catch (Throwable t) {
            Log.e(TAG, "HWEditView FEHLER", t);
            status.setText("FEHLER " + t);
            return;
        }

        if (getIntent().getBooleanExtra("auto", false)) {
            ui.postDelayed(this::autoRun, 2500);
        }
    }

    private void addButton(LinearLayout row, String text, ThrowingRunnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, "Aktion '" + text + "' FEHLER", t);
                status.setText("Aktion '" + text + "': FEHLER " + t);
            }
        });
        row.addView(b);
    }

    // ---- Auswertung nach einem Strich ----

    private void logAfterStroke(int listenerCount) {
        List<?> list = ink.getTrackList();
        int tracks = list == null ? -1 : list.size();
        int lastPts = -1;
        if (tracks > 0) {
            lastPts = ((IHWTrack) list.get(tracks - 1)).getPoints().size();
        }
        String s = "Strich: Listener-Punkte=" + listenerCount + ", Tracks=" + tracks
                + ", Punkte im letzten Track=" + lastPts + ", receiving=" + ink.isReceivingTrack();
        Log.i(TAG, s);
        status.setText(s + " | Stift-Events am Parent=" + stylusEvents + ", Finger-Events=" + fingerEvents);
    }

    private void logTracks() {
        List<?> list = ink.getTrackList();
        int pts = 0;
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            IHWTrack t = (IHWTrack) o;
            pts += t.getPoints().size();
            sb.append(" [type=").append(t.getPenType()).append(" w=").append(t.getPenWidth())
                    .append(" n=").append(t.getPoints().size()).append("]");
        }
        String s = "Info: Tracks=" + list.size() + ", Punkte=" + pts + ", zoom=" + zoom
                + ", canUndo=" + ink.canUndo() + ", canRedo=" + ink.canRedo();
        Log.i(TAG, s + sb);
        status.setText(s);
    }

    // ---- Tracks erzeugen, serialisieren, zoomen ----

    private static RectF bounds(List<HWPoint> pts, float w) {
        RectF r = new RectF(pts.get(0).x, pts.get(0).y, pts.get(0).x, pts.get(0).y);
        for (HWPoint p : pts) {
            r.union(p.x, p.y);
        }
        r.inset(-w, -w);
        return r;
    }

    private IHWTrack makeTrack(int type, float w, int color, List<HWPoint> pts) {
        IHWTrackCreator c = ink.getHWTrackCreator();
        return c.createHWTrack(type, w, color, 0L, pts, bounds(pts, w));
    }

    private void synthSpiral() {
        IHWTrackCreator c = ink.getHWTrackCreator();
        List<IHWTrack> tracks = new ArrayList<>();
        float cx = ink.getWidth() / 2f;
        float cy = ink.getHeight() / 2f;
        long t0 = System.currentTimeMillis();
        for (int k = 0; k < 3; k++) {
            List<HWPoint> pts = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                double a = i * 0.12 + k * 2.1;
                double r = 20 + i * 1.6 + k * 40;
                pts.add(c.createHWPoint((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)),
                        t0 + i * 5L, 0.5f));
            }
            tracks.add(makeTrack(2, 4f, Color.BLACK, pts));
        }
        ink.setTrackList(tracks);
        Log.i(TAG, "setTrackList mit " + tracks.size() + " synthetischen Tracks, View "
                + ink.getWidth() + "x" + ink.getHeight());
        status.setText("Spirale gesetzt: " + tracks.size() + " Tracks");
    }

    private JSONArray toJson(List<?> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Object o : list) {
            IHWTrack t = (IHWTrack) o;
            JSONObject jt = new JSONObject();
            jt.put("type", t.getPenType());
            jt.put("w", t.getPenWidth());
            jt.put("color", t.getPenColor());
            JSONArray pa = new JSONArray();
            for (Object po : t.getPoints()) {
                HWPoint p = (HWPoint) po;
                JSONArray one = new JSONArray();
                one.put((double) p.x).put((double) p.y).put(p.eventtime).put((double) p.pressure);
                pa.put(one);
            }
            jt.put("p", pa);
            arr.put(jt);
        }
        return arr;
    }

    private List<IHWTrack> fromJson(JSONArray arr, float scale) throws Exception {
        IHWTrackCreator c = ink.getHWTrackCreator();
        List<IHWTrack> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject jt = arr.getJSONObject(i);
            JSONArray pa = jt.getJSONArray("p");
            List<HWPoint> pts = new ArrayList<>();
            for (int j = 0; j < pa.length(); j++) {
                JSONArray one = pa.getJSONArray(j);
                pts.add(c.createHWPoint((float) one.getDouble(0) * scale, (float) one.getDouble(1) * scale,
                        one.getLong(2), (float) one.getDouble(3)));
            }
            float w = (float) jt.getDouble("w") * scale;
            out.add(makeTrack(jt.getInt("type"), w, jt.getInt("color"), pts));
        }
        return out;
    }

    private void saveTracks() throws Exception {
        List<?> list = ink.getTrackList();
        try (FileWriter w = new FileWriter(saveFile)) {
            w.write(toJson(list).toString());
        }
        String s = "Gespeichert: " + list.size() + " Tracks -> " + saveFile.length() + " Bytes";
        Log.i(TAG, s + " " + saveFile);
        status.setText(s);
    }

    private void loadTracks() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(saveFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        List<IHWTrack> tracks = fromJson(new JSONArray(sb.toString()), 1f);
        ink.setTrackList(tracks);
        String s = "Geladen: " + tracks.size() + " Tracks";
        Log.i(TAG, s);
        status.setText(s);
    }

    /** Zoom: Punkte und Strichbreite umrechnen, Track-Liste neu setzen, Hintergrund mit skalieren. */
    private void applyZoom(float factor) throws Exception {
        List<?> list = ink.getTrackList();
        JSONArray arr = toJson(list);
        List<IHWTrack> scaled = fromJson(arr, factor);
        zoom *= factor;
        grid.setZoom(zoom);
        if (scaled.isEmpty()) {
            ink.clear();
        } else {
            ink.setTrackList(scaled);
        }
        String s = "Zoom auf " + zoom + ", " + scaled.size() + " Tracks neu gesetzt";
        Log.i(TAG, s);
        status.setText(s);
    }

    private boolean eraserBorder = true;

    /** Radierstil setzen. Bei Stil 1 liefert der Prepare-Listener Weiss als Hintergrund. */
    private void setEraser(int style, boolean whiteBackground) {
        ink.setEraserStyle(style);
        ink.setEraserRadius(20);
        ink.setOnEraserPrepareListener((v, pic) -> {
            if (whiteBackground) {
                pic.bindColor(Color.WHITE);
            }
        });
        ink.setState(HWEditView.STATE_ERASE);
        Log.i(TAG, "Radierer Stil " + style + " radius 20 weissHintergrund=" + whiteBackground);
        status.setText("Radierer Stil " + style + " aktiv (Radius 20)");
    }

    private void eraseMiddle() {
        Path p = new Path();
        float cx = ink.getWidth() / 2f;
        float cy = ink.getHeight() / 2f;
        p.addRect(cx - 150, cy - 150, cx + 150, cy + 150, Path.Direction.CW);
        ink.erase(p);
        Log.i(TAG, "erase(Path) Rechteck um die Mitte aufgerufen");
        status.setText("erase(Path) aufgerufen");
    }

    private void captureToFile() {
        int w = ink.getWidth();
        int h = ink.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ink.capture(bmp);
        int nonTransparent = 0;
        int dark = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                int px = bmp.getPixel(x, y);
                if (Color.alpha(px) != 0) {
                    nonTransparent++;
                }
                if (Color.alpha(px) != 0 && Color.red(px) < 128) {
                    dark++;
                }
            }
        }
        File out = new File(getExternalFilesDir(null), "capture_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fo = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Exception e) {
            Log.e(TAG, "Capture schreiben", e);
        }
        String s = "Capture " + w + "x" + h + ": nicht transparente Stichproben=" + nonTransparent
                + ", dunkle=" + dark + " -> " + out.getName();
        Log.i(TAG, s);
        status.setText(s);
    }

    // ---- Automatischer Ablauf (Intent-Extra "auto") ----

    private void autoRun() {
        try {
            Log.i(TAG, "AUTO Start");
            synthSpiral();
            long t = 1500;
            ui.postDelayed(() -> step(() -> { logTracks(); captureToFile(); }), t);
            // Radierer vor allem anderen, damit der Verlauf noch intakt ist
            ui.postDelayed(() -> step(this::eraseMiddle), t += 1500);
            ui.postDelayed(() -> step(() -> { logTracks(); captureToFile(); }), t += 1500);
            ui.postDelayed(() -> step(() -> { Log.i(TAG, "vor undo canUndo=" + ink.canUndo()); ink.undo(); }), t += 1500);
            ui.postDelayed(() -> step(() -> { logTracks(); captureToFile(); }), t += 1500);
            ui.postDelayed(() -> step(() -> { Log.i(TAG, "vor redo canRedo=" + ink.canRedo()); ink.redo(); }), t += 1500);
            ui.postDelayed(() -> step(() -> { logTracks(); captureToFile(); }), t += 1500);
            ui.postDelayed(() -> step(this::saveTracks), t += 1500);
            ui.postDelayed(() -> step(() -> { ink.clear(); logTracks(); }), t += 1000);
            ui.postDelayed(() -> step(this::loadTracks), t += 1500);
            ui.postDelayed(() -> step(() -> { logTracks(); captureToFile(); }), t += 1500);
            ui.postDelayed(() -> Log.i(TAG, "AUTO Ende"), t += 1000);
        } catch (Throwable th) {
            Log.e(TAG, "AUTO FEHLER", th);
        }
    }

    private void step(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(TAG, "AUTO Schritt FEHLER", t);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- Hilfsviews ----

    /** Fremde Zeichenflaeche unter HWEditView: Gitter plus Beschriftung. */
    private final class GridView extends View {
        private final Paint line = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float z = 1f;

        GridView() {
            super(Spike2Activity.this);
            line.setColor(Color.rgb(170, 170, 170));
            line.setStrokeWidth(1f);
            text.setColor(Color.rgb(200, 0, 0));
            text.setTextSize(36f);
        }

        void setZoom(float zoomFactor) {
            z = zoomFactor;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float step = 100f * z;
            for (float x = 0; x < getWidth(); x += step) {
                c.drawLine(x, 0, x, getHeight(), line);
            }
            for (float y = 0; y < getHeight(); y += step) {
                c.drawLine(0, y, getWidth(), y, line);
            }
            c.drawText("Hintergrund-View, Zoom " + z, 40, 60, text);
        }
    }

    /** Zaehlt Touch-Events am Parent nach Werkzeugtyp. */
    private final class TouchCountLayout extends FrameLayout {
        TouchCountLayout() {
            super(Spike2Activity.this);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                stylusEvents++;
            }
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                fingerEvents++;
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    Log.i(TAG, "Finger-Events am Parent angekommen: " + fingerEvents);
                    status.setText("Finger-Events am Parent: " + fingerEvents);
                }
                return true;
            }
            return false;
        }
    }
}
