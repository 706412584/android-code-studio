package ticode.zh.android;


public class 字体对象 {
public android.graphics.Typeface 默认字体() {return null; }

public android.graphics.Typeface 默认粗体字体() {return null; }

public android.graphics.Typeface 等宽字体() {return null; }

public android.graphics.Typeface 衬线字体() {return null; }

public android.graphics.Typeface 无衬线字体() {return null; }

public static android.graphics.Typeface 从资源文件创建字体(安卓窗口 窗口环境, String 文件名) {
return android.graphics.Typeface.createFromAsset(窗口环境.getAssets(), 文件名);
}

public static android.graphics.Typeface 从文件路径创建字体(String 文件路径) {
return android.graphics.Typeface.createFromFile(文件路径);
}
}