package ticode.android;

import android.view.View;
import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class UniversalAdapter extends BaseAdapter2 {
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
return 取项目布局(position, null).getView();
} else {
VisualComponent v = (VisualComponent) view.getTag();
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

public VisualComponent 取项目布局(int 索引, VisualComponent 项目布局) {
return (null);
}
}