package ticode.zh.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import com.Meng.decoration.SpacesItemDecoration;
import com.Meng.decoration.GridSpaceItemDecoration;
import com.google.android.flexbox.FlexboxItemDecoration;

import ticode.zh.android.可绘制对象;
import ticode.zh.android.图片资源;
import ticode.zh.android.安卓应用;

public class 宫格分割线 extends 分割线 {

public 宫格分割线(){
this(8, false);
}
public 宫格分割线(int j, boolean in){
this.idn = new GridSpaceItemDecoration(j, in);
}
public GridSpaceItemDecoration getIDN(){return (GridSpaceItemDecoration)idn;}

public void 间距(int 间距) {
getIDN().mSpacing = 间距;
}

public void 外间距(boolean 是否) {
getIDN().mIncludeEdge = 是否;
}






public void 不显示分割线的位置(int 头部, int 尾部) {
getIDN().setNoShowSpace(头部, 尾部);
}

}