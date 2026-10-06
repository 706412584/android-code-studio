package ticode.meng;

import androidx.recyclerview.widget.RecyclerView.LayoutManager;

import ticode.android.WindowComponent;

public class LayoutManager2 extends WindowComponent {
public AdvancedListView rv;
public LayoutManager LayoutManager2;

public LayoutManager2(android.content.Context context) {
super(context);
}

public LayoutManager getLM(){
return (LayoutManager)LayoutManager2;
}

public void setRv(AdvancedListView l){
this.rv = l;
}

public int 取项目类型(int 索引) {
return 取列表().取适配器().取项目类型(索引);
}

public AdvancedListView 取列表() {
return rv;
}

public AdvancedAdapter 取适配器() {
return 取列表().取适配器();
}

}