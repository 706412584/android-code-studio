package ticode.android;


import ticode.jvm.JInputStream;

public class ExtraResourceManager extends android.content.res.AssetManager {
//打开指定文件输入流
public JInputStream 打开文件(String 文件名) {
try {
return this.open(文件名);
} catch (Exception e) { }
return null;
}
}