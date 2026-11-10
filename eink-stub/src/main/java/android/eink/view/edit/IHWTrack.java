package android.eink.view.edit;

import android.graphics.RectF;
import java.util.List;

public interface IHWTrack {
    RectF getBounds();

    int getPenColor();

    int getPenType();

    float getPenWidth();

    List getPoints();
}
