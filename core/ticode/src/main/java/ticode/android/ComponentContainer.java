package ticode.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class ComponentContainer extends WindowComponent {
public final static int ID = -101;
private LayoutComponent root;

public ComponentContainer(android.content.Context context) {
super(context);
}

@Override
protected void onInit() {
this.root = onCreateComponent(context);
this.root.getView().setTag(ID, this);
}

protected LayoutComponent onCreateComponent(android.content.Context context) {
return new EmptyLayout(context);
}

public void addInLayout(LayoutComponent parent) {
parent.addComponent(this.root);
布局被加载();
}

public LayoutComponent getLayout() {
return this.root;
}

public LayoutComponent 取根布局() {
return getLayout();
}

public VisualComponent 取用户布局() {
return root;
}

public void 布局被加载() {
}




public void 绑定数据(Object 数据) {
}
}