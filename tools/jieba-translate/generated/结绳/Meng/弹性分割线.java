package 结绳.Meng;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import com.Meng.decoration.SpacesItemDecoration;
import com.Meng.decoration.GridSpaceItemDecoration;
import com.google.android.flexbox.FlexboxItemDecoration;

public class 弹性分割线 extends 分割线 {

public 弹性分割线(){
this.idn = new FlexboxItemDecoration(安卓应用.取安卓应用());
}
public FlexboxItemDecoration getIDN(){return (FlexboxItemDecoration)idn;}

public void 自定义(可绘制对象 图) {
getIDN().setDrawable(图);
}

public void 方向(弹性分割线_方向 方向) {
getIDN().setOrientation(方向);
}

}

