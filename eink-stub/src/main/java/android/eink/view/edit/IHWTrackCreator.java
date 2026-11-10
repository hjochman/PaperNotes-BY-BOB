package android.eink.view.edit;

import android.graphics.RectF;
import java.util.List;

public interface IHWTrackCreator {
    HWPoint createHWPoint(float x, float y, long time, float pressure);

    IHWTrack createHWTrack(int penType, float width, int color, long tag, List points, RectF bounds);
}
