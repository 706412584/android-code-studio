package 结绳.安卓;


public class 安卓程序权限信息 extends 安卓程序包项目信息基础类 {

public static final int 标志_扣费 = 1;;

public static final int 标志_硬件限制 = 4;;

public static final int 标志_不可变限制 = 16;;

public static final int 标志_已安装 = 1073741824;;

public static final int 标志_软件限制 = 8;;

public boolean 等于_op(安卓程序权限信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序权限信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 描述资源id() {
return this.descriptionRes;
}

public void 描述资源id(int 描述资源id) {
this.descriptionRes = 描述资源id;
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public String 组名() {
return this.group;
}

public void 组名(String 组名) {
this.group = 组名;
}

public String 获取描述(安卓程序包管理器 管理器) {
return this.loadDescription(管理器).toString();
}

}


