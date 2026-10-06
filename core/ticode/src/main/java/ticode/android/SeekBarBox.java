package ticode.android;

import android.widget.SeekBar;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class SeekBarBox extends ProgressBox {
public SeekBarBox(android.content.Context context) {
super(context);
getView().setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
@Override
public void onProgressChanged(SeekBar p1, int p2, boolean p3) {
进度被改变(p2, p3);
}
@Override
public void onStartTrackingTouch(SeekBar p1) {
开始拖动();
}
@Override
public void onStopTrackingTouch(SeekBar p1) {
结束拖动();
}
});
}

@Override
public SeekBar onCreateView(android.content.Context context) {
SeekBar view = new SeekBar(context);
return view;
}

@Override
public SeekBar getView() {
return (SeekBar) view;
}

public void 进度被改变(int 当前进度, boolean 是否人为改变) { } // 事件

public void 开始拖动() { } // 事件

public void 结束拖动() { } // 事件
}