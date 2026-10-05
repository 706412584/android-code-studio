package ticode.zh.android;

import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.content.Context;
import android.view.View;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.GridView;
import android.view.*;

public class 适配器组件 extends 可视化组件 {
public 适配器组件(Context context) {
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




public void 项目被单击(可视化组件 被单击组件, int 索引) { } // 事件






public boolean 项目被长按(可视化组件 被长按组件, int 索引) { return false; } // 事件




public void 项目被选中(可视化组件 被单击组件, int 索引) { } // 事件
}