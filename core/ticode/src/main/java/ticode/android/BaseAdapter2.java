package ticode.android;

import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public class BaseAdapter2 extends android.widget.BaseAdapter {



public void 通知_更新数据() {
this.notifyDataSetChanged();
}
public int 项目数量() {
return this.getCount();
}
}