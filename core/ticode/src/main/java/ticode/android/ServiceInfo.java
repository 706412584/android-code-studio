package ticode.android;


public class ServiceInfo extends android.content.pm.ServiceInfo {

public static final int 标志_外部服务 = 4;;

public static final int 标志_独立进程 = 2;;

public static final int 标志_单例 = 1073741824;;

public static final int 标志_自动停止任务 = 1;;

public static final int 标志_使用Zygote = 8;;

public static final int 前台服务类型_相机 = 64;;

public static final int 前台服务类型_连接设备 = 16;;

public static final int 前台服务类型_数据同步 = 1;;

public static final int 前台服务类型_位置 = 8;;

public static final int 前台服务类型_使用清单中类型 = -1;;

public static final int 前台服务类型_音频播放 = 2;;

public static final int 前台服务类型_屏幕录制截屏 = 32;;

public static final int 前台服务类型_录制音频 = 128;;

public static final int 前台服务类型_默认 = 0;;

public static final int 前台服务类型_通话 = 4;;

public boolean 等于_op(ServiceInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(ServiceInfo 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public String 权限() {
return this.permission;
}

public void 权限(String 权限) {
this.permission = 权限;
}

public int 获取前台服务类型() {
return this.getForegroundServiceType();
}

public AppInfo 应用信息() {
return this.applicationInfo;
}
public int 描述资源id() {
return this.descriptionRes;
}
public boolean 可实例化() {
return this.enabled;
}
public boolean 可被外部调用() {
return this.exported;
}
public String 进程名称() {
return this.processName;
}
public String 拆分名称() {
return this.splitName;
}
}