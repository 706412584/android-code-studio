package ticode.zh.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.*;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout.LayoutParams;
import android.widget.GridLayout;
import android.view.View;
import rn_1.*;
import java.util.*;
import android.view.View;

public class 未知组件 extends 布局组件 {
class UnknownView extends FrameLayout {
private Paint paint;
private String text;

public UnknownView(Context context) {
super(context);
initView();
text = "自定义组件";
}

private void initView() {
super.setWillNotDraw(false);
paint = new Paint();
paint.setColor(Color.BLACK);
paint.setAntiAlias(true);
paint.setStyle(Paint.Style.STROKE);
paint.setStrokeWidth(3);
paint.setTextSize(getContext().getResources().getDisplayMetrics().density * 12);
}

public void setText(String text) {
this.text = text;
invalidate();
}

@Override
protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
if (text != null) {
Path path = new Path();
path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
canvas.drawPath(path, paint);
canvas.drawText(text, 0, paint.getTextSize(), paint);
}
}
}

public 未知组件(Context context) {
super(context);
}

@Override
public UnknownView onCreateView(Context context) {
UnknownView view = new UnknownView(context);
return view;
}

@Override
public UnknownView getView() {
return (UnknownView) view;
}

public void 内容(String 内容) {
getView().setText(内容);
}
}