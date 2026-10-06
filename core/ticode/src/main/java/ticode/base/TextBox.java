package ticode.base;

import android.os.*;
import java.util.concurrent.*;

import ticode.jvm.JRegex;

public class TextBox extends String {

public String 加_op(Object 欲相加对象) {
return this + 欲相加对象;
}




public boolean 等于_op(String 另一个文本) {
if (this == null) {
return 另一个文本 == null;
}
return this.equals(另一个文本);
}




public boolean 不等于_op(String 另一个文本) {
if (this == null) {
return 另一个文本 != null;
}
return !this.equals(另一个文本);
}




public boolean 是_op(String 欲判断文本) {
return this.contains(欲判断文本);
}




public String 乘_op(int 数量) {
String result = "";
for (int i = 0;i < 数量;i++) {
result += this;
}
return result;
}




public char 取索引_op(int 索引) {
return 取字符(索引);
}




public boolean 为空() {
return this == null || this.isEmpty();
}


public boolean 开头为(String 前缀, int 起始索引) {
return this.startsWith(前缀, 起始索引);
}


public boolean 结尾为(String 后缀) {
return this.endsWith(后缀);
}






public String 替换(String 欲替换内容, String 欲替换到内容) {
return this.replace(欲替换内容,欲替换到内容);
}






public int 寻找文本(String 寻找内容, int 开始位置) {
if (开始位置 < 0 || 开始位置 > this.length() || "".equals(this) || "".equals(寻找内容)) {
return -1;
}
return this.indexOf(寻找内容, 开始位置);
}






//从末尾开始找一段文本，参数一是要寻找的文本，参数二是开始寻找的位置（-1低表文本末端）
public int 倒找文本(String 寻找内容, int 开始位置) {
if (开始位置 > this.length()+1 || "".equals(this) || "".equals(寻找内容)) {
return -1;
}
if (开始位置 == -1){
开始位置 = this.length();
}
return this.lastIndexOf(寻找内容,开始位置);
}






public String 截取文本(String 开始文本, String 结束文本, boolean 包含截取符) {
if (开始文本 == "" && 结束文本 == ""){
return "";
}

int left = this.indexOf(开始文本);
if (left == -1) {
return "";
}

if ("".equals(结束文本)) {
if (包含截取符) {
return this.substring(left);
} else {
return this.substring(left + 开始文本.length());
}
}

int right = this.indexOf(结束文本, left + 开始文本.length());
if (right == -1) {
return "";
}
String temp;
if (包含截取符) {
temp = 取文本中间(this, left, right + 结束文本.length() - 1);
} else {
temp = 取文本中间(this, left + 开始文本.length(), right - 1);
}
return temp;
}

//将英文字母全部转化为大写
public String 到大写() {
return this.toUpperCase();
}

//将英文字母全部转化为小写
public String 到小写() {
return this.toLowerCase();
}

//取文本左边一段内容，参数一为要截取的长度
public String 取文本左边(int 长度) {
if ("".equals(this) || 长度 <= 0) {
return "";
}
return 长度 <= this.length() ? this.substring(0, 长度) : this;
}

//取文本右边一段内容，参数一为开始截取的索引
public String 取文本右边(int 起始索引) {
if ("".equals(this) || 起始索引 < 0) {
return "";
}
return this.substring(起始索引, this.length());
}

//取文本右边一段内容，参数一为要截取的长度
public String 取文本右边_长度(int 长度) {
if ("".equals(this) || 长度 < 0) {
return "";
}
return this.substring(this.length() - 长度, this.length());
}






public String 取文本中间(int 开始索引位置, int 结束索引位置) {
return this.substring(开始索引位置, 结束索引位置 + 1);
}

//删除文本首尾处空字符
public String 删首尾空() {
return this.trim();
}

//将文本翻转排序(倒置)
public String 翻转文本() {
return new StringBuffer(this).reverse().toString();
}

//分割一段文本，参数一为作为分割符号的文本
public String[] 分割文本(String 分割符) {
return this.split(分割符);
}





public int 到整数(int 进制) {
return Integer.parseInt(this, 进制);
}





public long 到长整数(int 进制) {
return Long.parseLong(this, 进制);
}

//将当前文本转换为小数值
public double 到小数() {
return Double.parseDouble(this);
}

//将当前文本转换为单精度小数值
public float 到单精度小数() {
return Float.parseFloat(this);
}

//将当前文本转换为逻辑值
public boolean 到逻辑值() {
return Boolean.parseBoolean(this);
}

public byte[] 到字节集(String 编码) {
try {
return this.getBytes(编码);
} catch (Exception e) {
throw new RuntimeException("文本到字节集编码错误：" + 编码);
}
}

public char[] 到字符集() {
return this.toCharArray();
}

public JRegex 创建正则表达式() {
return java.util.regex.Pattern.compile(this);
}

public String 正则替换(String 欲替换匹配表达式, String 欲替换到内容, boolean 只替换首个匹配) {
if (只替换首个匹配) {
return this.replaceFirst(欲替换匹配表达式,欲替换到内容);
} else {
return this.replaceAll(欲替换匹配表达式,欲替换到内容);
}
}

//判断当前文本是否匹配指定的正则表达式
public boolean 是否匹配表达式(String 正则表达式) {
return this.matches(正则表达式);
}

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
public int 长度() {
return this.length();
}
public char 取字符(int 索引) {
return this.charAt(索引);
}
}