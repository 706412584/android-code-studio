package ticode.zh.android;


public class 安卓资源管理器 extends android.content.res.Resources {
public 安卓资源管理器() { super(null, null, null); }

public android.content.res.AssetManager 取附加资源管理器() {
return (android.content.res.AssetManager)this.getAssets();
}

public 可绘制对象 取可绘制对象(int 图片资源对象) {
return (可绘制对象)this.getDrawable(图片资源对象);
}

public android.graphics.Bitmap 取位图对象(int 图片资源对象) {
return ((android.graphics.drawable.BitmapDrawable)this.getDrawable(图片资源对象)).getBitmap();
}
}