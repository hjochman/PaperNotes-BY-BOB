package local.penspike;

import android.app.Activity;
import android.eink.view.IAhwTouchListener;
import android.eink.view.edit.HWEditView;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Spike: Vergleich einer normalen SurfaceView (Stift-Events + lockCanvas) mit
 * android.eink.view.edit.HWEditView (natives Schreiben im Framework).
 * Eigener Code, nutzt nur die oeffentliche Klassenstruktur des Frameworks.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PenSpike";

    private FrameLayout container;
    private TextView status;
    private View current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button normal = new Button(this);
        normal.setText("A: normale SurfaceView");
        Button eink = new Button(this);
        eink.setText("B: HWEditView");
        Button clear = new Button(this);
        clear.setText("Leeren");
        bar.addView(normal);
        bar.addView(eink);
        bar.addView(clear);
        root.addView(bar);

        status = new TextView(this);
        status.setTextSize(14);
        root.addView(status);

        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        normal.setOnClickListener(v -> show(buildNormal()));
        eink.setOnClickListener(v -> show(buildEink()));
        clear.setOnClickListener(v -> {
            if (current instanceof PlainInkView) {
                ((PlainInkView) current).clearAll();
            } else if (current instanceof HWEditView) {
                ((HWEditView) current).clear();
            }
        });

        logEnvironment();
        show(buildNormal());
    }

    private void logEnvironment() {
        String[] names = {
                "android.eink.view.edit.HWEditView",
                "android.eink.view.HWSurfaceView",
                "android.eink.view.HandPen",
                "android.eink.view.FBHandSurface",
        };
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n);
                Log.i(TAG, "Klasse sichtbar: " + n + " loader=" + c.getClassLoader());
            } catch (Throwable t) {
                Log.e(TAG, "Klasse NICHT ladbar: " + n, t);
            }
        }
    }

    private void show(View v) {
        container.removeAllViews();
        current = v;
        if (v != null) {
            container.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private View buildNormal() {
        status.setText("A: normale SurfaceView, Stift-Events -> lockCanvas");
        return new PlainInkView(this, status);
    }

    private View buildEink() {
        try {
            final HWEditView v = new HWEditView(this);
            v.getFBSurface().setAutoHandWrite(true);
            v.getFBSurface().setAhwPenType(2);
            v.getFBSurface().setAhwPenWidth(4f);
            v.getFBSurface().setAhwPenColor(Color.BLACK);
            v.getFBSurface().setAhwToolTypes(new int[]{2});
            v.setState(1);
            v.getFBSurface().setAhwTouchListener(new IAhwTouchListener() {
                @Override
                public void onAhwAddPoint(int action, float x, float y, long time, float pressure) {
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                        Log.i(TAG, "HWEditView Punkt action=" + action + " x=" + x + " y=" + y);
                    }
                }

                @Override
                public void onAhwAddTrack(float a, float b, float c, float d) {
                }

                @Override
                public void onAhwSetPen(int type, float width, int color, long tag) {
                }
            });
            v.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    Log.i(TAG, "HWEditView surfaceCreated");
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                    Log.i(TAG, "HWEditView surfaceChanged " + w + "x" + h);
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    Log.i(TAG, "HWEditView surfaceDestroyed");
                }
            });
            status.setText("B: HWEditView erstellt (siehe logcat, Tag PenSpike)");
            Log.i(TAG, "HWEditView erstellt");
            return v;
        } catch (Throwable t) {
            Log.e(TAG, "HWEditView FEHLER", t);
            status.setText("B: FEHLER " + t);
            return null;
        }
    }

    /** Normale SurfaceView: zeichnet Stiftspuren per lockCanvas. */
    static final class PlainInkView extends SurfaceView implements SurfaceHolder.Callback {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final TextView status;
        private boolean ready;
        private long maxDelay;
        private long sumDelay;
        private long count;

        PlainInkView(MainActivity a, TextView status) {
            super(a);
            this.status = status;
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            getHolder().addCallback(this);
        }

        void clearAll() {
            path.reset();
            redraw();
        }

        private void redraw() {
            if (!ready) {
                return;
            }
            Canvas c = getHolder().lockCanvas();
            if (c == null) {
                return;
            }
            try {
                c.drawColor(Color.WHITE);
                c.drawPath(path, paint);
            } finally {
                getHolder().unlockCanvasAndPost(c);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
                return false;
            }
            long delay = SystemClock.uptimeMillis() - e.getEventTime();
            maxDelay = Math.max(maxDelay, delay);
            sumDelay += delay;
            count++;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    path.moveTo(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        path.lineTo(e.getHistoricalX(i), e.getHistoricalY(i));
                    }
                    path.lineTo(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    path.lineTo(e.getX(), e.getY());
                    status.setText("A: Events=" + count + " Zustellverzoegerung Mittel="
                            + (sumDelay / Math.max(1, count)) + " ms, max=" + maxDelay + " ms");
                    break;
                default:
                    break;
            }
            redraw();
            return true;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            ready = true;
            redraw();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            redraw();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            ready = false;
        }
    }
}
