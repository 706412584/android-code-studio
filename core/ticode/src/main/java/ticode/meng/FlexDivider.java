package ticode.meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import com.Meng.decoration.SpacesItemDecoration;
import com.Meng.decoration.GridSpaceItemDecoration;
import com.google.android.flexbox.FlexboxItemDecoration;

import ticode.android.AndroidApplication;
import ticode.android.DrawableObject;
import ticode.android.ImageResource;

public class FlexDivider extends Divider {

public FlexDivider(){
this.idn = new FlexboxItemDecoration(AndroidApplication.取安卓应用());
}
public FlexboxItemDecoration getIDN(){return (FlexboxItemDecoration)idn;}

public void 自定义(DrawableObject 图) {
getIDN().setDrawable(图);
}

public void 方向(FlexDividerOrientation 方向) {
getIDN().setOrientation(方向);
}

}