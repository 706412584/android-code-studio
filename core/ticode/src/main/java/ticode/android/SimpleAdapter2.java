package ticode.android;

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

public class SimpleAdapter2 extends android.widget.BaseAdapter {
Integer 项目数;

public void 更新项目(int 项目总数) {
this.项目数 = 项目总数;
通知_更新数据();
}

public int 取项目数量() {
return 项目数;
}

public Object 取项目数据(int 索引) {
return null;
}

public long 取项目ID(int 索引) {
return (索引);
}

public VisualComponent 取项目布局(int 索引, VisualComponent 项目布局) {
ComponentContainer 布局;
if (项目布局 == null) {
布局 = 加载布局(索引, null);
return 布局.getLayout();
} else {
布局 = (ComponentContainer) 项目布局.getView().getTag(ComponentContainer.ID);
return 加载布局(索引, 布局).getLayout();
}
}

public ComponentContainer 加载布局(int 索引, ComponentContainer 项目布局) { return null; } // 事件
}