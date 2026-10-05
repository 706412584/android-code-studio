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

public class 文本列表框 extends 列表框 {
java.util.List<String> 数据源集合;

private ArrayAdapter<String> adapter;

public 文本列表框(android.content.Context context) {
super(context);
adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, 数据源集合);
getView().setAdapter(adapter);
}

public boolean 是否存在(String 项目) {
return 数据源集合.是否存在(项目);
}

public void 添加项目(String 项目) {
数据源集合.添加成员(项目);
}

public void 插入项目(int 索引, String 项目) {
数据源集合.插入成员(索引, 项目);
}

public void 修改项目(int 索引, String 项目) {
数据源集合.置成员(索引, 项目);
}

public void 移除项目(int 索引) {
数据源集合.删除成员(索引);
}

public void 清空() {
数据源集合.清空();
}

public void 更新项目() {
adapter.notifyDataSetChanged();
}
}

