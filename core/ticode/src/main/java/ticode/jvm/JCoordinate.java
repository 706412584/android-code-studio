package ticode.jvm;


public class JCoordinate {
public Integer 横坐标;
public Integer 纵坐标;

public JCoordinate(int 横坐标, int 纵坐标) {
this.横坐标 = 横坐标;
this.纵坐标 = 纵坐标;

}




public JCoordinate 加_op(JCoordinate 另一个坐标) {
int 新的横坐标 = 横坐标 + 另一个坐标.横坐标;
int 新的纵坐标 = 纵坐标 + 另一个坐标.纵坐标;
JCoordinate 新坐标 = new JCoordinate(新的横坐标,新的纵坐标);
return (新坐标);
}




public JCoordinate 减_op(JCoordinate 另一个坐标) {
int 新的横坐标 = 横坐标 - 另一个坐标.横坐标;
int 新的纵坐标 = 纵坐标 - 另一个坐标.纵坐标;
JCoordinate 新坐标 = new JCoordinate(新的横坐标,新的纵坐标);
return (新坐标);
}

public String 到文本() {
return "("+横坐标+","+纵坐标+")";
}
}