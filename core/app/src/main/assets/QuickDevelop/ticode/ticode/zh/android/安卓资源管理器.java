package ticode.zh.android;


public class 安卓资源管理器 extends android.content.res.Resources {
public 安卓资源管理器() { super(null, null, null); }

public android.content.res.AssetManager 取附加资源管理器() {
return (android.content.res.AssetManager)this.getAssets();
}

public android.graphics.drawable.Drawable 取可绘制对象(int 图片资源对象) {
// getDrawable 是框架原生实例，不是 可绘制对象 子类，强转必 ClassCastException。返回原生类型。
return this.getDrawable(图片资源对象);
}

public android.graphics.Bitmap 取位图对象(int 图片资源对象) {
return ((android.graphics.drawable.BitmapDrawable)this.getDrawable(图片资源对象)).getBitmap();
}
}