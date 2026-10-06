package ticode.zh.android;

import android.widget.CompoundButton;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public abstract class 复合按钮 extends 按钮 {
public 复合按钮(android.content.Context context) {
super(context);
getView().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
@Override
public void onCheckedChanged(CompoundButton btn, boolean checked) {
选中状态改变(checked);
}
});
}

@Override
public abstract CompoundButton onCreateView(android.content.Context context);

@Override
public abstract CompoundButton getView();

//设置复合类按钮的选中状态
public void 选中(boolean 是否选中) {
getView().setChecked(是否选中);
}

//获取复合类按钮的选中状态
public boolean 选中() {
return getView().isChecked();
}

//切换复合类按钮的选中状态
public void 切换状态() {
getView().toggle();
}




public void 选中状态改变(boolean 选中) { } // 事件
}