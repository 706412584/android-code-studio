package ticode.meng;

import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.flexbox.FlexboxLayoutManager;

import ticode.android.AndroidEnv;
import ticode.android.WindowComponent;

public class LinearLayoutManager2 extends LayoutManager2 {
public LinearLayoutManager2(android.content.Context context) {
super(context);
LayoutManager2 = new LinearLayoutManager(context,1,false);
}

public LinearLayoutManager getLM(){
return (LinearLayoutManager)LayoutManager2;
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