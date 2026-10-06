package ticode.android;

import java.security.KeyPairGenerator;
import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class RsaKeyPair2 extends java.security.KeyPair {
public String 公钥() {
return CryptoOps.Base64编码_字节集(this.getPublic().getEncoded(), CryptoOps.Base64编码集);
}

public String 私钥() {
return CryptoOps.Base64编码_字节集(this.getPrivate().getEncoded(), CryptoOps.Base64编码集);
}

public static RsaKeyPair2 创建RSA密钥对(int 密钥长度) {
try {
KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
keyPairGenerator.initialize(密钥长度);
return keyPairGenerator.generateKeyPair();
} catch (Exception e) {
}
return null;
}
}