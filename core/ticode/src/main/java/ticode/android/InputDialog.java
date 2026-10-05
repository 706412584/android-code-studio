package ticode.android;

import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.widget.*;
import java.util.*;
import android.graphics.*;

public class InputDialog extends WindowComponent {

private EditText et;
private AlertDialog dialog;
private AlertDialog.Builder builder;

public InputDialog(AndroidEnv context) {
super(context);
builder = new AlertDialog.Builder(context);
et = new EditText(context);
builder.setView(et);
et.addTextChangedListener(new android.text.TextWatcher(){
@Override
public void beforeTextChanged(CharSequence p1, int p2, int p3, int p4) {
}
@Override
public void onTextChanged(CharSequence p1, int p2, int p3, int p4) {
}
@Override
public void afterTextChanged(android.text.Editable p1) {
输入内容被改变();
}
});


}

//设置编辑框内容
public void 内容(String 内容) {
et.setText(内容);
}

//获取编辑框内容
public String 内容() {
return et.getText().toString();
}

//设置编辑框提示文本
public void 提示文本(String 提示文本) {
et.setHint(提示文本);
}

//获取编辑框提示文本
public String 提示文本() {
return et.getHint().toString();
}

//设置编辑框提示文本颜色，0xaarrggb格式
public void 提示文本颜色(int 提示文本颜色) {
et.setHintTextColor(提示文本颜色);
}

//获取编辑框提示文本颜色
public int 提示文本颜色() {
return et.getHintTextColors().getDefaultColor();
}

//设置编辑框字体大小
public void 字体大小(int 字体大小) {
et.setTextSize(字体大小);
}

//获取编辑框字体大小
public int 字体大小() {
return (int)et.getTextSize();
}

//设置编辑框字体颜色
public void 字体颜色(int 字体颜色) {
et.setTextColor(字体颜色);
}

//获取编辑框字体颜色
public int 字体颜色() {
return et.getTextColors().getDefaultColor();
}

//设置编辑框是否为密码输入模式
public void 密码输入(boolean 是否密码输入) {
if (是否密码输入) {
et.setInputType(0x81);
} else {
et.setInputType(0);
}
}

//设置对话框的标题
public void 标题(String 标题) {
builder.setTitle(标题);
}

//设置对话框要显示的信息
public void 信息(String 信息) {
builder.setMessage(信息);
}

//设置对话框图标, 参数为附加图片资源名称
public void 图标(String 图片名称) {
try {
builder.setIcon(
Drawable.createFromStream(
context.getAssets().open(图片名称), 图片名称
)
);
} catch (java.io.IOException e) {
e.printStackTrace();
}
}

//设置对话框图标, 参数为图片资源
public void 图标(ImageResource 图片) {
builder.setIcon(图片);
}

//设置对话框按钮1的文本
public void 按钮1(String 按钮1文本) {
builder.setPositiveButton(按钮1文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮1被单击();
}
});
}

//设置对话框按钮2的文本
public void 按钮2(String 按钮2文本) {
builder.setNegativeButton(按钮2文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮2被单击();
}
});
}

//设置对话框按钮3的文本
public void 按钮3(String 按钮3文本) {
builder.setNeutralButton(按钮3文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮3被单击();
}
});
}

//设置对话框是否可取消，若设置为假，则不能通过返回键取消对话框，只能通过代码取消对话框
public void 可取消(boolean 可取消) {
builder.setCancelable(可取消);
}

//显示对话框
public void 显示() {
dialog = builder.create();
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

public void 输入内容被改变() { } // 事件

//对话框按钮1被单击触发该事件
public void 按钮1被单击() { } // 事件

//对话框按钮2被单击触发该事件
public void 按钮2被单击() { } // 事件

//对话框按钮3被单击触发该事件
public void 按钮3被单击() { } // 事件

}