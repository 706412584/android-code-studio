package ticode.zh.jvm;


import ticode.zh.base.整数类;

public class 坐标 {
Integer 横坐标;
Integer 纵坐标;

public void 赋值_op(int 横坐标, int 纵坐标) {
this.横坐标 = 横坐标;
this.纵坐标 = 纵坐标;
}




public 坐标 加_op(坐标 另一个坐标) {
int 新的横坐标 = 横坐标 + 另一个坐标.横坐标;
int 新的纵坐标 = 纵坐标 + 另一个坐标.纵坐标;
坐标 新坐标 = new 坐标(新的横坐标,新的纵坐标);
return (新坐标);
}




public 坐标 减_op(坐标 另一个坐标) {
int 新的横坐标 = 横坐标 - 另一个坐标.横坐标;
int 新的纵坐标 = 纵坐标 - 另一个坐标.纵坐标;
坐标 新坐标 = new 坐标(新的横坐标,新的纵坐标);
return (新坐标);
}

public String 到文本() {
return "("+横坐标+","+纵坐标+")";
}
}