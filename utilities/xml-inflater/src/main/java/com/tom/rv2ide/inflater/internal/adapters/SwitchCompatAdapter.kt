package com.tom.rv2ide.inflater.internal.adapters

import androidx.appcompat.widget.SwitchCompat
import com.tom.rv2ide.annotations.inflater.ViewAdapter
import com.tom.rv2ide.inflater.IView

@ViewAdapter(SwitchCompat::class)
open class SwitchCompatAdapter<T : SwitchCompat> : CompoundButtonAdapter<T>() {
  override fun applyBasic(view: IView) {
    super.applyBasic(view)
    (view.view as SwitchCompat).apply {
      isChecked = false
      text = ""
    }
  }
}
