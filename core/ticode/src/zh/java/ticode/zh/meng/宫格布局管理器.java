package ticode.zh.meng;

import androidx.recyclerview.widget.GridLayoutManager;

public class 宫格布局管理器 extends 线性布局管理器 {

public 宫格布局管理器(android.content.Context context) {
super(context);
布局管理器 = new GridLayoutManager(context, 2);
getLM().setSpanSizeLookup(
new GridLayoutManager.SpanSizeLookup() {
@Override
public int getSpanSize(int p) {
Integer n = 项目占用格数(取项目类型(p), p);
return n > 0 ? n : 1;
}
}
);
}

public GridLayoutManager getLM(){
return (GridLayoutManager)布局管理器;
}

public void 列数(int 列数) {
getLM().setSpanCount(列数);
}

public int 项目占用格数(int 项目类型, int 索引) { return 0; } // 事件

}