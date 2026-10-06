package ticode.zh.android;

import android.view.ViewGroup;

public abstract class 自定义组件 extends 布局组件 {
public 自定义组件(android.content.Context context) {
super(context);
}

@Override
public android.view.ViewGroup onCreateView(android.content.Context context) {
return onCreateComponent(context).getView();
}

@Override
public ViewGroup getView() {
return (ViewGroup) view;
}

protected 布局组件 onCreateComponent(android.content.Context context) {
return new 空布局(context);
}
}