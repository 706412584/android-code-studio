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

public class 下拉列表框 extends 适配器组件 {
private int dropDownHeight;

public 下拉列表框(Context context) {
super(context);
getView().setOnItemSelectedListener(new OnItemSelectedListener() {
public void onItemSelected(AdapterView<?> p1, View p2, int p3, long p4) {
项目被选中((可视化组件) p2.getTag(), p3);
}
public void onNothingSelected(AdapterView<?> p1) {
// TODO: Implement this method
}
});
}

public Spinner onCreateView(Context context) {
Spinner view = new Spinner(context);
return view;
}

public Spinner getView() {
return (Spinner) view;
}

public int 下拉宽度() {
return getView().getDropDownWidth();
}

public void 下拉宽度(int 宽度) {
getView().setDropDownWidth(宽度);
}

public int 下拉高度() {
return dropDownHeight;
}

public void 下拉高度(int 高度) {
try {
Field mPopupField = Spinner.class.getDeclaredField("mPopup");
mPopupField.setAccessible(true);
ListPopupWindow mPopup = (ListPopupWindow) mPopupField.get(getView());
mPopup.setHeight(高度);
} catch (Exception e) {
}
}

//设置是否显示滑块条
public void 显示滑块条(boolean 是否显示) {
getView().setVerticalScrollBarEnabled(是否显示);
}

}





