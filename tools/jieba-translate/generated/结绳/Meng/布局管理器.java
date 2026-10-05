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

public class 布局管理器 extends 窗口组件 {
public 高级列表框 rv;
public LayoutManager 布局管理器;

public 布局管理器(android.content.Context context) {
super(context);
}

public LayoutManager getLM(){
return (LayoutManager)布局管理器;
}

public void setRv(高级列表框 l){
this.rv = l;
}

public int 取项目类型(int 索引) {
return 取列表().取适配器().取项目类型(索引);
}

public 高级列表框 取列表() {
return rv;
}

public 高级适配器 取适配器() {
return 取列表().取适配器();
}

}

