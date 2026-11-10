package android.eink.view;

import android.graphics.Path;

public interface IFBHandSurface extends ISurface {
    /** Begrenzt die native Schreibfläche auf [path] (View-Koordinaten); null hebt die Begrenzung auf. */
    void setClip(Path path);

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
