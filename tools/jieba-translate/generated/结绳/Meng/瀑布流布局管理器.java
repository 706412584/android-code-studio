package 结绳.Meng;

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

public class 瀑布流布局管理器 extends 布局管理器 {
public 瀑布流布局管理器(android.content.Context context) {
super(context);
布局管理器 = new StaggeredGridLayoutManager(2, 1);
}

public StaggeredGridLayoutManager getLM(){
return (StaggeredGridLayoutManager)布局管理器;
}

public void 列数(int 列数) {
getLM().setSpanCount(列数);
}

//滑动后自动填充间隙排序，默认：假
public void 禁用间隙自动填充(boolean 是否) {
getLM().setGapStrategy(是否 ? 0 : 2);
}

public void 排列方向(布局管理器_排列方向 排列方向) {
int 方向;
方向 = 排列方向;
假如 方向;
return 0;
getLM().setOrientation(0);
return 1;
getLM().setOrientation(0);
倒序(true);
return 2;
getLM().setOrientation(1);
return 3;
getLM().setOrientation(1);
倒序(true);
结束 假如;
}

public void 倒序(boolean 是否倒序) {
getLM().setReverseLayout(是否倒序);
}

}

