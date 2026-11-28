package com.tom.rv2ide.inflater.internal.adapters

import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.annotations.inflater.ViewAdapter
import com.tom.rv2ide.inflater.IView
import com.tom.rv2ide.inflater.IViewGroup

@ViewAdapter(RecyclerView::class)
open class RecyclerViewAdapter<T : RecyclerView> : ViewGroupAdapter<T>() {
  // 列表项由被预览应用的 Adapter 创建，不是可向 XML 拖入的直接子节点。
  override fun canAcceptChild(view: IViewGroup, child: IView?, name: String) = false
}
