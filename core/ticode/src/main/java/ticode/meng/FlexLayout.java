package ticode.meng;

import com.google.android.flexbox.*;
import android.view.View;
import android.view.ViewGroup;

import ticode.android.AdjustableMarginLayout;
import ticode.android.DrawableObject;
import ticode.android.VisualComponent;

public class FlexLayout extends AdjustableMarginLayout {

public FlexLayout(android.content.Context context) {
super(context);
getView().setFlexWrap(1);
}

public FlexboxLayout onCreateView(android.content.Context context) {
return new FlexboxLayout(context);
}

public FlexboxLayout getView() {
return (FlexboxLayout) view;
}


public int 子视图数量() {
return getView().getFlexItemCount();
}

public void 移除视图(int 索引) {
getView().removeViewAt(索引);
}

public void 移除全部视图() {
getView().removeAllViews();
}

public FlexDirection 主轴方向() {
return getView().getFlexDirection();
}

public void 主轴方向(FlexDirection 方向) {
getView().setFlexDirection(方向);
}

public FlexWrap 换行策略() {
return getView().getFlexWrap();
}

public void 换行策略(FlexWrap 策略) {
getView().setFlexWrap(策略);
}

public FlexJustifyContent 主轴对齐方式() {
return getView().getJustifyContent();
}

public void 主轴对齐方式(FlexJustifyContent 对齐) {
getView().setJustifyContent(对齐);
}

public FlexAlignContent 测轴对齐方式_多行() {
return getView().getAlignContent();
}

public void 侧轴对齐方式_多行(FlexAlignContent 对齐) {
getView().setAlignContent(对齐);
}

public FlexAlignItems 测轴对齐方式_单行() {
return getView().getAlignItems();
}

public void 侧轴对齐方式_单行(FlexAlignItems 对齐) {
getView().setAlignItems(对齐);
}

public boolean 水平主轴() {
return getView().isMainAxisDirectionHorizontal();
}

public int 起始内边距() {
return getView().getPaddingStart();
}

public int 结束内边距() {
return getView().getPaddingEnd();
}

public int 主轴最大尺寸() {
return getView().getLargestMainSize();
}

public int 侧轴最大尺寸() {
return getView().getSumOfCrossSize();
}

public int 最大行数() {
return getView().getMaxLine();
}

public void 最大行数(int 行) {
getView().setMaxLine(行);
}

public void 分割线(DrawableObject 图片) {
getView().setDividerDrawable(图片);
}

public DrawableObject 分割线_纵向() {
return getView().getDividerDrawableVertical();
}

public void 分割线_纵向(DrawableObject 图片) {
getView().setDividerDrawableVertical(图片);
}

public DrawableObject 分割线_横向() {
return getView().getDividerDrawableHorizontal();
}

public void 分割线_横向(DrawableObject 图片) {
getView().setDividerDrawableHorizontal(图片);
}

public void 分割线模式(FlexDividerMode 模式) {
getView().setShowDivider(模式);
}

public void 分割线模式_纵向(FlexDividerMode 模式) {
getView().setShowDividerVertical(模式);
}

public void 分割线模式_横向(FlexDividerMode 模式) {
getView().setShowDividerHorizontal(模式);
}

public void 排序值(VisualComponent 欲设置组件, int 值) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setOrder(值);
view.setLayoutParams(_params);
}
}

public void 弹性拓展值(VisualComponent 欲设置组件, int 值) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setFlexGrow(值);
view.setLayoutParams(_params);
}
}

public void 弹性收缩值(VisualComponent 欲设置组件, int 值) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setFlexShrink(值);
view.setLayoutParams(_params);
}
}

public void 对齐方式(VisualComponent 欲设置组件, int 方式) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setAlignSelf(方式);
view.setLayoutParams(_params);
}
}

public void 最小宽(VisualComponent 欲设置组件, int 宽) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setMinWidth(宽);
view.setLayoutParams(_params);
}
}

public void 最小高(VisualComponent 欲设置组件, int 高) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setMinHeight(高);
view.setLayoutParams(_params);
}
}

public void 最大宽(VisualComponent 欲设置组件, int 宽) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setMaxWidth(宽);
view.setLayoutParams(_params);
}
}

public void 最大高(VisualComponent 欲设置组件, int 高) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setMaxHeight(高);
view.setLayoutParams(_params);
}
}

public void 强制换行(VisualComponent 欲设置组件, boolean 是否) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setWrapBefore(是否);
view.setLayoutParams(_params);
}
}

public void 布局初始占比(VisualComponent 欲设置组件, double 占比) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof FlexboxLayout.LayoutParams) {
FlexboxLayout.LayoutParams _params = ((FlexboxLayout.LayoutParams) params);
_params.setFlexBasisPercent((float)占比);
view.setLayoutParams(_params);
}
}

}