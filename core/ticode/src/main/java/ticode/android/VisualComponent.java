package ticode.android;

import android.content.Context;
import android.util.TypedValue;
import android.animation.Animator;
import android.view.animation.Animation;
import android.view.View;
import android.view.ViewGroup;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class VisualComponent extends WindowComponent {
public static final String 横坐标设置错误 = "横坐标属性只能在组件父布局为自适应布局时使用";
public static final String 纵坐标设置错误 = "纵坐标属性只能在组件父布局为自适应布局时使用";
public static final String 权重设置错误 = "权重属性只能在组件父布局为线性布局时设置";

protected View view;
private GestureDetector detector;

public VisualComponent(Context context) {
super(context);
}

public VisualComponent(View view) {
super(view.getContext(), false);
this.view = view;
创建完毕();
}

@Override
protected void onInit() {
this.view = onCreateView(context);
this.view.setTag(this);
}

public View onCreateView(Context context) {
View view = new View(context);
return view;
}

public View getView() {
return view;
}

protected int computeDimension(Object value) {
if (value instanceof Number) {
return ((Number) value).intValue();
} else if (value instanceof String) {
String text = (String) value;
if(text.trim().isEmpty()){
return 0;
}
int index = text.lastIndexOf("dp");
if (index != -1) {
int dp = Integer.parseInt(text.substring(0, index).trim());
return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
} else {
index = text.lastIndexOf("sp");
if (index != -1) {
int sp = Integer.parseInt(text.substring(0, index).trim());
return (int) (sp * context.getResources().getDisplayMetrics().scaledDensity + 0.5f);
} else {
index = text.lastIndexOf("px");
if (index != -1) {
return Integer.parseInt(text.substring(0, index).trim());
} else {
return Integer.parseInt(text);
}
}
}
}
return 0;
}

protected float computePercentage(Object value) {
if (value instanceof Number) {
return ((Number) value).floatValue();
} else if (value instanceof String) {
String text = (String) value;
if (text.charAt(0) == '%') {
return (float) (Double.parseDouble(text.substring(1)) / 100);
} else if (text.charAt(text.length() - 1) == '%') {
return (float) (Double.parseDouble(text.substring(0, text.length() - 1)) / 100);
} else {
int index = text.indexOf('/');
if (index == -1) {
return Integer.parseInt(text) * 1f / 100;
} else {
int denominator = Integer.parseInt(text.substring(0, index));
int numerator = Integer.parseInt(text.substring(index + 1));
return denominator * 1f / numerator;
}
}
}
return 0;
}

public void ID(int ID) {
view.setId(ID);
}

public int ID() {
return view.getId();
}





public void 宽度(Object 宽度) {
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params == null) {
params = new ViewGroup.LayoutParams(-2, -2);
}
params.width = computeDimension(宽度);
view.setLayoutParams(params);
}





public void 宽度DP(int 宽度) {
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params == null) {
params = new ViewGroup.LayoutParams(-2, -2);
}
params.width = PixelOps.DP到PX(宽度);
view.setLayoutParams(params);
}


public int 宽度() {
return view.getWidth();
}





public void 高度(Object 高度) {
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params == null) {
params = new ViewGroup.LayoutParams(-2, -2);
}
params.height = computeDimension(高度);
view.setLayoutParams(params);
}





public void 高度DP(int 高度) {
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params == null) {
params = new ViewGroup.LayoutParams(-2, -2);
}
params.height = PixelOps.DP到PX(高度);
view.setLayoutParams(params);
}


public int 高度() {
return view.getHeight();
}




public void 横坐标(Object 横坐标) {
view.setX(computeDimension(横坐标));
}




public double 横坐标() {
return view.getX();
}




public void 纵坐标(Object 纵坐标) {
view.setY(computeDimension(纵坐标));
}




public double 纵坐标() {
return view.getY();
}

public void 竖坐标(Object 竖坐标) {
view.setZ(computeDimension(竖坐标));
}

public double 竖坐标() {
return view.getZ();
}

public void 横向偏移(Object 偏移) {
view.setTranslationX(computeDimension(偏移));
}

public void 横向偏移DP(int 偏移) {
view.setTranslationX(PixelOps.DP到PX(偏移));
}

public int 横向偏移() {
return (int)view.getTranslationX();
}

public void 纵向偏移(Object 偏移) {
view.setTranslationY(computeDimension(偏移));
}

public void 纵向偏移DP(int 偏移) {
view.setTranslationY(PixelOps.DP到PX(偏移));
}

public int 纵向偏移() {
return (int)view.getTranslationY();
}

public void 竖向偏移(int 偏移) {
view.setTranslationZ(computeDimension(偏移));
}

public int 竖向偏移() {
return (int)view.getTranslationZ();
}

public void 旋转角(double 旋转角) {
view.setRotation((float) 旋转角);
}

public double 旋转角() {
return view.getRotation();
}

public void X轴旋转角(double 旋转角) {
view.setRotationX((float) 旋转角);
}

public double X轴旋转角() {
return view.getRotationX();
}

public void Y轴旋转角(double 旋转角) {
view.setRotationY((float) 旋转角);
}

public double Y轴旋转角() {
return view.getRotationY();
}

public void 内边距(Object 边距) {
int padding = computeDimension(边距);
view.setPadding(padding, padding, padding, padding);
}

public void 内边距DP(int 边距) {
int padding = PixelOps.DP到PX(边距);
view.setPadding(padding, padding, padding, padding);
}

public void 左内边距(Object 左内边距) {
view.setPadding(computeDimension(左内边距), view.getPaddingTop(),view.getPaddingRight(),view.getPaddingBottom());
}

public void 左内边距DP(int 左内边距) {
view.setPadding(PixelOps.DP到PX(左内边距), view.getPaddingTop(),view.getPaddingRight(),view.getPaddingBottom());
}

public int 左内边距() {
return view.getPaddingLeft();
}

public void 上内边距(Object 上内边距) {
view.setPadding(view.getPaddingLeft(), computeDimension(上内边距), view.getPaddingRight(),view.getPaddingBottom());
}

public void 上内边距DP(int 上内边距) {
view.setPadding(view.getPaddingLeft(), PixelOps.DP到PX(上内边距), view.getPaddingRight(),view.getPaddingBottom());
}

public int 上内边距() {
return view.getPaddingTop();
}

public void 右内边距(Object 右内边距) {
view.setPadding(view.getPaddingLeft(),view.getPaddingTop(), computeDimension(右内边距), view.getPaddingBottom());
}

public void 右内边距DP(int 右内边距) {
view.setPadding(view.getPaddingLeft(),view.getPaddingTop(), PixelOps.DP到PX(右内边距), view.getPaddingBottom());
}

public int 右内边距() {
return view.getPaddingRight();
}

public void 下内边距(Object 下内边距) {
view.setPadding(view.getPaddingLeft(),view.getPaddingTop(),view.getPaddingRight(), computeDimension(下内边距));
}

public void 下内边距DP(int 下内边距) {
view.setPadding(view.getPaddingLeft(),view.getPaddingTop(),view.getPaddingRight(), PixelOps.DP到PX(下内边距));
}

public int 下内边距() {
return view.getPaddingBottom();
}

public void 阴影(Object 阴影度) {
view.setElevation(computeDimension(阴影度));
}

public int 阴影() {
return (int)view.getElevation();
}

public void 透明度(double 透明度) {
view.setAlpha((float)透明度);
}

public double 透明度() {
return (float)view.getAlpha();
}

public void 可用(boolean 是否可用) {
view.setEnabled(是否可用);
}

public boolean 可用() {
return view.isEnabled();
}

public void 可视(boolean 是否可视) {
if (是否可视) {
view.setVisibility(View.VISIBLE);
} else {
view.setVisibility(View.GONE);
}
}

public boolean 可视() {
return view.getVisibility() == View.VISIBLE ? true : false;
}

public void 可视状态(int 状态) {
view.setVisibility(状态);
}

public int 可视状态() {
return view.getVisibility();
}

//设置组件是否填充以占据整个系统界面，如状态栏
public void 填充系统界面(boolean 是否填充) {
view.setFitsSystemWindows(是否填充);
}

//获取组件是否填充以占据整个系统界面，如状态栏
public boolean 填充系统界面() {
return view.getFitsSystemWindows();
}

//判断是否启用硬件加速
public boolean 硬件加速() {
return view.isHardwareAccelerated();
}

//设置是否启用硬件加速
public void 硬件加速(boolean 是否启用) {
view.setLayerType(是否启用 ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE, null);
}

public void 可获取焦点(boolean 是否可获取焦点) {
view.setFocusable(是否可获取焦点);
}

public boolean 可获取焦点() {
return view.isFocusable();
}




public void 背景图片(int 图片) {
view.setBackgroundResource(图片);
}




public void 背景颜色(int 颜色) {
view.setBackgroundColor(颜色);
}

//设置水波纹效果
public void 水波纹效果(boolean 是否开启水波纹效果) {
if (DeviceInfo.安卓版本号 < 23) {
return;
}
if (是否开启水波纹效果 == true) {
android.content.res.Resources.Theme theme = context.getTheme();
TypedValue typedValue = new TypedValue();
theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
int[] attribute = new int[]{android.R.attr.selectableItemBackground};
TypedArray typedArray = theme.obtainStyledAttributes(typedValue.resourceId, attribute);
view.setForeground(typedArray.getDrawable(0));
} else {
view.setForeground(null);
}
}




public LayoutComponent 取父组件() {
ViewGroup parent = (ViewGroup) view.getParent();
if (parent == null) {
return null;
}
return (LayoutComponent) parent.getTag();
}




public void 从父组件中移除() {
LayoutComponent 父组件 = 取父组件();
if (父组件 != null) {
父组件.移除组件(this);
}
}




public PropertyAnimator 取动画播放器() {
ViewPropertyAnimator animator = view.animate();
animator.setListener(new android.animation.Animator.AnimatorListener() {
@Override
public void onAnimationStart(Animator p1) {
动画开始播放();
}
@Override
public void onAnimationEnd(Animator p1) {
动画播放完毕();
}
@Override
public void onAnimationCancel(Animator p1) {
// TODO: Implement this method
}
@Override
public void onAnimationRepeat(Animator p1) {
动画重复播放();
}
});
return animator;
}

public void 播放动画(ComponentAnimation 欲播放动画) {
view.startAnimation(欲播放动画);
欲播放动画.setAnimationListener(new android.view.animation.Animation.AnimationListener(){
@Override
public void onAnimationStart(Animation p1) {
动画开始播放();
}
@Override
public void onAnimationEnd(Animation p1) {
动画播放完毕();
}
@Override
public void onAnimationRepeat(Animation p1) {
动画重复播放();
}
});
}

public void 刷新() {
view.invalidate();
}

public void 子线程刷新() {
view.postInvalidate();
}

public void 模拟单击() {
view.performClick();
}

public void 模拟长按() {
view.performLongClick();
}

public void 请求焦点() {
view.requestFocus();
}

public void 取消焦点() {
view.clearFocus();
}




public void 支持单击(boolean 是否支持) {
view.setClickable(是否支持);
if (是否支持) {
view.setOnClickListener(new View.OnClickListener() {
@Override
public void onClick(View view) {
被单击();
}
});
} else {
view.setOnClickListener(null);
}
}




public boolean 支持单击() {
return view.isClickable();
}




public void 支持长按(boolean 是否支持) {
view.setLongClickable(是否支持);
if (是否支持) {
view.setOnLongClickListener(new View.OnLongClickListener() {
@Override
public boolean onLongClick(View view) {
被长按();
return true;
}
});
} else {
view.setOnLongClickListener(null);
}
}




public boolean 支持长按() {
return view.isLongClickable();
}




public void 支持触摸(boolean 是否支持) {
if (是否支持) {
if (detector == null) {
GestureDetector.SimpleOnGestureListener listener = new GestureDetector.SimpleOnGestureListener() {
@Override
public boolean onSingleTapConfirmed(MotionEvent e) {
TouchGesture(TouchGesture.单击);
return true;
}
@Override
public boolean onDoubleTap(MotionEvent e) {
TouchGesture(TouchGesture.双击);
return true;
}
@Override
public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
int direction;
if (Math.abs(distanceX) > Math.abs(distanceY)) {
direction = distanceX > 0 ? TouchGesture.左移 : TouchGesture.右移;
} else {
direction = distanceY > 0 ? TouchGesture.上移 : TouchGesture.下移;
}
TouchGesture(direction);
return true;
}
@Override
public boolean onFling(MotionEvent e1, MotionEvent e2, float p3, float p4) {
if(e1 == null || e2 == null) return false;
int deltaX = (int) (e1.getRawX() - e2.getRawX());
int deltaY = (int) (e1.getRawY() - e2.getRawY());
int direction;
if (Math.abs(deltaX) > Math.abs(deltaY)) {
direction = deltaX > 0 ? TouchGesture.左滑 : TouchGesture.右滑;
} else {
direction = deltaY > 0 ? TouchGesture.上滑 : TouchGesture.下滑;
}
TouchGesture(direction);
return true;
}
};
detector = new GestureDetector(listener);
}
view.setOnTouchListener(new View.OnTouchListener() {
@Override
public boolean onTouch(View view, android.view.MotionEvent event) {
detector.onTouchEvent(event);
return 被触摸(event);
}
});
} else {
view.setOnTouchListener(null);
}
}




public void 支持拖放(boolean 是否支持) {
if (是否支持) {
view.setOnDragListener(new View.OnDragListener() {
@Override
public boolean onDrag(View view, android.view.DragEvent event) {
return 被拖放(event);
}
});
} else {
view.setOnDragListener(null);
}
}




public void 支持焦点改变监听(boolean 是否支持) {
if (是否支持) {
view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
@Override
public void onFocusChange(View view, boolean hasFocus) {
焦点被改变(hasFocus);
}
});
} else {
view.setOnFocusChangeListener(null);
}
}




public void 支持按键监听(boolean 是否支持) {
if (是否支持) {
view.setOnKeyListener(new View.OnKeyListener() {
@Override
public boolean onKey(View view, int keyCode, android.view.KeyEvent event) {
return 按键输入(keyCode, event);
}
});
} else {
view.setOnKeyListener(null);
}
}

//用户在组件上单击事件
public void 被单击() { } // 事件

//组件被长按时事件
public void 被长按() { } // 事件






public boolean 被触摸(TouchEvent2 来源事件) { return false; } // 事件






public void 触摸手势(int 手势) { } // 事件






public boolean 被拖放(DragEvent2 来源事件) { return false; } // 事件

//组件焦点改变时事件
public void 焦点被改变(boolean 是否获得焦点) { } // 事件






public boolean 按键输入(int 键代码, KeyEvent2 来源事件) { return false; } // 事件

//组件动画开始播放时触发该事件
public void 动画开始播放() { } // 事件

//动画播放完毕时触发该事件
public void 动画播放完毕() { } // 事件

//动画重复播放时触发该事件
public void 动画重复播放() { } // 事件
}