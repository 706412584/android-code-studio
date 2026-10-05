package ticode.zh.android;

import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.widget.*;
import java.util.*;
import android.graphics.*;

public class 弹出式菜单 extends 窗口组件 {

private PopupMenu mPopupMenu;
public 弹出式菜单(安卓环境 context) {
super(context);
}




public void 依赖组件(可视化组件 组件) {
mPopupMenu = new PopupMenu(context, 组件.getView());
mPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
@Override
public boolean onMenuItemClick(MenuItem p1) {
菜单项被单击(p1);
return true;
}
});
}




public android.view.Menu 菜单() {
return mPopupMenu.getMenu();
}

//设置弹出式菜单显示时的对齐方式
public void 对齐方式(int 对齐方式) {
mPopupMenu.setGravity(对齐方式);
}

//显示弹窗菜单
public void 显示() {
mPopupMenu.show();
}

//关闭弹窗菜单
public void 关闭() {
mPopupMenu.dismiss();
}

public void 菜单项被单击(android.view.MenuItem 项目) { } // 事件

}