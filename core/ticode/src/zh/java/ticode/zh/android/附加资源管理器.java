package ticode.zh.android;


import ticode.zh.jvm.输入流;

public class 附加资源管理器 extends android.content.res.AssetManager {
//打开指定文件输入流
public 输入流 打开文件(String 文件名) {
try {
return this.open(文件名);
} catch (Exception e) { }
return null;
}
}