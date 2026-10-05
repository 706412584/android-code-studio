package ticode.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class SignatureInfo extends android.content.pm.SigningInfo {

public boolean 等于_op(SignatureInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(SignatureInfo 另一个) {
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

public SignatureData[] 取历史签名数据() {
return this.getSigningCertificateHistory();
}

public SignatureData[] 取签名数据() {
return this.getApkContentsSigners();
}

}