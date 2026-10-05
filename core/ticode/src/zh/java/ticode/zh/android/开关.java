package ticode.zh.android;

import android.widget.Switch;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

import ticode.zh.base.文本;

public class 开关 extends 复合按钮 {
public 开关(android.content.Context context) {
super(context);
}

@Override
public Switch onCreateView(android.content.Context context) {
Switch view = new Switch(context);
return view;
}

@Override
public Switch getView() {
return (Switch) view;
}

//设置开关打开时的文本
public void 文本_打开(String 文本) {
getView().setTextOn(文本);
}

//获取开关打开时的文本
public String 文本_打开() {
return getView().getTextOn().toString();
}

//设置开关关闭时的文本
public void 文本_关闭(String 文本) {
getView().setTextOff(文本);
}

//获取开关关闭时的文本
public String 文本_关闭() {
return getView().getTextOff().toString();
}
}