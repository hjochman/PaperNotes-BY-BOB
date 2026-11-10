package android.eink.view;

public interface IFBHandSurface extends ISurface {
    boolean isAutoHandWrite();

    void setAutoHandWrite(boolean enabled);

    int getAhwPenType();

    void setAhwPenType(int type);

    float getAhwPenWidth();

    void setAhwPenWidth(float width);

    int getAhwPenColor();

    void setAhwPenColor(int color);

    void setAhwToolTypes(int[] toolTypes);

    void setAhwTouchListener(IAhwTouchListener listener);
}
