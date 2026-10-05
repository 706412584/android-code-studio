package ticode.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class Menu2 implements android.view.Menu {




public android.view.MenuItem 添加菜单项(String 标题) {
return this.add(标题);
}








public android.view.MenuItem 添加菜单项2(int 组ID, int ID, int 序号, String 标题) {
return this.add(组ID, ID, 序号, 标题);
}








public void 添加子菜单(int 组ID, int ID, int 序号, String 标题) {
this.addSubMenu(组ID, ID, 序号, 标题);
}
}