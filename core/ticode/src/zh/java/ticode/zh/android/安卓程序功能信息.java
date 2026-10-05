package ticode.zh.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class 安卓程序功能信息 extends android.content.pm.FeatureInfo {

public static final int GLES版本_未定义 = 0;

public static final int 标志_需要 = 1;

public boolean 等于_op(安卓程序功能信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序功能信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public String 名称() {
return this.name;
}

public void 名称(String 名称) {
this.name = 名称;
}

public int GLES版本() {
return this.reqGlEsVersion;
}

public void GLES版本(int GLES版本) {
this.reqGlEsVersion = GLES版本;
}

public int 版本() {
return this.version;
}

public void 版本(int 版本) {
this.version = 版本;
}

public String 取GLES版本名称() {
return this.getGlEsVersion();
}

}