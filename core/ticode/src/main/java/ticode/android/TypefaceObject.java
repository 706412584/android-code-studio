package ticode.android;


public class TypefaceObject extends android.graphics.Typeface {
public TypefaceObject 默认字体() {
return android.graphics.Typeface.DEFAULT;
}

public TypefaceObject 默认粗体字体() {
return android.graphics.Typeface.DEFAULT_BOLD;
}

public TypefaceObject 等宽字体() {
return android.graphics.Typeface.MONOSPACE;
}

public TypefaceObject 衬线字体() {
return android.graphics.Typeface.SERIF;
}

public TypefaceObject 无衬线字体() {
return android.graphics.Typeface.SANS_SERIF;
}

public static TypefaceObject 从资源文件创建字体(AndroidActivity 窗口环境, String 文件名) {
return android.graphics.Typeface.createFromAsset(窗口环境.getAssets(), 文件名);
}

public static TypefaceObject 从文件路径创建字体(String 文件路径) {
return android.graphics.Typeface.createFromFile(文件路径);
}
}