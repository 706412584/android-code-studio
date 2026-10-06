package ticode.zh.android;

import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public abstract class 基础适配器 extends android.widget.BaseAdapter {



public void 通知_更新数据() {
this.notifyDataSetChanged();
}
public int 项目数量() {
return this.getCount();
}
}