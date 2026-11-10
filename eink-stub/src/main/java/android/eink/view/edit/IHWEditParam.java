package android.eink.view.edit;

public interface IHWEditParam {
    int TYPE_EDIT = 0;
    int TYPE_REDO = 2;
    int TYPE_UNDO = 1;

    int getType();
}
