package ticode.zh.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;

import ticode.zh.android.可视化组件;
import ticode.zh.android.安卓环境;
import ticode.zh.android.组件容器;
import ticode.zh.android.适配器;

public class 高级列表框 extends 可视化组件 {

public 高级适配器 适配器;
public 布局管理器 布局器;

public 高级列表框(android.content.Context context) {
super(context);
getView().addOnScrollListener(new ScrollListener());
getView().addOnItemTouchListener(new ItemClickListener(getView()){
public void onItemClick(组件容器 v, int p){项目被单击(v,p);};
public void onItemLongClick(组件容器 v, int p){项目被长按(v,p);};
});



}

@Override
public RecyclerView onCreateView(android.content.Context context) {
RecyclerView view = new RecyclerView(context);
view.setLayoutManager((布局器 = new 线性布局管理器(context)).getLM());
return view;
}

public RecyclerView getView() {return (RecyclerView) view;}

public class ScrollListener extends RecyclerView.OnScrollListener {
@Override
public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
super.onScrollStateChanged(recyclerView, newState);
滚动状态(newState);
}
@Override
public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
super.onScrolled(recyclerView, dx, dy);
滚动事件(dx,dy);
}
}

public class ItemClickListener extends RecyclerView.SimpleOnItemTouchListener{
protected void onItemClick(组件容器 view, int position){};
protected void onItemLongClick(组件容器 view, int position){};
private GestureDetector mGestureDetectorCompat;
public ItemClickListener(RecyclerView recyclerView){
mGestureDetectorCompat = new GestureDetector(recyclerView.getContext(), new GestureDetector.OnGestureListener() {
public boolean onDown(MotionEvent e) {return false;}
public void onShowPress(MotionEvent e) {}
public boolean onSingleTapUp(MotionEvent e) {
View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
if (childView != null) onItemClick(((高级适配器.VH)recyclerView.findContainingViewHolder(childView)).rq, recyclerView.getChildAdapterPosition(childView));
return false;
}
public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {return false;}
public void onLongPress(MotionEvent e) {
View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
if (childView != null) onItemLongClick(((高级适配器.VH)recyclerView.findContainingViewHolder(childView)).rq, recyclerView.getChildAdapterPosition(childView));
}
public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {return false;}
});
}
public void onTouchEvent(RecyclerView rv, MotionEvent e) {
if (!mGestureDetectorCompat.onTouchEvent(e)) super.onTouchEvent(rv, e);
}
public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
if (!mGestureDetectorCompat.onTouchEvent(e)) return super.onInterceptTouchEvent(rv, e);
return false;
}
}

//动画速度，每个像素滚动的滚动时间
public void 滚动到(int 索引, double 动画速度, boolean 匀速, boolean 底部对齐) {
if(动画速度 == 0){
getView().scrollToPosition(索引);
} else {
LinearSmoothScroller s = new LinearSmoothScroller(取安卓环境()){
@Override
protected float calculateSpeedPerPixel(android.util.DisplayMetrics displayMetrics) {
return ((float)动画速度) / displayMetrics.densityDpi;
}
@Override
protected void onTargetFound(View targetView, RecyclerView.State state, Action action) {
final int dx = calculateDxToMakeVisible(targetView, getHorizontalSnapPreference());
final int dy = calculateDyToMakeVisible(targetView, getVerticalSnapPreference());
final int distance = (int) Math.sqrt(dx * dx + dy * dy);
final int time = calculateTimeForDeceleration(distance);
if (time > 0) {
if(匀速) action.update(-dx, -dy, time, mLinearInterpolator);
else action.update(-dx, -dy, time, mDecelerateInterpolator);
}
}
@Override
public int getVerticalSnapPreference() {
return 底部对齐 ? LinearSmoothScroller.SNAP_TO_END : LinearSmoothScroller.SNAP_TO_START;
}
@Override
protected int calculateTimeForDeceleration(int dx) {
return 匀速 ? (int) Math.ceil(calculateTimeForScrolling(dx)) : super.calculateTimeForDeceleration(dx);
}
};
s.setTargetPosition(索引);
getView().getLayoutManager().startSmoothScroll(s);
}
}

public void 转跳到(int X, int Y) {
getView().scrollBy(X,Y);
}

public void 转跳到_平滑(int X, int Y) {
getView().smoothScrollBy(X,Y);
}

public boolean 模拟滚动(int X, int Y) {
return getView().fling(X, Y);
}

public void 停止滚动() {
getView().stopScroll();
}

public int 最小惯性速度() {
return getView().getMinFlingVelocity();
}

public int 最大惯性速度() {
return getView().getMaxFlingVelocity();
}

public void 项目视图缓存数量(int 数量) {
getView().setItemViewCacheSize(数量);
}






public void 固定项目大小(boolean 是否固定) {
getView().setHasFixedSize(是否固定);
}

public void 冻结布局(boolean 是否) {
getView().setLayoutFrozen(是否);
}

//为真时，不再自动刷新绘制布局
public void 抑制布局(boolean 是否) {
getView().suppressLayout(是否);
}

//触摸滚动灵敏度
public void 触摸滚动阈值(int 距离) {
getView().setScrollingTouchSlop(距离);
}

public void 禁用内边距限制(boolean 是否) {
getView().setClipToPadding(!是否);
}

public void 置布局管理器(布局管理器 布局管理器) {
getView().setLayoutManager((布局器 = 布局管理器).getLM());
布局器.setRv(this);
}

SnapHelper snap;

public void 分页模式(boolean 是否, boolean 惯性) {
if(是否) (snap = 惯性 ? new LinearSnapHelper() : new PagerSnapHelper()).attachToRecyclerView(getView()); else if(snap!=null) snap.attachToRecyclerView(getView());
}

public int 取分页位置索引() {
return ((RecyclerView.LayoutParams)getView().getChildAt(0).getLayoutParams()).getViewAdapterPosition();
}

public void 置适配器(高级适配器 适配器) {
this.适配器 = 适配器;
适配器.setRv(this);
getView().setAdapter(适配器);
}

public void 替换适配器(高级适配器 适配器, boolean 保持状态) {
this.适配器 = 适配器;
适配器.setRv(this);
getView().swapAdapter(适配器, 保持状态);
}

public 高级适配器 取适配器() {
return 适配器;
}

public 布局管理器 取布局管理器() {
return 布局器;
}

// 结绳的 `分割线` 家族（分割线/线性分割线/宫格分割线/瀑布流分割线）依赖
// com.Meng.decoration.SpacesItemDecoration —— 那些 @外部Java文件 从未随库发布，
// 本机无源码，无法编译；dedup_set 据此整体丢弃（其「被引用 0 次」的判定是错的，
// 高级列表框 确实引用了 分割线）。
// 这里改用框架类型承接：用户可传 androidx 自带的 DividerItemDecoration 或自定义实现，
// 比原来的固定分割线类更灵活。若日后拿到 SpacesItemDecoration 源码，可从 dedup 放回。
public void 置分割线(androidx.recyclerview.widget.RecyclerView.ItemDecoration 分割线) {
getView().addItemDecoration(分割线);
}

public void 添分割线(androidx.recyclerview.widget.RecyclerView.ItemDecoration 分割线, int 索引) {
getView().addItemDecoration(分割线, 索引);
}

public void 取分割线(int 索引) {
getView().getItemDecorationAt(索引);
}

public void 删分割线(int 索引) {
getView().removeItemDecorationAt(索引);
}

public void 置项目触摸辅助器(高级列表项目触摸辅助器 辅助器) {
辅助器.attachToRecyclerView(this);
}

public void 滚动事件监听(boolean 是否支持) {
getView().addOnScrollListener(是否支持 ? new ScrollListener() : null);
}

public int 内容高度() {
return getView().computeVerticalScrollRange();
}

public int 内容宽度() {
return getView().computeHorizontalScrollRange();
}

public int 显示区域高度() {
return getView().computeVerticalScrollExtent();
}

public int 显示区域宽度() {
return getView().computeHorizontalScrollExtent();
}

public int 已滚动高度() {
return getView().computeVerticalScrollOffset();
}

public int 已滚动宽度() {
return getView().computeHorizontalScrollOffset();
}

public double 已滚动高度百分比() {
return (已滚动高度() * 1d/ (内容高度() - 显示区域高度()));
}

public double 已滚动宽度百分比() {
return (已滚动宽度() * 1d/ (内容宽度() - 显示区域宽度()));
}

public boolean 是否到顶() {
return !getView().canScrollVertically(-1);
}

public boolean 是否到底() {
return !getView().canScrollVertically(1);
}

public boolean 是否正在播放动画() {
return getView().isAnimating();
}

public boolean 是否正在计算布局() {
return getView().isComputingLayout();
}

public int 取滚动状态() {
return getView().getScrollState();
}

//取指定索引容器
public 组件容器 取索引容器(int 索引) {
高级适配器.VH vh; return (vh = (高级适配器.VH)getView().findViewHolderForAdapterPosition(索引)) != null ? vh.rq : null;
}

//列表布局内的视图位置
public 组件容器 取位置容器(int 位置) {
高级适配器.VH vh; return (vh = (高级适配器.VH)getView().findViewHolderForLayoutPosition(位置)) != null ? vh.rq : null;
}

public 组件容器 取固定ID容器(long ID) {
高级适配器.VH vh; return (vh = (高级适配器.VH)getView().findViewHolderForItemId(ID)) != null ? vh.rq : null;
}

public 组件容器 取视图容器(可视化组件 容器组件) {
高级适配器.VH vh; return (vh = (高级适配器.VH)getView().findContainingViewHolder(容器组件.getView())) != null ? vh.rq : null;
}

public int 取容器索引(组件容器 容器) {
return getView().getChildAdapterPosition(容器.getLayout().getView());
}

public int 取视图索引(可视化组件 容器组件) {
return getView().getChildAdapterPosition(getView().findContainingItemView(容器组件.getView()));
}

//列表布局内的视图位置
public int 取容器位置(组件容器 容器) {
return getView().getChildLayoutPosition(容器.getLayout().getView());
}

//列表布局内的视图位置
public int 取视图位置(可视化组件 容器组件) {
return getView().getChildLayoutPosition(getView().findContainingItemView(容器组件.getView()));
}

public long 取容器固定ID(组件容器 容器) {
return getView().getChildItemId(容器.getLayout().getView());
}

public long 取视图固定ID(可视化组件 容器组件) {
return getView().getChildItemId(getView().findContainingItemView(容器组件.getView()));
}

public 组件容器 取指定坐标视图容器(int X, int Y) {
高级适配器.VH vh; return (vh = (高级适配器.VH)getView().findContainingViewHolder(getView().findChildViewUnder(X, Y))) != null ? vh.rq : null;
}

public int 取指定坐标视图索引(int X, int Y) {
View v; return (v = getView().findChildViewUnder(X, Y)) != null ? getView().getChildAdapterPosition(v) : -1;
}

public void 纵向偏移项目(int 偏移量) {
getView().offsetChildrenVertical(偏移量);
}

public void 横向偏移项目(int 偏移量) {
getView().offsetChildrenHorizontal(偏移量);
}

public void 项目被单击(组件容器 容器, int 索引) { } // 事件

public void 项目被长按(组件容器 容器, int 索引) { } // 事件

// 1:被拖拽， 2:惯性滚动， 0:停止
public void 滚动状态(int 状态) { } // 事件

//手动滑动时触发
public void 滚动事件(int X, int Y) { } // 事件

}