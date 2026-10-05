package 结绳.安卓;

import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.KeyPairGenerator;

public class RSA密钥对 {
public String 公钥() {
return 加解密操作.Base64编码_字节集(this.getPublic().getEncoded(), 加解密操作.Base64编码集);
}

public String 私钥() {
return 加解密操作.Base64编码_字节集(this.getPrivate().getEncoded(), 加解密操作.Base64编码集);
}

public RSA密钥对 创建RSA密钥对(int 密钥长度) {
try {
KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
keyPairGenerator.initialize(密钥长度);
return keyPairGenerator.generateKeyPair();
} catch (Exception e) {
}
return null;
}
}