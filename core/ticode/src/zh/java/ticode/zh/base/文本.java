package ticode.zh.base;

import android.os.*;
import java.util.concurrent.*;

import ticode.zh.jvm.正则表达式;

public class 文本 {

public String 加_op(Object 欲相加对象) {return null; }




public boolean 等于_op(String 另一个文本) {return false; }




public boolean 不等于_op(String 另一个文本) {return false; }




public boolean 是_op(String 欲判断文本) {return false; }




public String 乘_op(int 数量) {return null; }




public char 取索引_op(int 索引) {return 0; }




public boolean 为空() {return false; }


public boolean 开头为(String 前缀, int 起始索引) {return false; }


public boolean 结尾为(String 后缀) {return false; }






public String 替换(String 欲替换内容, String 欲替换到内容) {return null; }






public int 寻找文本(String 寻找内容, int 开始位置) {return 0; }






//从末尾开始找一段文本，参数一是要寻找的文本，参数二是开始寻找的位置（-1低表文本末端）
public int 倒找文本(String 寻找内容, int 开始位置) {return 0; }






public String 截取文本(String 开始文本, String 结束文本, boolean 包含截取符) {return null; }

//将英文字母全部转化为大写
public String 到大写() {return null; }

//将英文字母全部转化为小写
public String 到小写() {return null; }

//取文本左边一段内容，参数一为要截取的长度
public String 取文本左边(int 长度) {return null; }

//取文本右边一段内容，参数一为开始截取的索引
public String 取文本右边(int 起始索引) {return null; }

//取文本右边一段内容，参数一为要截取的长度
public String 取文本右边_长度(int 长度) {return null; }






public String 取文本中间(int 开始索引位置, int 结束索引位置) {return null; }

//删除文本首尾处空字符
public String 删首尾空() {return null; }

//将文本翻转排序(倒置)
public String 翻转文本() {return null; }

//分割一段文本，参数一为作为分割符号的文本
public String[] 分割文本(String 分割符) {return null; }





public int 到整数(int 进制) {return 0; }





public long 到长整数(int 进制) {return 0L; }

//将当前文本转换为小数值
public double 到小数() {return 0; }

//将当前文本转换为单精度小数值
public float 到单精度小数() {return 0; }

//将当前文本转换为逻辑值
public boolean 到逻辑值() {return false; }

public byte[] 到字节集(String 编码) {return null; }

public char[] 到字符集() {return null; }

public java.util.regex.Pattern 创建正则表达式() {return null; }

public String 正则替换(String 欲替换匹配表达式, String 欲替换到内容, boolean 只替换首个匹配) {return null; }

//判断当前文本是否匹配指定的正则表达式
public boolean 是否匹配表达式(String 正则表达式) {return false; }

public static String 拼接文本数组(String[] 文本数组, String 拼接符) {
return String.join(拼接符,文本数组);
}

public static String 拼接文本集合(java.util.List<String> 文本集合1, String 拼接符) {
return String.join(拼接符,文本集合1);
}

public static String 从字节集创建(byte[] 字节集, String 编码) {
try {
return new String(字节集, 编码);
} catch (Exception e) {
return new String(字节集);
}
}

public static String 从字符集创建(char[] 字符集) {
return new String(字符集);
}









public static String 格式化(String 格式, Object[] 参数) {
return String.format(格式, 参数);
}
}