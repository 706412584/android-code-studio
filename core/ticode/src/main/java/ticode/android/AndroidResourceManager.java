package ticode.android;


import ticode.jvm.JInputStream;

public class AndroidResourceManager extends android.content.res.Resources {

public ExtraResourceManager 取附加资源管理器() {
return this.getAssets();
}

public DrawableObject 取可绘制对象(ImageResource 图片资源对象) {
return this.getDrawable(图片资源对象);
}

public BitmapObject 取位图对象(ImageResource 图片资源对象) {
return ((android.graphics.drawable.BitmapDrawable)this.getDrawable(图片资源对象)).getBitmap();
}
}