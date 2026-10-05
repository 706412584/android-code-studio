package 结绳.安卓;


public class 附加资源管理器 {
//打开指定文件输入流
public 输入流 打开文件(String 文件名) {
容错处理();
return this.open(文件名);
结束容错();
return null;
}
}