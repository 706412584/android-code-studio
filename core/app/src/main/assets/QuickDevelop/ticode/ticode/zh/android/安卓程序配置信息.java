package ticode.zh.android;


public class 安卓程序配置信息 extends android.content.pm.ConfigurationInfo {

public static final int GLES版本_未定义 = 0;

public static final int 输入功能_五向导航设备 = 2;

public static final int 输入功能_硬键盘 = 1;

public boolean 等于_op(安卓程序配置信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序配置信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int GLES版本() {
return this.reqGlEsVersion;
}

public void GLES版本(int GLES版本) {
this.reqGlEsVersion = GLES版本;
}

public int 输入功能() {
return this.reqInputFeatures;
}

public void 输入功能(int 输入功能) {
this.reqInputFeatures = 输入功能;
}

public String 取GLES版本名称() {
return this.getGlEsVersion();
}

}