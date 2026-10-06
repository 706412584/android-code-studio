package ticode.zh.android;

import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class 按钮 extends 文本框 {
public 按钮(android.content.Context context) {
super(context);
}

@Override
public android.widget.Button onCreateView(android.content.Context context) {
android.widget.Button view = new android.widget.Button(context);
return view;
}

@Override
public android.widget.Button getView() {
return (android.widget.Button) view;
}
}