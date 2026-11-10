package android.eink.view.edit;

import android.graphics.PointF;

public class HWPoint extends PointF {
    public long eventtime;
    public float pressure;

    public HWPoint(float x, float y, long time, float pressure) {
        throw new RuntimeException("Stub");
    }
}
