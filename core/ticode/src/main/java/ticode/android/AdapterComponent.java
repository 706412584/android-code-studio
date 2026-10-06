package ticode.android;

import android.widget.AdapterView;
import android.content.Context;
import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public abstract class AdapterComponent extends VisualComponent {
public AdapterComponent(Context context) {
super(context);
}

@Override
public abstract AdapterView onCreateView(Context context);

@Override
public abstract AdapterView getView();




public android.widget.Adapter 适配器() {
return getView().getAdapter();
}




public void 适配器(android.widget.Adapter 适配器对象) {
getView().setAdapter(适配器对象);
}




public void 项目被单击(VisualComponent 被单击组件, int 索引) { } // 事件






public boolean 项目被长按(VisualComponent 被长按组件, int 索引) { return false; } // 事件




public void 项目被选中(VisualComponent 被单击组件, int 索引) { } // 事件
}