package ticode.zh.android;

import android.view.ViewGroup;

import ticode.zh.jvm.输入流;

public class GIF动画框 extends 可视化组件 {
public GIF动画框(android.content.Context context) {
super(context);
}

@Override
public rn_1.GIFView onCreateView(android.content.Context context) {
rn_1.GIFView view = new rn_1.GIFView(context);
return view;
}

@Override
public rn_1.GIFView getView() {
return (rn_1.GIFView) view;
}

//设置GIF动画框的资源路径，文件路径可以为assets资源名称，也可以为sd卡路径
public void 动画路径(String GIF文件路径) {
if (GIF文件路径.开头为("/")) {
getView().setMovieResource(GIF文件路径);
} else {
输入流 文件流 = 取安卓环境().取附加资源管理器().打开文件(GIF文件路径);;
if (文件流 == null) {
return;
}
this.动画输入流 = 文件流;
文件流.关闭();
}
}

//通过输入流设置动画
public void 动画输入流(输入流 动画输入流) {
getView().setMovieResource(动画输入流);
}

public boolean 播放状态() {
return !getView().isPaused();
}

public void 播放状态(boolean 是否播放) {
getView().setPaused(!是否播放);
}

public int 动画时长() {
return getView().getMovieTime();
}

public void 动画时长(int 动画时长) {
getView().setMovieTime(动画时长);
}
}