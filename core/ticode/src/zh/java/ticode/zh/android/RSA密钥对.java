package ticode.zh.android;

import java.security.KeyPairGenerator;
import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class RSA密钥对 {
public String 公钥() {return null; }

public String 私钥() {return null; }

public static java.security.KeyPair 创建RSA密钥对(int 密钥长度) {
try {
KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
keyPairGenerator.initialize(密钥长度);
return keyPairGenerator.generateKeyPair();
} catch (Exception e) {
}
return null;
}
}