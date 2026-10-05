package ticode.zh.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.*;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import java.util.*;

public class 空布局 extends 布局组件 {
public 空布局(android.content.Context context) {
super(context);
}

@Override
public FrameLayout onCreateView(android.content.Context context) {
FrameLayout view = new FrameLayout(context);
return view;
}

@Override
public FrameLayout getView() {
return (FrameLayout) view;
}
}