package ticode.meng;

import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.*;
import androidx.recyclerview.widget.ItemTouchHelper;

import ticode.android.Adapter2;
import ticode.android.AndroidActivity;
import ticode.android.AndroidEnv;
import ticode.android.ComponentContainer;
import ticode.android.VisualComponent;
import ticode.jvm.JCollection;

public class AdvancedAdapter {

AdvancedListView 列表;

public int itemCount = -1;
public java.util.ArrayList dataList;

public AdvancedAdapter(){}
public AdvancedAdapter(java.util.ArrayList l){
this.dataList = l;
}

public void setRv(AdvancedListView l){
this.列表 = l;
}

public static AdvancedAdapter 创建适配器(Object 集合) {
if(!(集合 instanceof java.util.ArrayList)) throw new RuntimeException("AdvancedAdapter 创建失败，传入参数不是集合");
return new AdvancedAdapter((java.util.ArrayList)集合);
}

public Object 赋值_op(JCollection 集合) {
new AdvancedAdapter(集合);
}

//更新数量
public void 更新项目() {
if(this.dataList != null) this.itemCount = -1;
this.notifyDataSetChanged();
}

//不使用集合， 直接更新数量
public void 更新项目数量(int 数量) {
this.dataList = null;
this.itemCount = 数量;
this.notifyDataSetChanged();
}

public void 更新指定项目(int 索引, int 数量) {
if(数量==1) this.notifyItemChanged(索引);
else this.notifyItemRangeChanged(索引,数量);
}

public void 更新插入项目(int 索引, int 数量) {
if(this.itemCount != -1) this.itemCount += 数量;
if(数量==1) this.notifyItemInserted(索引);
else this.notifyItemRangeInserted(索引,数量);
}

public void 更新移动项目(int 移动索引, int 目标索引) {
this.notifyItemMoved(移动索引,目标索引);
}

public void 更新移除项目(int 索引, int 数量) {
if(this.itemCount != -1) this.itemCount -= 数量;
if(数量==1) this.notifyItemRemoved(索引);
else this.notifyItemRangeRemoved(索引,数量);
}

public void 固定项目ID(boolean 是否) {
this.setHasStableIds(是否);
}

public int 取项目类型(int 索引) {
return this.getItemViewType(索引);
}

public int 取项目数量() {
return this.getItemCount();
}

public int 取项目总数() {
return 取项目数量();
}

//使用集合时返回数据，可能为空
public JCollection 取数据() {
return dataList;
}

public void 置数据(Object 数据集合) {
if(数据集合 instanceof java.util.ArrayList) this.dataList = (java.util.ArrayList)数据集合;
else throw new RuntimeException("AdvancedAdapter 创建失败，传入参数不是集合");
更新项目();
}

public void 支持项目单击(boolean 是否) {
this.isClick = 是否;
}

public void 支持项目长按(boolean 是否) {
this.isLongClick = 是否;
}

public AdvancedListView 取列表() {
return 列表;
}

public LayoutManager2 取布局管理器() {
return 取列表().取布局管理器();
}

public AndroidEnv 取安卓环境() {
return 列表.取安卓环境();
}

public AndroidActivity 取安卓窗口() {
return 列表.取安卓窗口();
}

@Override
public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
ComponentContainer rq = 关联视图(viewType);
if(rq == null) throw new RuntimeException("关联视图错误，请检查适配器“关联视图”事件是否正确以及容器是否不为空");
return new VH(rq);
}

int pr;
boolean isClick,isLongClick;

@Override
public void onBindViewHolder(RecyclerView.ViewHolder holder,final int position){
int type = getItemViewType(pr = position);
VH vh = (VH)holder;
if(vh.p != position && vh.p != -1)
视图被复用(type, vh.rq, position, vh.p);
关联视图数据(type, vh.rq, position);
if(isClick) vh.rq.getLayout().getView().setOnClickListener(new android.view.View.OnClickListener() {
@Override
public void onClick(android.view.View view) {
项目被单击(vh.rq, position);
VisualComponent 组件 = null;
组件 = vh.rq.getLayout();
组件.被单击();
}
});
if(isLongClick) vh.rq.getLayout().getView().setOnLongClickListener(new android.view.View.OnLongClickListener() {
@Override
public boolean onLongClick(android.view.View view) {
项目被长按(vh.rq, position);
VisualComponent 组件 = null;
组件 = vh.rq.getLayout();
组件.被长按();
return true;
}
});
vh.p = position;
}

@Override
public void onViewRecycled(RecyclerView.ViewHolder holder) {
super.onViewRecycled(holder);
}

@Override
public long getItemId(int p){
return super.getItemId(p);
}

@Override
public int getItemCount() {
int c = 关联项目数量();
if(c > 0) return c;
if(dataList != null) return dataList.size();
if(itemCount != -1) return itemCount;
return 0;
}

@Override
public int getItemViewType(int position){
return 关联项目类型(position);
}

public class VH extends RecyclerView.ViewHolder{
public ComponentContainer rq;
public int p = -1;
public VH(ComponentContainer r) {
super(r.getLayout().getView());
rq = r;
if(rq.getLayout().getView().getLayoutParams() != null){
rq.getLayout().getView().setLayoutParams(
new RecyclerView.LayoutParams(
rq.getLayout().getView().getLayoutParams()));
} else rq.getLayout().getView().setLayoutParams(
new RecyclerView.LayoutParams(-2,-2));
}
}

//可选， 仅在需要 多个布局时使用
public int 关联项目类型(int 索引) { return 0; } // 事件

//必要事件
public ComponentContainer 关联视图(int 项目类型) { return null; } // 事件

public void 关联视图数据(int 项目类型, ComponentContainer 容器, int 索引) { } // 事件

//适配器获取项目数量时触发事件
public int 关联项目数量() { return 0; } // 事件

//可选，可实现对应逻辑
public void 视图被复用(int 项目类型, ComponentContainer 容器, int 索引, int 被复用视图索引) { } // 事件

public void 项目被单击(ComponentContainer 容器, int 索引) { } // 事件

public void 项目被长按(ComponentContainer 容器, int 索引) { } // 事件

//为每一个项目设置固定的ID，可优化性能
public int 项目ID(int 索引) { return 0; } // 事件

public static AdvancedAdapter AdvancedAdapter(JCollection 集合) {
AdvancedAdapter 适配器 = 集合;
return 适配器;
}

public static AdvancedAdapter 创建高级适配器(JCollection 集合) {
AdvancedAdapter 适配器 = 集合;
return 适配器;
}

}