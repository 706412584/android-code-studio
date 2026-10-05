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

public class StaggeredLayoutManager extends LayoutManager2 {
public StaggeredLayoutManager(android.content.Context context) {
super(context);
LayoutManager2 = new StaggeredGridLayoutManager(2, 1);
}

public StaggeredGridLayoutManager getLM(){
return (StaggeredGridLayoutManager)LayoutManager2;
}

public void 列数(int 列数) {
getLM().setSpanCount(列数);
}

//滑动后自动填充间隙排序，默认：假
public void 禁用间隙自动填充(boolean 是否) {
getLM().setGapStrategy(是否 ? 0 : 2);
}

public void 排列方向(LayoutManagerOrientation 排列方向) {
int 方向;
方向 = 排列方向;
switch (方向) {
case 0:
getLM().setOrientation(0);
case 1:
getLM().setOrientation(0);
倒序(true);
case 2:
getLM().setOrientation(1);
case 3:
getLM().setOrientation(1);
倒序(true);
}
}

public void 倒序(boolean 是否倒序) {
getLM().setReverseLayout(是否倒序);
}

}