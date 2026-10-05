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

public class 列表框 extends 适配器组件 {
public 列表框(Context context) {
super(context);
getView().setOnItemClickListener(new OnItemClickListener() {
public void onItemClick(AdapterView<?> p1, View p2, int p3, long p4) {
项目被单击((可视化组件) p2.getTag(), p3);
}
});
getView().setOnItemLongClickListener(new OnItemLongClickListener() {
public boolean onItemLongClick(AdapterView<?> p1, View p2, int p3, long p4) {
return 项目被长按((可视化组件) p2.getTag(), p3);
}
});
}

public ListView onCreateView(Context context) {
ListView view = new ListView(context);
return view;
}

public ListView getView() {
return (ListView) view;
}

//设置是否显示滑块条
public void 显示滑块条(boolean 是否显示) {
getView().setVerticalScrollBarEnabled(是否显示);
}

//设置列表框是否支持快速滚动，即显示右边的快速滚动条
public void 支持快速滚动(boolean 是否支持) {
getView().setFastScrollEnabled(是否支持);
}

//设置列表框是否始终显示快速滚动条
public void 始终显示快速滚动条(boolean 是否支持) {
getView().setFastScrollAlwaysVisible(是否支持);
}

//设置列表框分割线高度
public void 分割线高度(int 高度) {
getView().setDividerHeight(高度);
}

//获取列表框分割线高度
public int 分割线高度() {
return getView().getDividerHeight();
}

//设置列表框分割线颜色,参数为十六进制整数型颜色,格式：0xaarrggbb
public void 分割线颜色(int 颜色) {
getView().setDivider(new ColorDrawable(颜色));
}

//获取列表框分割线颜色，返回整数型颜色值
public int 分割线颜色() {
Drawable d = getView().getDivider();
if (d instanceof ColorDrawable) {
return ((ColorDrawable) d).getColor();
}
return 0;
}

//设置分割线背景资源
public void 分割线背景(图片资源 背景图片) {
getView().setDivider(context.getResources().getDrawable(背景图片));
}

//使列表框滚动至某一确定位置，参数为欲滚动到的位置
public void 滚动至(int 位置) {
getView().smoothScrollToPosition(位置);
}

//迅速移动到指定位置
public void 移动至(int 位置) {
getView().setSelection(位置);
}
}

