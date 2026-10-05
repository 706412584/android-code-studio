package ticode.android;


import ticode.jvm.JInputStream;

public class AndroidResourceManager extends android.content.res.Resources {

public android.content.res.AssetManager 取附加资源管理器() {
return this.getAssets();
}

public DrawableObject 取可绘制对象(ImageResource 图片资源对象) {
return this.getDrawable(图片资源对象);
}

public android.graphics.Bitmap 取位图对象(ImageResource 图片资源对象) {
return ((android.graphics.drawable.BitmapDrawable)this.getDrawable(图片资源对象)).getBitmap();
}
}