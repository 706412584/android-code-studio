package 结绳.安卓;

import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

public class 日期选择框 extends 窗口组件 {

private DatePickerDialog dialog;

public 日期选择框(安卓环境 context) {
super(context);
Calendar calendar = Calendar.getInstance();
this.dialog = new DatePickerDialog(context, new DatePickerDialog.OnDateSetListener(){
public void onDateSet(DatePicker p1, int year, int month, int dayOfMonth) {
日期被确定(year, month + 1, dayOfMonth);
}
}, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)) {
public void onDateChanged(android.widget.DatePicker view, int year, int month, int dayOfMonth) {
日期被改变(year, month + 1, dayOfMonth);
}
};
}

//设置对话框的标题
public void 标题(String 标题) {
dialog.setTitle(标题);
}

//设置对话框要显示的信息
public void 信息(String 信息) {
dialog.setMessage(信息);
}

//设置对话框图标, 参数为附加图片资源名称
public void 图标文件(String 图片名称) {
try {
dialog.setIcon(Drawable.createFromStream(context.getAssets().open(图片名称), 图片名称));
} catch (java.io.IOException e) {
e.printStackTrace();
}
}

//设置对话框图标, 参数为图片资源
public void 图标(图片资源 图片) {
dialog.setIcon(图片);
}

//设置对话框按钮1的文本
public void 按钮1(String 按钮1文本) {
dialog.setButton(按钮1文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮1被单击();
}
});
}

//设置对话框按钮2的文本
public void 按钮2(String 按钮2文本) {
dialog.setButton2(按钮2文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮2被单击();
}
});
}

//设置对话框按钮3的文本
public void 按钮3(String 按钮3文本) {
dialog.setButton3(按钮3文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮3被单击();
}
});
}

//设置对话框是否可取消，若设置为假，则不能通过返回键取消对话框，只能通过代码取消对话框
public void 可取消(boolean 可取消) {
dialog.setCancelable(可取消);
}

//显示对话框
public void 显示() {
dialog.show();
}

//隐藏对话框
public void 隐藏() {
dialog.hide();
}

//关闭对话框
public void 关闭() {
dialog.dismiss();
}

public void 日期被改变(int 年, int 月, int 日) { return null; } // 事件

public void 日期被确定(int 年, int 月, int 日) { return null; } // 事件

//对话框按钮1被单击触发该事件
public void 按钮1被单击() { return null; } // 事件

//对话框按钮2被单击触发该事件
public void 按钮2被单击() { return null; } // 事件

//对话框按钮3被单击触发该事件
public void 按钮3被单击() { return null; } // 事件

}




