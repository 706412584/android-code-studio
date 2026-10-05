package 结绳.安卓;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.*;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout.LayoutParams;
import android.widget.GridLayout;
import android.view.View;
import rn_1.*;
import java.util.*;
import android.view.View;

public class 分页布局 extends 布局组件 {
private ArrayPageAdapter adapter;
private List<OnPageChangeListener> listeners = new ArrayList<>();

public interface OnPageChangeListener {
void onPageChange(View view, int position);
void onPageScrolled(int position, float positionOffset, int positionOffsetPixels);
void onPageSelected(int position);
void onPageScrollStateChanged(int state);
}

public void addOnPageChangeListener(OnPageChangeListener listener) {
if (listener != null) {
listeners.add(listener);
}
}

public 分页布局(android.content.Context context) {
super(context);
adapter = new ArrayPageAdapter();
getView().setAdapter(adapter);
getView().setOnPageChangeListener(new PageView.OnPageChangeListener(){
public void onPageChange(View view, int position) {
for (OnPageChangeListener listener : listeners) {
listener.onPageChange(view,position);
}
页面被改变(position);
}

public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
for (OnPageChangeListener listener : listeners) {
listener.onPageScrolled(position,positionOffset,positionOffsetPixels);
}
页面被滑动(position,positionOffset,positionOffsetPixels);
}

public void onPageSelected(int position) {
for (OnPageChangeListener listener : listeners) {
listener.onPageSelected(position);
}
页面被选中(position);
}

public void onPageScrollStateChanged(int state) {
for (OnPageChangeListener listener : listeners) {
listener.onPageScrollStateChanged(state);
}
滑动状态改变(state);
}

});
}

public PageView onCreateView(android.content.Context context) {
PageView view = new PageView(context);
return view;
}

public PageView getView() {
return (PageView) view;
}

public void addComponent(可视化组件 component) {
adapter.add(component.getView());
adapter.notifyDataSetChanged();
}

//获取页面总数
public int 页面总数() {
return (adapter.getCount());
}

//设置分页布局页面布局
public void 页面边距(int 页面边距) {
getView().setPageMargin(页面边距);
}

public void 缓存数量(int 数量) {
getView().setOffscreenPageLimit(数量);
}

public void 可否滑动(boolean 可否) {
getView().setTouchEnabled(可否);
}

//向分页布局添加组件容器
public void 添加页面(组件容器 欲添加页面) {
可视化组件 component = 欲添加页面.取根布局();
adapter.add(component.getView());
欲添加页面.布局被加载();
adapter.notifyDataSetChanged();
}

//向分页布局指定索引处插入组件容器
public void 插入页面(int 欲插入索引, 组件容器 欲插入页面) {
可视化组件 component = 欲插入页面.取根布局();
adapter.insert(欲插入索引, component.getView());
欲插入页面.布局被加载();
adapter.notifyDataSetChanged();
}

//删除分页布局中已添加的组件容器
public void 删除页面布局(组件容器 欲删除页面) {
可视化组件 component = 欲删除页面.取根布局();
adapter.remove(component.getView());
adapter.notifyDataSetChanged();
}

//删除分页布局中指定页面
public void 删除页面(int 索引) {
adapter.remove(索引);
adapter.notifyDataSetChanged();
}

//切换至指定索引处页面
public void 显示页面(int 页面索引, boolean 是否播放切换动画) {
getView().setCurrentItem(页面索引, 是否播放切换动画);
}

//获取当前页面索引
public int 取当前页面索引() {
return getView().getCurrentItem();
}

//分页布局页面被改变时触发该事件，返回页面索引，索引从0开始
public void 页面被改变(int 索引) { return null; } // 事件

//分页布局页面被选中时触发该事件，返回页面索引，索引从0开始
public void 页面被选中(int 索引) { return null; } // 事件

//分页布局页面被滚动时触发该事件，返回页面索引，移量百分比，偏移量数值
public void 页面被滑动(int 索引, double 移量百分比, double 偏移量数值) { return null; } // 事件

//分页布局滑动状态改变时触发该事件，返回滑动状态，0为什么都不做，1为开始滑动，2为结束滑动
public void 滑动状态改变(int 状态) { return null; } // 事件
}