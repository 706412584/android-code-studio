package ticode.android;

import android.os.Environment;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class MenuItem2 implements android.view.MenuItem {
public static final int 总是显示 = 2;
public static final int 尽量显示 = 1;
public static final int 从不显示 = 0;
public static final int 带标题显示 = 4;




public int ID() {
return this.getItemId();
}




public int 组ID() {
return this.getGroupId();
}




public int 序号() {
return this.getOrder();
}




public String 标题() {
return this.getTitle().toString();
}




public void 标题(String 标题) {
this.setTitle(标题);
}




public void 图标资源(ImageResource 图标) {
this.setIcon(图标);
}





public void 标题栏显示方式(int 方式) {
this.setShowAsAction(方式);
}
}