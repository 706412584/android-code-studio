package ticode.zh.base;

import java.io.PrintWriter;
import java.io.StringWriter;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.正则表达式;

public class 文本构建器 extends java.lang.StringBuilder {

public void 赋值_op(CharSequence 初始字符串) {
return new StringBuilder(初始字符串);
}

public 文本构建器 加_op(Object 追加对象) {
return 追加对象(追加对象);
}

public boolean 等于_op(文本构建器 另一个构建器) {
if (this == null) {
return 另一个构建器 == null;
}
return this.equals(另一个构建器);
}

public boolean 是_op(String 欲判断文本) {
return this.indexOf(欲判断文本) != -1;
}

public char 取索引_op(int 索引) {
return 取字符(索引);
}

public void 设索引_op(int 索引, char 设置字符) {
设置字符(索引,设置字符);
}

public 文本构建器 追加字符串(CharSequence 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加文本(String 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加逻辑值(boolean 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加字符(char 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加字符集(char[] 追加内容, int 起始偏移量, int 长度) {
if (长度 == -1) {
长度 = 取数组长度(追加内容);
}
return this.append(追加内容, 起始偏移量, 长度);
}

public 文本构建器 追加整数(int 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加长整数(long 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加小数(double 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加单精度小数(float 追加内容) {
return this.append(追加内容);
}

public 文本构建器 追加对象(Object 追加内容) {
return this.append(追加内容);
}

public 文本构建器 删除(int 起始位置, int 结束位置) {
return this.delete(起始位置,结束位置);
}

public 文本构建器 从索引删除(int 索引) {
return this.deleteCharAt(索引);
}

public 文本构建器 替换(int 被替换起始位置, int 被替换结束位置, String 替换内容) {
return this.replace(被替换起始位置,被替换结束位置,替换内容);
}

public 文本构建器 插入字符串(int 插入索引位置, CharSequence 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入文本(int 插入索引位置, String 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入逻辑值(int 插入索引位置, boolean 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入字符(int 插入索引位置, char 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入字符集(int 插入索引位置, char[] 追加内容, int 起始偏移量, int 长度) {
if (长度 == -1) {
长度 = 取数组长度(追加内容);
}
return this.insert(插入索引位置,追加内容,起始偏移量,长度);
}

public 文本构建器 插入整数(int 插入索引位置, int 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入长整数(int 插入索引位置, long 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入小数(int 插入索引位置, double 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入单精度小数(int 插入索引位置, float 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 文本构建器 插入对象(int 插入索引位置, Object 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public int 寻找文本(String 寻找内容, int 开始位置) {
return this.indexOf(寻找内容,开始位置);
}

public int 倒找文本(String 寻找内容, int 开始位置) {
if (开始位置 == -1) {
开始位置 = 长度() - 1;
}
return this.lastIndexOf(寻找内容,开始位置);
}

public 文本构建器 翻转内容() {
return this.reverse();
}

public void 设置字符(int 索引, char 设置字符) {
this.setCharAt(索引,设置字符);
}

public void 设置新长度(int 新长度) {
this.setLength(新长度);
}

public void 清空() {
设置新长度(0);
}

}