package ticode.zh.meng;

import androidx.recyclerview.widget.LinearLayoutManager;

public class 线性布局管理器 extends 布局管理器 {
public 线性布局管理器(android.content.Context context) {
super(context);
布局管理器 = new LinearLayoutManager(context,1,false);
}

public LinearLayoutManager getLM(){
return (LinearLayoutManager)布局管理器;
}

public void 排列方向(布局管理器_排列方向 排列方向) {
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