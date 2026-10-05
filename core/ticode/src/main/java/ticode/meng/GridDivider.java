package ticode.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import com.Meng.decoration.SpacesItemDecoration;
import com.Meng.decoration.GridSpaceItemDecoration;
import com.google.android.flexbox.FlexboxItemDecoration;

import ticode.android.AndroidApplication;
import ticode.android.DrawableObject;
import ticode.android.ImageResource;

public class GridDivider extends Divider {

public GridDivider(){
this(8, false);
}
public GridDivider(int j, boolean in){
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