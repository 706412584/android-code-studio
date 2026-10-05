package ticode.zh.jvm;

import java.util.*;
import java.util.regex.*;

import ticode.zh.base.文本;

public class 正则匹配器 extends java.util.regex.Matcher {
//将匹配到的内容全部替换
public String 全部替换(String 内容) {
return this.replaceAll(内容);
}

//匹配下一个文本
public boolean 匹配下一个() {
return this.find();
}

//获取匹配到的文本
public String 取匹配文本() {
return this.group();
}

//获取匹配到的文本开始的位置
public int 取匹配开始位置() {
return this.start();
}

//获取匹配到的文本结束的位置
public int 取匹配结束位置() {
return this.end();
}

//获取匹配到的文本的数量
public int 取子匹配数量() {
return this.groupCount();
}

//获取匹配到的某一个文本，参数为匹配到的文本的索引
public String 取子匹配文本(int 索引) {
return this.group(索引);
}
}