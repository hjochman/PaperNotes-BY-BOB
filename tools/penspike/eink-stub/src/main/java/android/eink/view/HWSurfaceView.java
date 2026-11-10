package android.eink.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.SurfaceView;

public class HWSurfaceView extends SurfaceView {
    public HWSurfaceView(Context context) {
        super(context);
        throw new RuntimeException("Stub");
    }

    public IFBHandSurface getFBSurface() {
        throw new RuntimeException("Stub");
    }

    public void capture(Bitmap bitmap) {
        throw new RuntimeException("Stub");
    }
}
