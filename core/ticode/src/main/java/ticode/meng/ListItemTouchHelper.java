package ticode.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;

import ticode.android.ComponentContainer;

public class ListItemTouchHelper extends ItemTouchHelper.Callback {

public AdvancedListView 列表;

ItemTouchHelper touchHelper;

int moveFlag;
int swipedFlag;
boolean longPressDrag = true;
boolean itemSwipe = true;

public ListItemTouchHelper(){
touchHelper = new ItemTouchHelper(this);
}

public int getMovementFlags(RecyclerView recyclerView, ViewHolder viewHolder){
return makeMovementFlags(moveFlag, swipedFlag);
}

public boolean isLongPressDragEnabled() {
return longPressDrag;
}

public boolean onMove(RecyclerView recyclerView, ViewHolder viewHolder, ViewHolder target){
return 项目被拖拽(((AdvancedAdapter.VH)viewHolder).rq, getAdapterPosition(viewHolder), ((AdvancedAdapter.VH)target).rq, getAdapterPosition(target));
}

public void onSwiped(ViewHolder viewHolder, int direction){
项目被滑动(((AdvancedAdapter.VH)viewHolder).rq, getAdapterPosition(viewHolder), direction);
}

public void onSelectedChanged(ViewHolder viewHolder, int actionState) {
super.onSelectedChanged(viewHolder, actionState);
if(viewHolder != null) 项目状态改变(((AdvancedAdapter.VH)viewHolder).rq, getAdapterPosition(viewHolder), actionState);
}

public void clearView(RecyclerView recyclerView, ViewHolder viewHolder) {
super.clearView(recyclerView, viewHolder);
项目操作结束(((AdvancedAdapter.VH)viewHolder).rq, getAdapterPosition(viewHolder));
}

public void onChildDraw(android.graphics.Canvas c, RecyclerView recyclerView, ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
项目操作中(((AdvancedAdapter.VH)viewHolder).rq, getAdapterPosition(viewHolder),dX,dY,actionState,isCurrentlyActive);
}

public void attachToRecyclerView(AdvancedListView rv){
touchHelper.attachToRecyclerView((列表 = rv).getView());
}

public int getAdapterPosition(ViewHolder viewHolder) {
return viewHolder != null ? viewHolder.getAbsoluteAdapterPosition() : -1;
}

public void 启用拖拽(int 拖拽方向) {
moveFlag = 拖拽方向;
}

public void 启用滑动(int 滑动方向) {
swipedFlag = 滑动方向;
}

//禁用后无法通过长按触发，自行处理触发逻辑调用 拖拽指定项目 方法
public void 禁用长按拖拽(boolean 是否) {
longPressDrag = !是否;
}

//禁用后无法触发滑动，自行处理触发逻辑调用 滑动指定项目 方法
public void 禁用项目滑动(boolean 是否) {
itemSwipe = !是否;
}

public void 拖拽指定项目(int 索引) {
touchHelper.startDrag(列表.getView().findViewHolderForAdapterPosition(索引));
}

public void 滑动指定项目(int 索引) {
touchHelper.startSwipe(列表.getView().findViewHolderForAdapterPosition(索引));
}

//取消正在进行的 拖动或滑动 操作
public void 取消项目操作() {
touchHelper.cancel();
}

//交换项目视图(适配器)
public void 交换项目(int 索引, int 目标索引) {
列表.取适配器().更新移动项目(索引, 目标索引);
}

public void 交换集合数据(Object 集合, int 索引, int 目标索引) {
java.util.Collections.swap((java.util.List)集合, 索引, 目标索引);
}

//移除项目视图
public void 移除项目(int 索引) {
列表.取适配器().更新移除项目(索引,1);
}

public void 移除集合数据(Object 集合, int 索引) {
((java.util.List)集合).remove(索引);
}

//拖拽中触发
public boolean 项目被拖拽(ComponentContainer 容器, int 索引, ComponentContainer 目标容器, int 目标索引) { return false; } // 事件
//滑动结束后触发
public void 项目被滑动(ComponentContainer 容器, int 索引, int 方向) { } // 事件
public void 项目状态改变(ComponentContainer 容器, int 索引, int 状态) { } // 事件
public void 项目操作中(ComponentContainer 容器, int 索引, double dX, double dY, int 状态, boolean 手动操作) { } // 事件
public void 项目操作结束(ComponentContainer 容器, int 索引) { } // 事件

}