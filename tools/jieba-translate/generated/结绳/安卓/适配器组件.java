package 结绳.安卓;

import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.content.Context;
import android.view.View;
import android.view.View;
import android.widget.AdapterView.*;
import android.widget.AdapterView;
import android.widget.ListView;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.ArrayAdapter;
import java.lang.reflect.Field;
import android.view.View;
import android.widget.AdapterView.*;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.ListPopupWindow;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.widget.GridView;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.*;

public class 适配器组件 extends 可视化组件 {
public 适配器组件(Context context) {
super(context);
}

public abstract AdapterView onCreateView(Context context);

public abstract AdapterView getView();




public 适配器 适配器() {
return getView().getAdapter();
}




public void 适配器(适配器 适配器对象) {
getView().setAdapter(适配器对象);
}




public void 项目被单击(可视化组件 被单击组件, int 索引) { return null; } // 事件






public boolean 项目被长按(可视化组件 被长按组件, int 索引) { return false; } // 事件




public void 项目被选中(可视化组件 被单击组件, int 索引) { return null; } // 事件
}





