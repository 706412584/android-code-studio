package ticode.zh.meng;

import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import ticode.zh.android.可视化组件;
import ticode.zh.android.安卓环境;
import ticode.zh.android.安卓窗口;
import ticode.zh.android.组件容器;
import ticode.zh.android.适配器;
import ticode.zh.jvm.集合;

public class 高级列表项目触摸方向 {

public static final int 全向 = 15;
public static final int 上 = 1;
public static final int 下 = 2;
public static final int 上下 = 3;
public static final int 左 = 4;
public static final int 右 = 8;
public static final int 左右 = 12;

public static final int 相对起始 = 16;
public static final int 相对结束 = 32;

}