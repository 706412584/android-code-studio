package ticode.zh.android;

import android.os.Environment;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 菜单 extends android.view.Menu {




public 菜单项 添加菜单项(String 标题) {
return this.add(标题);
}








public 菜单项 添加菜单项2(int 组ID, int ID, int 序号, String 标题) {
return this.add(组ID, ID, 序号, 标题);
}








public void 添加子菜单(int 组ID, int ID, int 序号, String 标题) {
this.addSubMenu(组ID, ID, 序号, 标题);
}
}