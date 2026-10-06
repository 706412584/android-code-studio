package ticode.zh.android;


public class 安卓程序签名数据 extends android.content.pm.Signature {
public 安卓程序签名数据(String 签名数据) { super(签名数据); }

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

public 安卓程序签名数据(byte[] 签名数据) { super(签名数据); }

public static 安卓程序签名数据 从文本创建(String 签名数据) {
return new 安卓程序签名数据(签名数据);
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