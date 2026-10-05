package ticode.android;

import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.content.Context;
import android.view.View;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.GridView;
import android.view.*;

public class CustomGridListBox extends GridListBox {
public CustomGridListBox(android.content.Context context) {
super(context);
初始化_内部();
}

SimpleAdapter2 适配器对象;

public ComponentContainer 加载布局(int 索引, ComponentContainer 项目布局) {
return 加载布局(索引, 项目布局);
}

public void 初始化_内部() {
this.适配器 = 适配器对象;
订阅事件 适配器对象;
}




public void 更新项目(int 项目总数) {
适配器对象.更新项目(项目总数);
}

public ComponentContainer 加载布局(int 索引, ComponentContainer 项目布局) { return null; } // 事件
}