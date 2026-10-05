package ticode.zh.android;

import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class 简单适配器 extends 通用适配器 {
Integer 项目数;

public void 更新项目(int 项目总数) {
this.项目数 = 项目总数;
通知_更新数据();
}

public int 取项目数量() {
return 项目数;
}

public Object 取项目数据(int 索引) {
return null;
}

public long 取项目ID(int 索引) {
return (索引);
}

public 可视化组件 取项目布局(int 索引, 可视化组件 项目布局) {
组件容器 布局;
if (项目布局 == null) {
布局 = 加载布局(索引, null);
return 布局.getLayout();
} else {
布局 = (组件容器) 项目布局.getView().getTag(组件容器.ID);
return 加载布局(索引, 布局).getLayout();
}
}

public 组件容器 加载布局(int 索引, 组件容器 项目布局) { return null; } // 事件
}