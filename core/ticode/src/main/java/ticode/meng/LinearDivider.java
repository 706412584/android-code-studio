package ticode.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import com.Meng.decoration.SpacesItemDecoration;
import com.Meng.decoration.GridSpaceItemDecoration;
import com.google.android.flexbox.FlexboxItemDecoration;

import ticode.android.AndroidApplication;
import ticode.android.DrawableObject;
import ticode.android.ImageResource;

public class LinearDivider extends Divider {

public LinearDivider(){
this.idn = new SpacesItemDecoration();
}
public SpacesItemDecoration getIDN(){return (SpacesItemDecoration)idn;}

public void 方向(int 方向) {
getIDN().setOrientation(方向);
}



public void 纯色分割线(int 颜色, int 间距, double 左边距, double 右边距) {
getIDN().setParam(颜色, 间距, (float)左边距, (float)右边距);
}

public void 分割图(DrawableObject 图片) {
getIDN().setDrawable(图片);
}

public void 分割图_图片资源(ImageResource 图片) {
getIDN().setDrawable(图片);
}






public void 不显示分割线的数量(int 头部, int 尾部) {
getIDN().setNoShowDivider(头部, 尾部);
}

}