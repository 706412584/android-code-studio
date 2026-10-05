package 结绳.安卓;


public class 安卓资源管理器 {

public 附加资源管理器 取附加资源管理器() {
return this.getAssets();
}

public 可绘制对象 取可绘制对象(图片资源 图片资源对象) {
return this.getDrawable(图片资源对象);
}

public 位图对象 取位图对象(图片资源 图片资源对象) {
return ((android.graphics.drawable.BitmapDrawable)this.getDrawable(图片资源对象)).getBitmap();
}
}

//安卓附加资源管理器
