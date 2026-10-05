package ticode.zh.meng;

import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.flexbox.FlexboxLayoutManager;

import ticode.zh.android.安卓环境;
import ticode.zh.android.窗口组件;

public class 布局管理器_排列方向 {
public static final int 横 = 0;
public static final int 横_倒序 = 1;
public static final int 竖 = 2;
public static final int 竖_倒序 = 3;
}