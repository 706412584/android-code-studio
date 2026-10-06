package ticode.zh.android;


public class 样式_删除线 extends android.text.style.StrikethroughSpan {
// 具体化后必须显式调 super()：父类（Path/Paint/…）有 native 字段，
// 不调则实例未初始化，一用就 SIGSEGV。
public 样式_删除线() { super(); }

public static 样式_删除线 取实例() {
return new 样式_删除线();
}
}