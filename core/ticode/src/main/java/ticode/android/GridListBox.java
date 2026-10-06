package ticode.android;

import android.widget.AdapterView;
import android.content.Context;
import android.view.View;
import android.widget.GridView;
import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class GridListBox extends AdapterComponent {
public GridListBox(Context context) {
super(context);
getView().setOnItemClickListener(new OnItemClickListener() {
@Override
public void onItemClick(AdapterView<?> p1, View p2, int p3, long p4) {
项目被单击((VisualComponent) p2.getTag(), p3);
}
});
getView().setOnItemLongClickListener(new  OnItemLongClickListener() {
@Override
public boolean onItemLongClick(AdapterView<?> p1, View p2, int p3, long p4) {
return 项目被长按((VisualComponent) p2.getTag(), p3);
}
});
}

@Override
public GridView onCreateView(Context context) {
GridView view = new GridView(context);
return view;
}

@Override
public GridView getView() {
return (GridView) view;
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

//设置宫格列表框每列的宽度
public void 列宽(int 每列宽度) {
getView().setColumnWidth(每列宽度);
}

//获取宫格列表框每列的宽度
public int 列宽() {
return getView().getColumnWidth();
}

//设置宫格列表框每行的个数
public void 列数(int 每行个数) {
getView().setNumColumns(每行个数);
}

//获取宫格列表框每行的个数
public int 列数() {
return getView().getNumColumns();
}

//使列表框滚动至某一确定位置，参数为欲滚动到的位置
public void 滚动至(int 位置) {
getView().smoothScrollToPosition(位置);
}
}