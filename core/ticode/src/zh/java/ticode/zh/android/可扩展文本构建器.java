package ticode.zh.android;

import android.text.SpannableStringBuilder;
import android.text.SpannableString;

import ticode.zh.base.字符串;
import ticode.zh.base.文本;

public class 可扩展文本构建器 extends android.text.SpannableStringBuilder {

//字符串 包括 文本(String)
public void 赋值_op(字符串 初始字符串) {
return new SpannableStringBuilder(初始字符串);
}

public 可扩展文本构建器 加_op(Object 追加对象) {
return 追加对象(追加对象);
}

public boolean 等于_op(可扩展文本构建器 另一个构建器) {
if (this == null) {
return 另一个构建器 == null;
}
return this.equals(另一个构建器);
}

public char 取索引_op(int 索引) {
return 取字符(索引);
}

public 可扩展文本构建器 追加字符串(字符串 追加内容) {
return this.append(追加内容);
}

public 可扩展文本构建器 追加文本(String 追加内容) {
return this.append(追加内容);
}

public 可扩展文本构建器 追加逻辑值(boolean 追加内容) {
return this.append(String.valueOf(追加内容));
}

public 可扩展文本构建器 追加字符(char 追加内容) {
return this.append(追加内容);
}

public 可扩展文本构建器 追加整数(int 追加内容) {
return this.append(String.valueOf(追加内容));
}

public 可扩展文本构建器 追加长整数(long 追加内容) {
return this.append(String.valueOf(追加内容));
}

public 可扩展文本构建器 追加小数(double 追加内容) {
return this.append(String.valueOf(追加内容));
}
public 可扩展文本构建器 追加单精度小数(float 追加内容) {
return this.append(String.valueOf(追加内容));
}

public 可扩展文本构建器 追加对象(Object 追加内容) {
return this.append(String.valueOf(追加内容));
}

public 可扩展文本构建器 删除(int 起始位置, int 结束位置) {
return this.delete(起始位置,结束位置);
}

public 可扩展文本构建器 替换(int 被替换起始位置, int 被替换结束位置, String 替换内容) {
return this.replace(被替换起始位置,被替换结束位置,替换内容);
}

public 可扩展文本构建器 插入字符串(int 插入索引位置, 字符串 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 可扩展文本构建器 插入文本(int 插入索引位置, String 插入内容) {
return this.insert(插入索引位置,插入内容);
}

public 可扩展文本构建器 插入逻辑值(int 插入索引位置, boolean 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入字符(int 插入索引位置, char 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入整数(int 插入索引位置, int 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入长整数(int 插入索引位置, long 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入小数(int 插入索引位置, double 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入单精度小数(int 插入索引位置, float 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public 可扩展文本构建器 插入对象(int 插入索引位置, Object 插入内容) {
return this.insert(插入索引位置,String.valueOf(插入内容));
}

public void 清空() {
this.clear();
}

public void 设置到文本框(文本框 文本框组件) {
文本框组件.getView().setText(this);
}

}