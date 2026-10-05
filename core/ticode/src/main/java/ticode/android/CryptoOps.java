package ticode.android;

import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.KeyPairGenerator;

import ticode.base.TextBox;

public class CryptoOps {
public static final String Base64编码集 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";





public static String MD5加密(String 值, String 编码) {
char hexDigits[] = { '0', '1', '2', '3', '4','5', '6', '7',
'8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
try {
byte[] btInput = 值.getBytes(编码);
MessageDigest mdInst = MessageDigest.getInstance("MD5");
mdInst.update(btInput);
byte[] md = mdInst.digest();
int j = md.length;
char str[] = new char[j * 2];
int k = 0;
for (int i = 0; i < j; i++) {
byte byte0 = md[i];
str[k++] = hexDigits[byte0 >>> 4 & 0xf];
str[k++] = hexDigits[byte0 & 0xf];
}
return new String(str);
} catch (Exception e) {
e.printStackTrace();
return null;
}
}






public static String SHA加密(String 值, String 编码) {
try {
MessageDigest sha = MessageDigest.getInstance("SHA");
sha.update(值.getBytes(编码));
return new String(sha.digest(), 编码);
} catch (Exception e) {
return null;
}
}








public static String Base64编码(String 欲编码内容, String 编码, String 编码集) {
Object 字节集 = 欲编码内容.到字节集(编码);
return Base64编码_字节集(字节集, 编码集);
}







public static String Base64解码(String 欲解码内容, String 编码, String 编码集) {
Object 字节集 = Base64解码_字节集(欲解码内容, 编码集);
return TextBox.从字节集创建(字节集, 编码);
}






public static String Base64编码_字节集(byte[] 欲编码字节集, String 编码集) {
String add = "=";
StringBuilder base64Str = new StringBuilder();
String bytesBinary = Base64_到二进制(欲编码字节集, 2);
int addCount = 0;
while (bytesBinary.length() % 24 != 0) {
bytesBinary += "0";
addCount++;
}
for (int i = 0; i <= bytesBinary.length() - 6; i += 6) {
int index = Integer.parseInt(bytesBinary.substring(i, i + 6), 2);
if (index == 0 && i >= bytesBinary.length() - addCount) {
base64Str.append(add);
} else {
base64Str.append(编码集.charAt(index));
}
}
return base64Str.toString();
}






public static byte[] Base64解码_字节集(String 欲解码内容, String 编码集) {
String base64Binarys = "";
for (int i = 0; i < 欲解码内容.length(); i++) {
char s = 欲解码内容.charAt(i);
if (s != '=') {
String binary = Integer.toBinaryString(编码集.indexOf(s));
while (binary.length() != 6) {
binary = "0" + binary;
}
base64Binarys += binary;
}
}
base64Binarys = base64Binarys.substring(0, base64Binarys.length() - base64Binarys.length() % 8);
byte[] bytesStr = new byte[base64Binarys.length() / 8];
for (int bytesIndex = 0; bytesIndex < base64Binarys.length() / 8; bytesIndex++) {
bytesStr[bytesIndex] = (byte) Integer.parseInt(base64Binarys.substring(bytesIndex * 8, bytesIndex * 8 + 8), 2);
}
return bytesStr;
}

public static String Base64_到二进制(byte[] 字节集, int 进制) {
String strBytes = new BigInteger(1, 字节集).toString(进制);
while (strBytes.length() % 8 != 0) {
strBytes = "0" + strBytes;
}
return strBytes;
}






public static String RC4加密(String 值, String 密码, String 编码) {
if ((值 == null) || (密码 == null))
return null;
try {
byte[] a = RC4Base(值.getBytes(编码), 密码, 编码);
char[] hexDigits = new Object[]{ '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
int j = a.length;
char[] str = new char[j * 2];
int k = 0;
for (int i = 0; i < j; i++) {
byte byte0 = a[i];
str[(k++)] = hexDigits[(byte0 >>> 4 & 0xF)];
str[(k++)] = hexDigits[(byte0 & 0xF)];
}
return new String(str);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}






public static String RC4解密(String 值, String 密码, String 编码) {
if ((值 == null) || (密码 == null))
return null;
try {
return new String(RC4Base(HexString2Bytes(值, 编码), 密码, 编码), 编码);
} catch (Exception e) {
e.printStackTrace();
}
return null;
}







public static String AES加密(String 值, String 密码, String 编码) {
if ((值 == null) || (密码 == null))
return null;
try {
byte[] contentBytes = 值.getBytes(编码);
byte[] keyBytes = 密码.getBytes(编码);
int keyLen = keyBytes.length;
if (keyLen <= 16) {
keyLen = 16;
} else if (keyLen <= 24) {
keyLen = 24;
} else {
keyLen = 32;
}
keyBytes = Arrays.copyOf(keyBytes, keyLen);
Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
cipher.init(Cipher.ENCRYPT_MODE, secretKey);
return CryptoOps.Base64编码_字节集(cipher.doFinal(contentBytes), CryptoOps.Base64编码集);
} catch (Exception e) {
e.printStackTrace();
}
return "";
}







public static String AES解密(String 值, String 密码, String 编码) {
if ((值 == null) || (密码 == null))
return null;
try {
byte[] contentBytes = CryptoOps.Base64解码_字节集(值, CryptoOps.Base64编码集);
byte[] keyBytes = 密码.getBytes(编码);
int keyLen = keyBytes.length;
if (keyLen <= 16) {
keyLen = 16;
} else if (keyLen <= 24) {
keyLen = 24;
} else {
keyLen = 32;
}
keyBytes = Arrays.copyOf(keyBytes, keyLen);
Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
cipher.init(Cipher.DECRYPT_MODE, secretKey);
return new String(cipher.doFinal(contentBytes), 编码);
} catch (Exception e) {
e.printStackTrace();
}
return "";
}






public static String RSA加密(String 值, String 公钥, String 编码) {
if ((值 == null) || (公钥 == null))
return null;
try {
byte[] contentBytes = 值.getBytes(编码);
byte[] keyBytes = CryptoOps.Base64解码_字节集(公钥, CryptoOps.Base64编码集);
Cipher cipher = Cipher.getInstance("RSA");
PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
cipher.init(Cipher.ENCRYPT_MODE, publicKey);
return CryptoOps.Base64编码_字节集(cipher.doFinal(contentBytes), CryptoOps.Base64编码集);
} catch (Exception e) {
e.printStackTrace();
}
return "";
}






public static String RSA解密(String 值, String 私钥, String 编码) {
if ((值 == null) || (私钥 == null))
return null;
try {
byte[] contentBytes = CryptoOps.Base64解码_字节集(值, CryptoOps.Base64编码集);
byte[] keyBytes = CryptoOps.Base64解码_字节集(私钥, CryptoOps.Base64编码集);
Cipher cipher = Cipher.getInstance("RSA");
PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
cipher.init(Cipher.DECRYPT_MODE, privateKey);
return new String(cipher.doFinal(contentBytes), 编码);
} catch (Exception e) {
e.printStackTrace();
}
return "";
}






public static String DES加密(String 值, String 密钥, String 编码) {
String content = 值;
String key = 密钥;
if (content == null || content.isEmpty() ||
key == null || key.isEmpty()) return null;
try {
byte[] contentBytes = content.getBytes(编码);
byte[] keyBytes = key.getBytes(编码);
keyBytes = Arrays.copyOf(keyBytes, 8);
Cipher cipher = Cipher.getInstance("DES");
DESKeySpec dks = new DESKeySpec(keyBytes);
SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DES");
SecretKey secretKey = keyFactory.generateSecret(dks);
cipher.init(Cipher.ENCRYPT_MODE, secretKey);
return Base64编码_字节集(cipher.doFinal(contentBytes), Base64编码集);
} catch (Exception e) {
e.printStackTrace();
return "";
}
}






public static String DES解密(String 值, String 密钥, String 编码) {
String content = 值;
String key = 密钥;
if (content == null || content.isEmpty() ||
key == null || key.isEmpty()) return null;
try {
byte[] contentBytes = Base64解码_字节集(content, Base64编码集);
byte[] keyBytes = key.getBytes(编码);
keyBytes = Arrays.copyOf(keyBytes, 8);
Cipher cipher = Cipher.getInstance("DES");
DESKeySpec dks = new DESKeySpec(keyBytes);
SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DES");
SecretKey secretKey = keyFactory.generateSecret(dks);
cipher.init(Cipher.DECRYPT_MODE, secretKey);
return new String(cipher.doFinal(contentBytes), 编码);
} catch (Exception e) {
e.printStackTrace();
return "";
}
}

public static byte[] HexString2Bytes(String 内容, String 编码) {
try {
int size = 内容.length();
byte[] ret = new byte[size / 2];
byte[] tmp = 内容.getBytes(编码);
for (int i = 0; i < size / 2; i++) {
ret[i] = uniteBytes(tmp[(i * 2)], tmp[(i * 2 + 1)]);
}
return ret;
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static byte uniteBytes(byte 字节1, byte 字节2) {
char _b0 = (char)Byte.decode("0x" + new String(new byte[] { 字节1 })).byteValue();
_b0 = (char)(_b0 << '\004');
char _b1 = (char)Byte.decode("0x" + new String(new byte[] { 字节2 })).byteValue();
byte ret = (byte)(_b0 ^ _b1);
return ret;
}

public static byte[] RC4Base(byte[] 字节集, String 密码, String 编码) {
int x = 0;
int y = 0;
byte[] key = initKey(密码, 编码);

byte[] result = new byte[字节集.length];
for (int i = 0; i < 字节集.length; i++) {
x = x + 1 & 0xFF;
y = (key[x] & 0xFF) + y & 0xFF;
byte tmp = key[x];
key[x] = key[y];
key[y] = tmp;
int xorIndex = (key[x] & 0xFF) + (key[y] & 0xFF) & 0xFF;
result[i] = ((byte)(字节集[i] ^ key[xorIndex]));
}
return result;
}

public static byte[] initKey(String 密码, String 编码) {
try {
byte[] b_key = 密码.getBytes(编码);
byte[] state = new byte[256];

for (int i = 0; i < 256; i++) {
state[i] = ((byte)i);
}
int index1 = 0;
int index2 = 0;
if ((b_key == null) || (b_key.length == 0)) {
return null;
}
for (int i = 0; i < 256; i++) {
index2 = (b_key[index1] & 0xFF) + (state[i] & 0xFF) + index2 & 0xFF;
byte tmp = state[i];
state[i] = state[index2];
state[index2] = tmp;
index1 = (index1 + 1) % b_key.length;
}
return state;
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

}