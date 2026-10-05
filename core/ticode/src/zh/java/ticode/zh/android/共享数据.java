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

public class 共享数据 {
private static SharedPreferences sp;
private static SharedPreferences.Editor editor;

//初始化共享数据，在使用时必须先初始化，否则会报错，参数为储存名称，可随意
public static void 初始化(安卓环境 上下文, String 名称) {
sp = 上下文.getSharedPreferences(名称, Context.MODE_PRIVATE);
editor = sp.edit();
}

//取出之前设置的值，参数为值的名称，获取失败则返回默认值
public static String 取文本(String 键, String 默认值) {
return sp.getString(键, 默认值);
}

//将指定名称和值的数据写入私有目录
public static boolean 置文本(String 键, String 值) {
editor.putString(键, 值);
return editor.commit();
}

//将指定名称和值的数据写入私有目录
public static boolean 置整数(String 键, int 值) {
editor.putInt(键, 值);
return editor.commit();
}

//取出之前设置的值，参数为值的名称，获取失败则返回默认值
public static int 取整数(String 键, int 默认值) {
return sp.getInt(键, 默认值);
}

//将指定名称和值的数据写入私有目录
public static boolean 置逻辑值(String 键, boolean 值) {
editor.putBoolean(键, 值);
return editor.commit();
}

//取出之前设置的值，参数为值的名称，获取失败则返回假
public static boolean 取逻辑值(String 键, boolean 默认值) {
return sp.getBoolean(键, 默认值);
}

//判断共享数据是否包含某个数据
public static boolean 包含数据(String 键) {
return sp.contains(键);
}

public static boolean 清空() {
editor.clear();
return editor.commit();
}

}