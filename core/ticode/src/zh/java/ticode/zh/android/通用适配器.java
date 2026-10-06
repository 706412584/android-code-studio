package ticode.zh.android;

import android.view.View;
import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class 通用适配器 extends 基础适配器 {
@Override
public int getCount() {
return 取项目数量();
}

@Override
public Object getItem(int position) {
return 取项目数据(position);
}

@Override
public long getItemId(int position) {
return 取项目ID(position);
}

@Override
public View getView(int position, View view, ViewGroup parent) {
if (view == null) {
可视化组件 新 = 取项目布局(position, null);
return 新 == null ? null : 新.getView();
} else {
// tag 可能为 null 或非 可视化组件（如复用他人 view），直接强转会崩。
Object tag = view.getTag();
可视化组件 v = tag instanceof 可视化组件 ? (可视化组件) tag : null;
可视化组件 新 = 取项目布局(position, v);
return 新 == null ? null : 新.getView();
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