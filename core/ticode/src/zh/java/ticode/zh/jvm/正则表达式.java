package ticode.zh.jvm;

import java.util.*;
import java.util.regex.*;

import ticode.zh.base.文本;

public class 正则表达式 extends java.util.regex.Pattern {
public static final int 标志位_忽略大小写 = 0x02;
public static final int 标志位_允许注释 = 0x04;
public static final int 标志位_多行匹配 = 0x08;
public static final int 标志位_逐字的 = 0x10;
public static final int 标志位_任意字符 = 0x20;
public static final int 标志位_Unicode字符 = 0x40;
public static final int 标志位_等价替换 = 0x80;

//进行正则匹配，参数一为原文本，参数二为正则表达式，参数三为标志位
public static String[] 正则匹配(String 文本, String 表达式, int 标志位) {
Matcher mr = Pattern.compile(表达式, 标志位).matcher(文本);
List<String> list = new ArrayList<>();
while (mr.find()) {
list.add(mr.group());
}
return list.toArray(new String[list.size()]);
}

//进行正则匹配，参数一为原文本，参数二为正则表达式，参数三为标志位
public static java.util.List<String> 正则匹配2(String 文本, String 表达式, int 标志位) {
Matcher mr = Pattern.compile(表达式, 标志位).matcher(文本);
ArrayList<String> list = new ArrayList<>();
while (mr.find()) {
list.add(mr.group());
}
return list;
}

//编译一个正则表达式，得到正则表达式对象
public static 正则表达式 编译(String 表达式) {
return java.util.regex.Pattern.compile(表达式);
}

public String 表达式文本() {
return this.pattern();
}





public 正则匹配器 匹配(String 欲匹配文本) {
return this.matcher(欲匹配文本);
}





public String[] 分割(String 欲匹配文本) {
return this.split(欲匹配文本);
}
}