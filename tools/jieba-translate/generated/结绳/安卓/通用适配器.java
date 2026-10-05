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

public class 通用适配器 extends 基础适配器 {
public int getCount() {
return 取项目数量();
}

public Object getItem(int position) {
return 取项目数据(position);
}

public long getItemId(int position) {
return 取项目ID(position);
}

public View getView(int position, View view, ViewGroup parent) {
if (view == null) {
return 取项目布局(position, null).getView();
} else {
可视化组件 v = (可视化组件) view.getTag();
return 取项目布局(position, v).getView();
}
}

public int 取项目数量() {
return (0);
}

public Object 取项目数据(int 索引) {
return (null);
}

public long 取项目ID(int 索引) {
return (索引);
}

public 可视化组件 取项目布局(int 索引, 可视化组件 项目布局) {
return (null);
}
}

