package android.eink.view;

public interface IAhwTouchListener {
    void onAhwAddPoint(int action, float x, float y, long time, float pressure);

    void onAhwAddTrack(float a, float b, float c, float d);

    void onAhwSetPen(int type, float width, int color, long tag);
}
