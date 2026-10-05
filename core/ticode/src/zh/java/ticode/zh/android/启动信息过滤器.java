package ticode.zh.android;

import android.os.Environment;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 启动信息过滤器 extends android.content.IntentFilter {

public void 置优先级(int 优先级) {
this.setPriority(优先级);
}

public int 取优先级() {
return this.getPriority();
}

public String 取动作(int 索引) {
return this.getAction(索引);
}

public String 取种类(int 索引) {
return this.getCategory(索引);
}

public String 取数据方案(int 索引) {
return this.getDataScheme(索引);
}

public String 取数据类型(int 索引) {
return this.getDataType(索引);
}

public void 添加动作(String 行动) {
this.addAction(行动);
}

public void 添加种类(String 类别) {
this.addCategory(类别);
}

public void 添加数据权限(String 主机名, String 端口) {
this.addDataAuthority(主机名,端口);
}

public void 添加数据路径(String 路径, int 类型) {
this.addDataPath(路径,类型);
}

public void 添加数据方案(String 方案) {
this.addDataScheme(方案);
}

public void 添加数据类型(String 类型) {
try {
this.addDataType(类型);
} catch (Exception e) { }
}

}