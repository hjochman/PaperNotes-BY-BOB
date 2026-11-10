package com.huawei.stylus.penengine.feature.recg;

import android.content.Context;
import android.graphics.RectF;
import com.huawei.stylus.penengine.feature.IFeature;
import java.util.List;

/** Schnittstelle, die die Klasse HwRecognizeEngineImpl der Erkennungs-APK implementiert. */
public interface IRecgFeature extends IFeature {
    int addStrokePoints(float[] xs, float[] ys, int[] status);

    boolean checkEditorAvailable();

    boolean checkIntelligentAvailable();

    void clearContent();

    boolean deleteStrokePoints(int index);

    void destory();

    String doPageRecognize();

    String getContent();

    List<RectF> getPixelRects(List<RectF> rects);

    String getRecognizeMode();

    List<String> getSupportLanguages();

    boolean incRecDoSave(String path);

    String incRecLoadByPath(String path);

    void init(Context context, String language);

    String recognize(float[] xs, float[] ys, int[] status);

    int setEngineLanguage(String language);
}
