package ticode.zh.android;

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

public class 进度对话框 extends 窗口组件 {
private ProgressDialog dialog;

public 进度对话框(Context context) {
super(context);
this.dialog = new ProgressDialog(context);
}

public static final int 风格_圆形进度条 = 0;
public static final int 风格_水平进度条 = 1;

//设置进度对话框风格，0为圆形进度条，1为水平进度条
public void 风格(int 风格) {
dialog.setProgressStyle(风格);
}

//设置对话框的标题
public void 标题(String 标题) {
dialog.setTitle(标题);
}

//设置对话框要显示的信息
public void 信息(String 信息) {
dialog.setMessage(信息);
}

//设置进度条进度
public void 进度(int 进度值) {
dialog.setProgress(进度值);
}

//获取进度条进度
public int 进度() {
return dialog.getProgress();
}

//设置进度条的最大进度
public void 最大进度(int 最大进度值) {
dialog.setMax(最大进度值);
}

//获取进度条的最大进度
public int 最大进度() {
return dialog.getMax();
}

//设置进度条的缓冲进度，常用于缓冲音视频时设置缓冲进度
public void 缓冲进度(int 缓冲进度) {
dialog.setSecondaryProgress(缓冲进度);
}

//获取进度条缓冲进度
public int 缓冲进度() {
return dialog.getSecondaryProgress();
}

//设置进度条进度是否为模糊进度，如设置为真，则不再显示进度，而是一种无限刷新加载的状态
public void 模糊进度(boolean 是否不明确进度) {
dialog.setIndeterminate(是否不明确进度);
}

//获取进度条是否为模糊进度状态
public boolean 模糊进度() {
return dialog.isIndeterminate();
}

//设置对话框图标, 参数为res图片资源ID
public void 图标(图片资源 图标) {
dialog.setIcon(图标);
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

//判断进度对话框是否在显示
public boolean 正在显示() {
return dialog.isShowing();
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

//对话框按钮1被单击触发该事件
public void 按钮1被单击() { } // 事件

//对话框按钮2被单击触发该事件
public void 按钮2被单击() { } // 事件

//对话框按钮3被单击触发该事件
public void 按钮3被单击() { } // 事件
}