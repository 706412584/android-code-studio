package ticode.zh.meng;

import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.flexbox.FlexboxLayoutManager;

import ticode.zh.android.安卓环境;
import ticode.zh.android.窗口组件;

public class 弹性布局管理器 extends 布局管理器 {

public 弹性布局管理器(安卓环境 context) {
super(context);
布局管理器 = new FlexboxLayoutManager(context, 1){
public RecyclerView.LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
if (lp instanceof RecyclerView.LayoutParams) {
return new FlexboxLayoutManager.LayoutParams((RecyclerView.LayoutParams) lp);
} else if (lp instanceof ViewGroup.MarginLayoutParams) {
return new FlexboxLayoutManager.LayoutParams((ViewGroup.MarginLayoutParams) lp);
} else {
return new FlexboxLayoutManager.LayoutParams(lp);
}
}
};
}

public FlexboxLayoutManager getLM(){
return (FlexboxLayoutManager)布局管理器;
}

public int 子视图数量() {
return getLM().getFlexItemCount();
}

public 弹性布局_主轴方向 主轴方向() {
return getLM().getFlexDirection();
}

public void 主轴方向(弹性布局_主轴方向 方向) {
getLM().setFlexDirection(方向);
}

public 弹性布局_换行策略 换行策略() {
return getLM().getFlexWrap();
}

public void 换行策略(弹性布局_换行策略 策略) {
getLM().setFlexWrap(策略);
}

public 弹性布局_主轴对齐方式 主轴对齐方式() {
return getLM().getJustifyContent();
}

public void 主轴对齐方式(弹性布局_主轴对齐方式 对齐) {
getLM().setJustifyContent(对齐);
}

public 弹性布局_侧轴对齐方式_多行 测轴对齐方式_多行() {
return getLM().getAlignContent();
}

public void 侧轴对齐方式_多行(弹性布局_侧轴对齐方式_多行 对齐) {
getLM().setAlignContent(对齐);
}

public 弹性布局_侧轴对齐方式_单行 测轴对齐方式_单行() {
return getLM().getAlignItems();
}

public void 侧轴对齐方式_单行(弹性布局_侧轴对齐方式_单行 对齐) {
getLM().setAlignItems(对齐);
}

public boolean 水平主轴() {
return getLM().isMainAxisDirectionHorizontal();
}

public int 起始内边距() {
return getLM().getPaddingStart();
}

public int 结束内边距() {
return getLM().getPaddingEnd();
}

public int 主轴最大尺寸() {
return getLM().getLargestMainSize();
}

public int 侧轴最大尺寸() {
return getLM().getSumOfCrossSize();
}

public int 最大行数() {
return getLM().getMaxLine();
}

public void 最大行数(int 行) {
getLM().setMaxLine(行);
}

public int 首可见项目索引() {
return getLM().findFirstVisibleItemPosition();
}

public int 首完全可见项目索引() {
return getLM().findFirstCompletelyVisibleItemPosition();
}

public int 尾可见项目索引() {
return getLM().findLastVisibleItemPosition();
}

public int 尾完全可见项目索引() {
return getLM().findLastCompletelyVisibleItemPosition();
}

}