package android.eink.view.edit;

import android.content.Context;
import android.eink.view.HWEraserView;
import android.graphics.Path;
import java.util.List;

public class HWEditView extends HWEraserView {
    public static int STATE_SELECT = 3;

    public interface OnEditListener {
        void onEdit(HWEditView view, IHWEditParam param);
    }

    public interface OnSelectCompleteListener {
        void onSelectCancel(HWEditView view);

        void onSelectComlete(HWEditView view);
    }

    public HWEditView(Context context) {
        super(context);
        throw new RuntimeException("Stub");
    }

    public List getTrackList() {
        throw new RuntimeException("Stub");
    }

    public void setTrackList(List list) {
        throw new RuntimeException("Stub");
    }

    public IHWTrackCreator getHWTrackCreator() {
        throw new RuntimeException("Stub");
    }

    public boolean canUndo() {
        throw new RuntimeException("Stub");
    }

    public boolean canRedo() {
        throw new RuntimeException("Stub");
    }

    public void undo() {
        throw new RuntimeException("Stub");
    }

    public void redo() {
        throw new RuntimeException("Stub");
    }

    public void erase(Path path) {
        throw new RuntimeException("Stub");
    }

    public boolean isReceivingTrack() {
        throw new RuntimeException("Stub");
    }

    public void setOnEditListener(OnEditListener listener) {
        throw new RuntimeException("Stub");
    }

    public void setOnSelectCompleteListener(OnSelectCompleteListener listener) {
        throw new RuntimeException("Stub");
    }

    public void setSelectToolTypes(int[] toolTypes) {
        throw new RuntimeException("Stub");
    }
}
