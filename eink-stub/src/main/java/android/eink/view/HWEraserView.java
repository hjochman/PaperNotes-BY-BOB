package android.eink.view;

import android.content.Context;
import android.graphics.Path;

public class HWEraserView extends HWSurfaceView {
    public static int STATE_NONE = 0;
    public static int STATE_HADNWRITE = 1;
    public static int STATE_ERASE = 2;

    public static final int ERASER_STYLE_TRACK = 0;
    public static final int ERASER_STYLE_QUICK = 1;
    public static final int ERASER_STYLE_RANGE = 2;

    public class FBPicture {
        public void bindColor(int color) {
            throw new RuntimeException("Stub");
        }
    }

    public interface OnEraserPrepareListener {
        void onPrepareEraserFBPicture(HWEraserView view, FBPicture picture);
    }

    public interface OnEraseCompleteListener {
        void onEraseComlete(HWEraserView view, Path path);
    }

    public void setOnEraserPrepareListener(OnEraserPrepareListener listener) {
        throw new RuntimeException("Stub");
    }

    public OnEraseCompleteListener getOnEraseCompleteListener() {
        throw new RuntimeException("Stub");
    }

    public void setOnEraseCompleteListener(OnEraseCompleteListener listener) {
        throw new RuntimeException("Stub");
    }

    public void setEraserShowBorder(boolean show) {
        throw new RuntimeException("Stub");
    }

    public HWEraserView(Context context) {
        super(context);
        throw new RuntimeException("Stub");
    }

    public void setState(int state) {
        throw new RuntimeException("Stub");
    }

    public int getState() {
        throw new RuntimeException("Stub");
    }

    public void clear() {
        throw new RuntimeException("Stub");
    }

    public void setEraserRadius(int radius) {
        throw new RuntimeException("Stub");
    }

    public void setEraserStyle(int style) {
        throw new RuntimeException("Stub");
    }

    public void setEraseToolTypes(int[] toolTypes) {
        throw new RuntimeException("Stub");
    }
}
