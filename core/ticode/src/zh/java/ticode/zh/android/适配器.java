package ticode.zh.android;

import android.widget.AdapterView.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;

public abstract class 适配器 implements android.widget.Adapter {



public int 项目数量() {
return this.getCount();
}
}