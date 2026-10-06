package ticode.zh.meng;

import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.flexbox.FlexboxLayoutManager;

import ticode.zh.android.安卓环境;

public class 弹性布局管理器 extends 布局管理器 {

public 弹性布局管理器(android.content.Context context) {
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

public int 主轴方向() {
return getLM().getFlexDirection();
}

public void 主轴方向(int 方向) {
getLM().setFlexDirection(方向);
}

public int 换行策略() {
return getLM().getFlexWrap();
}

public void 换行策略(int 策略) {
getLM().setFlexWrap(策略);
}

public int 主轴对齐方式() {
return getLM().getJustifyContent();
}

public void 主轴对齐方式(int 对齐) {
getLM().setJustifyContent(对齐);
}

public int 测轴对齐方式_多行() {
return getLM().getAlignContent();
}

public void 侧轴对齐方式_多行(int 对齐) {
getLM().setAlignContent(对齐);
}

public int 测轴对齐方式_单行() {
return getLM().getAlignItems();
}

public void 侧轴对齐方式_单行(int 对齐) {
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