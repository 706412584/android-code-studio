package ticode.zh.android;

import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class 自定义宫格列表框 extends 宫格列表框 {
public 自定义宫格列表框(android.content.Context context) {
super(context);
初始化_内部();
}

public 简单适配器 适配器对象;

// 加载布局 是给用户覆写的事件：基类 简单适配器 有默认实现（返回 null）。
// 原实现 return 加载布局(索引, 项目布局) 是**调用自身**，会无限递归 StackOverflow。
public 组件容器 加载布局(int 索引, 组件容器 项目布局) {
return 适配器对象 == null ? null : 适配器对象.加载布局(索引, 项目布局);
}

public void 初始化_内部() {
this.适配器(适配器对象);
}




public void 更新项目(int 项目总数) {
适配器对象.更新项目(项目总数);
}

}