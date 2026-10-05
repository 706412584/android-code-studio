package ticode.zh.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 字符 extends 基本类型模板类<字符类> {
//判断字符为数字
public boolean 为数字() {
return Character.isDigit(this);
}

//判断字符为字母
public boolean 为字母() {
return Character.isLetter(this);
}

//判断字符为字母或数字
public boolean 为字母或数字() {
return Character.isLetterOrDigit(this);
}

//判断字符为空格
public boolean 为空格() {
return Character.isWhitespace(this);
}

//判断字符为大写字母
public boolean 为大写字母() {
return Character.isUpperCase(this);
}

//判断字符为小写字母
public boolean 为小写字母() {
return Character.isLowerCase(this);
}

//转化字符为大写字母
public char 到大写字母() {
return Character.toUpperCase(this);
}

//转化字符为小写字母
public char 到小写字母() {
return Character.toLowerCase(this);
}

public int 到整数() {
return (int) this;
}
}