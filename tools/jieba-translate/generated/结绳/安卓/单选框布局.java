package 结绳.安卓;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.*;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout.LayoutParams;
import android.widget.GridLayout;
import android.view.View;
import rn_1.*;
import java.util.*;
import android.view.View;

public class 单选框布局 extends 线性布局 {
public static final String 添加子组件错误 = "单选框布局中只能加入单选框组件";

public 单选框布局(android.content.Context context) {
super(context);
}

public void addComponent(可视化组件 component) {
if (!(component instanceof 单选框)) {
throw new RuntimeException(添加子组件错误);
}
getView().addView(component.getView());
((android.widget.CompoundButton) component.getView())
.setOnCheckedChangeListener(
new android.widget.CompoundButton.OnCheckedChangeListener(){
public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
if (checked) {
refresh(btn);
单选框被选中((单选框) btn.getTag());
}
}
});
}

private void refresh(android.view.View exclude) {
int count = getView().getChildCount();
for (int i = 0;i < count;i++) {
android.widget.RadioButton checkBox = (android.widget.RadioButton) getView().getChildAt(i);
if (checkBox != exclude) {
checkBox.setChecked(false);
}
}
}

public void 单选框被选中(单选框 被选中的单选框) { return null; } // 事件
}





