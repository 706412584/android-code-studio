package ticode.android;

import android.widget.AdapterView;
import android.widget.AdapterView.*;
import android.content.Context;
import android.view.View;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.GridView;
import android.view.*;

public class BaseAdapter2 extends android.widget.BaseAdapter {



public void 通知_更新数据() {
this.notifyDataSetChanged();
}
}