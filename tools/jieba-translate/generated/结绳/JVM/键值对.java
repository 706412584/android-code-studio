package 结绳.JVM;


public class 键值对 {
Object 键;
Object 值;

public void 赋值_op(Object 键, Object 值) {
this.键 = 键;
this.值 = 值;
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




