package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class ScaleHandler extends WindowComponent {

private ScaleGestureDetector scaleGestureDetector;
public ScaleHandler(Context context){
super(context, false);
scaleGestureDetector = new ScaleGestureDetector(context,new ScaleGestureDetector.OnScaleGestureListener(){
@Override
public boolean onScale(ScaleGestureDetector detector) {
return 进行缩放(ScaleHandler.this);
}
@Override
public boolean onScaleBegin(ScaleGestureDetector detector) {
return 缩放开始(ScaleHandler.this);
}
@Override
public void onScaleEnd(ScaleGestureDetector detector) {
缩放结束(ScaleHandler.this);
}
});
}
private ScaleGestureDetector getScale(){
return scaleGestureDetector;
}

// 处理触摸事件(重要的,必须要传递触摸事件才能进行处理)
public boolean 处理触摸事件(TouchEvent2 触摸事件) {
return getScale().onTouchEvent(触摸事件);
}

// 获取当前的缩放比例
public float 取缩放百分比() {
return getScale().getScaleFactor();
}

// 获取两个触摸点之间的距离
public float 取触摸点距离() {
return getScale().getCurrentSpan();
}

// 获取两个触摸点之间的水平距离
public float 取触摸点X距离() {
return getScale().getCurrentSpanX();
}

// 获取两个触摸点之间的垂直距离
public float 取触摸点Y距离() {
return getScale().getCurrentSpanY();
}

// 获取当前触摸事件的时间戳
public long 取事件时间() {
return getScale().getEventTime();
}

// 获取缩放手势的焦点X坐标
public float 取焦点X坐标() {
return getScale().getFocusX();
}

// 获取缩放手势的焦点Y坐标
public float 取焦点Y坐标() {
return getScale().getFocusY();
}

// 获取上一次触摸事件中两个触摸点之间的距离
public float 取上次触摸点距离() {
return getScale().getPreviousSpan();
}

// 获取上一次触摸事件中两个触摸点之间的水平距离
public float 取上次触摸点X距离() {
return getScale().getPreviousSpanX();
}

// 获取上一次触摸事件中两个触摸点之间的垂直距离
public float 取上次触摸点Y距离() {
return getScale().getPreviousSpanY();
}

// 获取当前触摸事件与上一次触摸事件之间的时间差
public long 取时间差() {
return getScale().getTimeDelta();
}

// 判断是否正在进行缩放操作
public boolean 是否在缩放() {
return getScale().isInProgress();
}

// 获取是否启用快速缩放功能
public boolean 快速缩放() {
return getScale().isQuickScaleEnabled();
}

// 获取是否启用手写笔缩放功能
public boolean 手写笔缩放() {
return getScale().isStylusScaleEnabled();
}

// 设置是否启用快速缩放功能
public void 快速缩放(boolean 启用) {
getScale().setQuickScaleEnabled(启用);
}

// 设置是否启用手写笔缩放功能
public void 手写笔缩放(boolean 启用) {
getScale().setStylusScaleEnabled(启用);
}






public boolean 进行缩放(ScaleHandler 检测器) { return false; } // 事件





public boolean 缩放开始(ScaleHandler 检测器) { return false; } // 事件



public void 缩放结束(ScaleHandler 检测器) { } // 事件
}