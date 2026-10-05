package ticode.meng;

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

import ticode.android.AndroidEnv;
import ticode.android.WindowComponent;

public class FlexLayoutManager extends LayoutManager2 {

public FlexLayoutManager(AndroidEnv context) {
super(context);
LayoutManager2 = new FlexboxLayoutManager(context, 1){
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
return (FlexboxLayoutManager)LayoutManager2;
}

public int 子视图数量() {
return getLM().getFlexItemCount();
}

public FlexDirection 主轴方向() {
return getLM().getFlexDirection();
}

public void 主轴方向(FlexDirection 方向) {
getLM().setFlexDirection(方向);
}

public FlexWrap 换行策略() {
return getLM().getFlexWrap();
}

public void 换行策略(FlexWrap 策略) {
getLM().setFlexWrap(策略);
}

public FlexJustifyContent 主轴对齐方式() {
return getLM().getJustifyContent();
}

public void 主轴对齐方式(FlexJustifyContent 对齐) {
getLM().setJustifyContent(对齐);
}

public FlexAlignContent 测轴对齐方式_多行() {
return getLM().getAlignContent();
}

public void 侧轴对齐方式_多行(FlexAlignContent 对齐) {
getLM().setAlignContent(对齐);
}

public FlexAlignItems 测轴对齐方式_单行() {
return getLM().getAlignItems();
}

public void 侧轴对齐方式_单行(FlexAlignItems 对齐) {
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