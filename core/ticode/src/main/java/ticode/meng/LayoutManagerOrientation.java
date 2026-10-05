package ticode.meng;

import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.flexbox.FlexboxLayoutManager;

import ticode.android.AndroidEnv;
import ticode.android.WindowComponent;

public class LayoutManagerOrientation {
public static final LayoutManagerOrientation 横 = 0;
public static final LayoutManagerOrientation 横_倒序 = 1;
public static final LayoutManagerOrientation 竖 = 2;
public static final LayoutManagerOrientation 竖_倒序 = 3;
}