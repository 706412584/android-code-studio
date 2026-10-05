package 结绳.安卓;

import android.content.Context;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.graphics.Typeface;
import android.text.Html;
import android.text.TextUtils;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.CompoundButton;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.CompoundButton;
import android.widget.ImageView.ScaleType;
import android.graphics.*;
import android.widget.ProgressBar;
import android.graphics.drawable.*;
import android.graphics.*;
import android.widget.ProgressBar;
import android.graphics.drawable.*;
import android.graphics.*;
import android.widget.SeekBar;
import android.graphics.drawable.*;
import android.widget.RatingBar;
import android.widget.VideoView;
import android.media.MediaPlayer;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.app.Activity;
import android.app.DownloadManager;
import android.widget.FrameLayout;
import java.io.File;
import android.graphics.Bitmap;
import android.annotation.TargetApi;
import android.widget.ProgressBar;
import android.webkit.*;

public class 图片框 extends 可视化组件 {

public 图片框(android.content.Context context) {
super(context);
}

public android.widget.ImageView onCreateView(android.content.Context context) {
android.widget.ImageView view = new android.widget.ImageView(context);
return view;
}

public android.widget.ImageView getView() {
return (android.widget.ImageView) view;
}

public void 图片资源(图片资源 需显示图片) {
getView().setImageResource(需显示图片);
}

public void 图片路径(String 图片路径) {
getView().setImageBitmap(android.graphics.BitmapFactory.decodeFile(图片路径));
}

public void 图片数据(byte[] 数据) {
getView().setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(数据,0,数据.length));
}

public void 图片对象(可绘制对象 图片可绘制对象) {
getView().setImageDrawable(图片可绘制对象);
}

public void 位图对象(位图对象 位图对象) {
getView().setImageBitmap(位图对象);
}

public void 附加资源(String 附加资源) {
输入流 流 = 取安卓环境().取附加资源管理器().打开文件(附加资源.替换("../",""));
this.位图对象 = 位图对象.从输入流创建位图(流);
容错运行(流.关闭());
}

public void 图片透明度(int 透明值) {
getView().setImageAlpha(透明值);
}

public int 图片透明度() {
return getView().getImageAlpha();
}

public void 拉伸方式(int 缩放类型) {
假如 缩放类型;
return 0;
this.图像缩放类型 = 图像缩放类型.完全拉伸;
return 1;
this.图像缩放类型 = 图像缩放类型.居中;
return 2;
this.图像缩放类型 = 图像缩放类型.左上;
return 3;
this.图像缩放类型 = 图像缩放类型.自适应居中;
return 4;
this.图像缩放类型 = 图像缩放类型.右下;
return 5;
this.图像缩放类型 = 图像缩放类型.矩阵;
return 6;
this.图像缩放类型 = 图像缩放类型.裁切居中;
return 7;
this.图像缩放类型 = 图像缩放类型.内置居中;
结束 假如;
}

public void 图像缩放类型(图像缩放类型 类型) {
getView().setScaleType(类型);
}

public 图像缩放类型 图像缩放类型() {
return getView().getScaleType();
}

public void 图片自适应(boolean 是否自适应图片) {
getView().setAdjustViewBounds(是否自适应图片);
}

public boolean 图片自适应() {
return getView().getAdjustViewBounds();
}

public void 最大扩展宽度(int 宽度) {
getView().setMaxWidth(宽度);
}

public int 最大扩展宽度() {
return getView().getMaxWidth();
}

public void 最大扩展高度(int 高度) {
getView().setMaxHeight(高度);
}

public int 最大扩展高度() {
return getView().getMaxHeight();
}

public void 裁切内边距(boolean 保留内边距) {
getView().setCropToPadding(保留内边距);
}

public boolean 裁切内边距() {
return getView().getCropToPadding();
}

public void 图像级别(int 设置图片级别) {
getView().setImageLevel(设置图片级别);
}





public void 加载网络图片(String 网址) {
网络工具 网络;
Object 字节集 = 网络.取网页源码_字节集_同步(网址);
this.图片数据 = 字节集;
}

public 可绘制对象 取图片() {
return getView().getDrawable();
}

}

