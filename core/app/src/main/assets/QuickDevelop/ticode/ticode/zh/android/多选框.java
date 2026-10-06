package ticode.zh.android;

import android.widget.CheckBox;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class 多选框 extends 复合按钮 {
public 多选框(android.content.Context context) {
super(context);
}

@Override
public CheckBox onCreateView(android.content.Context context) {
CheckBox view = new CheckBox(context);
return view;
}

@Override
public CheckBox getView() {
return (CheckBox) view;
}
}