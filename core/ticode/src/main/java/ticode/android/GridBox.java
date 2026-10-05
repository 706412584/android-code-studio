package ticode.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.*;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import java.util.*;

public class GridBox extends AdjustableMarginLayout {
public GridBox(android.content.Context context) {
super(context);
}

@Override
public android.widget.GridLayout onCreateView(android.content.Context context) {
android.widget.GridLayout view = new android.widget.GridLayout(context);
return view;
}

@Override
public android.widget.GridLayout getView() {
return (android.widget.GridLayout) view;
}

//设置宫格布局的列数，也就是每行显示的控件个数
public void 列数(int 列数) {
getView().setColumnCount(列数);
}

//获取宫格布局的列数
public int 列数() {
return getView().getColumnCount();
}

//设置宫格布局的行数，也就是每列显示的控件个数
public void 行数(int 行数) {
getView().setRowCount(行数);
}

//获取宫格布局的行数
public int 行数() {
return getView().getRowCount();
}

}