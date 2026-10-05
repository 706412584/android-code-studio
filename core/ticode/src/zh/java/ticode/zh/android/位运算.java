package ticode.zh.android;

import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;

import ticode.zh.base.数字;

public class 位运算 {
//将两数进行位与运算，相当于 整数1&整数2
public static int 位与(int 整数1, int 整数2) {
return 整数1 & 整数2;
}

//将两数进行位或运算，相当于 整数1|整数2
public static int 位或(int 整数1, int 整数2) {
return 整数1 | 整数2;
}

//将两数进行位异或运算，相当于 整数1^整数2
public static int 位异或(int 整数1, int 整数2) {
return 整数1 ^ 整数2;
}

//进行为非运算，相当于 数 ^ -1
public static int 位非(int 数字) {
return 数字 ^ -1;
}

//将整数1进行左移运算，相当于 整数1 << 整数2
public static int 位左移(int 整数1, int 整数2) {
return 整数1 << 整数2;
}

//将整数1进行右移运算，相当于 整数1 >> 整数2
public static int 位右移(int 整数1, int 整数2) {
return 整数1 >> 整数2;
}

//将整数1进行无符号右移运算，相当于 整数1 >>> 整数2
public static int 无符号位右移(int 整数1, int 整数2) {
return 整数1 >>> 整数2;
}

}