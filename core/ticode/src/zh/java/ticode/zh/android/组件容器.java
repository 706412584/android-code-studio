package ticode.zh.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 组件容器 extends 窗口组件 {
public final static int ID = -101;
private 布局组件 root;

public 组件容器(android.content.Context context) {
super(context);
}

@Override
protected void onInit() {
this.root = onCreateComponent(context);
this.root.getView().setTag(ID, this);
}

protected 布局组件 onCreateComponent(android.content.Context context) {
return new 空布局(context);
}

public void addInLayout(布局组件 parent) {
parent.addComponent(this.root);
布局被加载();
}

public 布局组件 getLayout() {
return this.root;
}

public 布局组件 取根布局() {
return getLayout();
}

public 可视化组件 取用户布局() {
return root;
}

public void 布局被加载() {
}




public void 绑定数据(Object 数据) {
}
}