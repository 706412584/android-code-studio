package ticode.zh.jvm;


public class 资源标识符 extends java.net.URI {
public Object 赋值_op(String URI文本) {
return java.net.URI.create(URI文本);
}





public String 协议名称() {
return this.getScheme();
}





public String 主机名() {
return this.getAuthority();
}





public String 主机地址() {
return this.getHost();
}





public int 主机端口() {
return this.getPort();
}





public String 路径() {
return this.getHost();
}





public String 片段() {
return this.getFragment();
}





public String 查询参数() {
return this.getQuery();
}
}