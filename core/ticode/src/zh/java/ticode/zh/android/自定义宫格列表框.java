package ticode.zh.android;

import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.content.Context;
import android.view.View;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.GridView;
import android.view.*;

public class 自定义宫格列表框 extends 宫格列表框 {
public 自定义宫格列表框(android.content.Context context) {
super(context);
初始化_内部();
}

简单适配器 适配器对象;

public 组件容器 加载布局(int 索引, 组件容器 项目布局) {
return 加载布局(索引, 项目布局);
}

public void 初始化_内部() {
this.适配器 = 适配器对象;
订阅事件 适配器对象;
}




public void 更新项目(int 项目总数) {
适配器对象.更新项目(项目总数);
}

public 组件容器 加载布局(int 索引, 组件容器 项目布局) { return null; } // 事件
}