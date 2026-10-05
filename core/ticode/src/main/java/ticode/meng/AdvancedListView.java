package ticode.meng;

import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import ticode.android.Adapter2;
import ticode.android.AndroidActivity;
import ticode.android.AndroidEnv;
import ticode.android.ComponentContainer;
import ticode.android.VisualComponent;
import ticode.jvm.JCollection;

public class AdvancedListView extends VisualComponent {

AdvancedAdapter 适配器;
LayoutManager2 布局器;

public AdvancedListView(AndroidEnv context) {
super(context);
getView().addOnScrollListener(new ScrollListener());
getView().addOnItemTouchListener(new ItemClickListener(getView()){
public void onItemClick(ComponentContainer v, int p){项目被单击(v,p);};
public void onItemLongClick(ComponentContainer v, int p){项目被长按(v,p);};
});



}

@Override
public RecyclerView onCreateView(AndroidEnv context) {
RecyclerView view = new RecyclerView(context);
view.setLayoutManager((布局器 = new LinearLayoutManager2(context)).getLM());
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
protected void onItemClick(ComponentContainer view, int position){};
protected void onItemLongClick(ComponentContainer view, int position){};
private GestureDetector mGestureDetectorCompat;
public ItemClickListener(RecyclerView recyclerView){
mGestureDetectorCompat = new GestureDetector(recyclerView.getContext(), new GestureDetector.OnGestureListener() {
public boolean onDown(MotionEvent e) {return false;}
public void onShowPress(MotionEvent e) {}
public boolean onSingleTapUp(MotionEvent e) {
View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
if (childView != null) onItemClick(((AdvancedAdapter.VH)recyclerView.findContainingViewHolder(childView)).rq, recyclerView.getChildAdapterPosition(childView));
return false;
}
public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {return false;}
public void onLongPress(MotionEvent e) {
View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
if (childView != null) onItemLongClick(((AdvancedAdapter.VH)recyclerView.findContainingViewHolder(childView)).rq, recyclerView.getChildAdapterPosition(childView));
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

public void 置布局管理器(LayoutManager2 布局管理器) {
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

public void 置适配器(AdvancedAdapter 适配器) {
this.适配器 = 适配器;
Adapter2.setRv(this);
getView().setAdapter(适配器);
}

public void 替换适配器(AdvancedAdapter 适配器, boolean 保持状态) {
this.适配器 = 适配器;
Adapter2.setRv(this);
getView().swapAdapter(适配器, 保持状态);
}

public AdvancedAdapter 取适配器() {
return 适配器;
}

public LayoutManager2 取布局管理器() {
return 布局器;
}

public void 置分割线(Divider 分割线) {
getView().addItemDecoration(Divider.getIDN());
}

public void 添分割线(Divider 分割线, int 索引) {
getView().addItemDecoration(Divider.getIDN(), 索引);
}

public void 取分割线(int 索引) {
getView().getItemDecorationAt(索引);
}

public void 删分割线(int 索引) {
getView().removeItemDecorationAt(索引);
}

public void 置项目触摸辅助器(ListItemTouchHelper 辅助器) {
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
public ComponentContainer 取索引容器(int 索引) {
AdvancedAdapter.VH vh; return (vh = (AdvancedAdapter.VH)getView().findViewHolderForAdapterPosition(索引)) != null ? vh.rq : null;
}

//列表布局内的视图位置
public ComponentContainer 取位置容器(int 位置) {
AdvancedAdapter.VH vh; return (vh = (AdvancedAdapter.VH)getView().findViewHolderForLayoutPosition(位置)) != null ? vh.rq : null;
}

public ComponentContainer 取固定ID容器(long ID) {
AdvancedAdapter.VH vh; return (vh = (AdvancedAdapter.VH)getView().findViewHolderForItemId(ID)) != null ? vh.rq : null;
}

public ComponentContainer 取视图容器(VisualComponent 容器组件) {
AdvancedAdapter.VH vh; return (vh = (AdvancedAdapter.VH)getView().findContainingViewHolder(容器组件.getView())) != null ? vh.rq : null;
}

public int 取容器索引(ComponentContainer 容器) {
return getView().getChildAdapterPosition(容器.getLayout().getView());
}

public int 取视图索引(VisualComponent 容器组件) {
return getView().getChildAdapterPosition(getView().findContainingItemView(容器组件.getView()));
}

//列表布局内的视图位置
public int 取容器位置(ComponentContainer 容器) {
return getView().getChildLayoutPosition(容器.getLayout().getView());
}

//列表布局内的视图位置
public int 取视图位置(VisualComponent 容器组件) {
return getView().getChildLayoutPosition(getView().findContainingItemView(容器组件.getView()));
}

public long 取容器固定ID(ComponentContainer 容器) {
return getView().getChildItemId(容器.getLayout().getView());
}

public long 取视图固定ID(VisualComponent 容器组件) {
return getView().getChildItemId(getView().findContainingItemView(容器组件.getView()));
}

public ComponentContainer 取指定坐标视图容器(int X, int Y) {
AdvancedAdapter.VH vh; return (vh = (AdvancedAdapter.VH)getView().findContainingViewHolder(getView().findChildViewUnder(X, Y))) != null ? vh.rq : null;
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

public void 项目被单击(ComponentContainer 容器, int 索引) { } // 事件

public void 项目被长按(ComponentContainer 容器, int 索引) { } // 事件

// 1:被拖拽， 2:惯性滚动， 0:停止
public void 滚动状态(int 状态) { } // 事件

//手动滑动时触发
public void 滚动事件(int X, int Y) { } // 事件

}