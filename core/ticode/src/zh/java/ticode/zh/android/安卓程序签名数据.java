package ticode.zh.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class 安卓程序签名数据 extends android.content.pm.Signature {

public boolean 等于_op(安卓程序签名数据 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序签名数据 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public void 赋值_op(byte[] 签名数据) {
return new android.content.pm.Signature(签名数据);
}

public static 安卓程序签名数据 从文本创建(String 签名数据) {
return new android.content.pm.Signature(签名数据);
}

public char[] 到字符集() {
return this.toChars();
}

public String 到字符集文本() {
return this.toCharsString();
}

public byte[] 到字节集() {
return this.toByteArray();
}

}