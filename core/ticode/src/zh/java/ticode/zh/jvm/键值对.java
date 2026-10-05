package ticode.zh.jvm;


import static ticode.zh.android.文本操作.格式化文本;

public class 键值对 {
Object 键;
Object 值;

public 键值对 赋值_op(Object 键, Object 值) {
this.键 = 键;
this.值 = 值;
return this;
}

//格式: 键=值
public String 到文本() {
return 键 + "=" + 值;
}

//格式: (键,值)
public String 到文本2() {
return "("+键+","+值+")";
}








public String 到格式文本(String 输出格式) {
return 格式化文本(输出格式,new Object[]{键,值});
}

}