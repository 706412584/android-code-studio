package ticode.zh.android;

import android.content.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.math.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.KeyPairGenerator;

import ticode.zh.base.数字;
import ticode.zh.base.文本;

public class Java代码扩展 {
//等价于 条件 ? 为真输出 : 为假输出
public static Object 三元判断(boolean 条件, Object 为真输出, Object 为假输出) {
return 条件 ? 为真输出 : 为假输出;
}

//等价于 赋值变量 = 条件 ? 为真赋值 : 为假赋值
public static Object 三元判断赋值(Object 目标变量, boolean 条件, Object 为真赋值, Object 为假赋值) {
目标变量 = 条件 ? 为真赋值 : 为假赋值;
}

}