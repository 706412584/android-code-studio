package ticode.base;

import android.os.*;
import java.util.concurrent.*;

public class JChar extends PrimitiveTemplate<CharBox> {
// 基础类型扩展方法：Java 无扩展方法，且 `char` 是原始类型，
// 实例方法体里的 `#this`（壳对象）无法当 char 用 → 改为静态 + 显式值参数。
public static boolean 为数字(char 值) {
return Character.isDigit(值);
}

public static boolean 为字母(char 值) {
return Character.isLetter(值);
}

public static boolean 为字母或数字(char 值) {
return Character.isLetterOrDigit(值);
}

public static boolean 为空格(char 值) {
return Character.isWhitespace(值);
}

public static boolean 为大写字母(char 值) {
return Character.isUpperCase(值);
}

public static boolean 为小写字母(char 值) {
return Character.isLowerCase(值);
}

public static char 到大写字母(char 值) {
return Character.toUpperCase(值);
}

public static char 到小写字母(char 值) {
return Character.toLowerCase(值);
}

public static int 到整数(char 值) {
return (int) 值;
}
}