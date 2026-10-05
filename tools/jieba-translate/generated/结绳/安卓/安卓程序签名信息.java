package 结绳.安卓;


public class 安卓程序签名信息 {

public boolean 等于_op(安卓程序签名信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序签名信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public boolean 有多个签名者() {
return this.hasMultipleSigners();
}

public boolean 含有过期的签名证书() {
return this.hasPastSigningCertificates();
}

public 安卓程序签名数据[] 取历史签名数据() {
return this.getSigningCertificateHistory();
}

public 安卓程序签名数据[] 取签名数据() {
return this.getApkContentsSigners();
}

}

