package ticode.android;


public class PermissionInfo {
//允许程序访问网络
public static final String 网络权限 = "android.permission.INTERNET";

//允许程序访问SD卡中的内容
public static final String 文件权限_读取 = "android.permission.READ_EXTERNAL_STORAGE";

//允许程序修改SD卡中的内容
public static final String 文件权限_写入 = "android.permission.WRITE_EXTERNAL_STORAGE";

//管理外部文件权限
public static final String 管理外部文件权限 = "android.permission.MANAGE_EXTERNAL_STORAGE";

//允许程序使用摄像头拍摄照片或视频
public static final String 拍摄权限 = "android.permission.CAMERA";

//允许程序安装应用
public static final String 安装应用权限 = "android.permission.INSTALL_PACKAGES";

//允许程序卸载应用
public static final String 卸载应用权限 = "android.permission.DELETE_PACKAGES";

//允许程序在系统完成引导后自启动
public static final String 自启动权限 = "android.permission.RECEIVE_BOOT_COMPLETED";

//允许程序监听卸载应用，有应用被卸载时，程序将会收到广播
public static final String 监听卸载权限 = "android.permission.BROADCAST_PACKAGE_REMOVED";

//允许程序在其他应用界面的上方绘图
public static final String 悬浮窗权限 = "android.permission.SYSTEM_ALERT_WINDOW";

//允许程序挂载或反挂载可移动文件系统，例如：挂载SD卡
public static final String 挂载存储权限 = "android.permission.MOUNT_UNMOUNT_FILESYSTEMS";

//允许程序格式化可移动文件系统，例如：格式化SD卡
public static final String 格式化存储权限 = "android.permission.MOUNT_FORMAT_FILESYSTEMS";

//允许程序管理文件
public static final String 管理文件权限 = "android.permission.MANAGE_DOCUMENTS";

//允许程序删除缓存文件
public static final String 删除缓存权限 = "android.permission.DELETE_CACHE_FILES";

//允许程序改变网络连接状态
public static final String 改变网络权限 = "android.permission.CHANGE_NETWORK_STATE";

//允许程序获取网络信息
public static final String 网络信息权限 = "android.permission.ACCESS_NETWORK_STATE";

//允许获取当前WiFi接⼊的状态以及WLAN热点的信息
public static final String WIFI信息权限 = "android.permission.ACCESS_WIFI_STATE";

//允许程序改变当前WIFI的连接状态
public static final String 改变WIFI权限 = "android.permission.CHANGE_WIFI_STATE";

//允许程序连接到已配对的蓝牙
public static final String 蓝牙权限_连接 = "android.permission.BLUETOOTH";

//允许程序发现和配对蓝牙
public static final String 蓝牙权限_配对 = "android.permission.BLUETOOTH_ADMIN";

//允许程序配对蓝牙，不需要与用户交互
public static final String 蓝牙权限_配对_不与用户交互 = "android.permission.BLUETOOTH_PRIVILEGED";

//允许程序定位
public static final String 定位权限 = "android.permission.ACCESS_FINE_LOCATION";

//允许程序读取短信内容
public static final String 短信权限_读取 = "android.permission.READ_SMS";

//允许程序编写短信
public static final String 短信权限_编辑 = "android.permission.WRITE_SMS";

//允许程序发送短信
public static final String 短信权限_发送 = "android.permission.SEND_SMS";

//允许程序监听接收短信
public static final String 短信权限_监听 = "android.permission.RECEIVE_SMS";

//允许程序接听来电
public static final String 电话权限_接听 = "android.permission.ANSWER_PHONE_CALLS";

//允许程序拨打电话
public static final String 电话权限_拨号 = "android.permission.CALL_PHONE";

//允许程序自定义拨号界面
public static final String 电话权限_自定义拨号 = "android.permission.CALL_PRIVILEGED";

//允许程序访问电话状态
public static final String 电话权限_访问状态 = "android.permission.READ_PHONE_STATE";

//允许程序读取通话记录
public static final String 电话权限_读取通话记录 = "android.permission.READ_CALL_LOG";

//允许程序改变电话状态
public static final String 电话权限_改变状态 = "android.permission.MODIFY_PHONE_STATE";

//允许程序修改系统时间
public static final String 系统权限_修改时间 = "android.permission.SET_TIME";

//允许程序读取日历数据
public static final String 系统权限_读取日历 = "android.permission.READ_CALENDAR";

//允许程序设置桌面壁纸
public static final String 壁纸权限 = "android.permission.SET_WALLPAPER";

//允许程序录制屏幕
public static final String 录制屏幕权限 = "android.permission.ACCESS_SURFACE_FLINGER";

//允许程序屏幕截图
public static final String 屏幕截图权限 = "android.permission.READ_FRAME_BUFFER";

//允许程序使用麦克风录制音频
public static final String 录音权限 = "android.permission.RECORD_AUDIO";

//允许程序修改系统设置
public static final String 系统权限_修改系统设置 = "android.permission.WRITE_SETTINGS";

//允许程序创建快捷方式
public static final String 创建快捷方式权限 = "android.permission.INSTALL_SHORTCUT";

//允许程序删除快捷方式
public static final String 删除快捷方式权限 = "android.permission.UNINSTALL_SHORTCUT";

//允许程序开启/关闭闪光灯
public static final String 闪光灯权限 = "android.permission.FLASHLIGHT";

//允许程序控制设备震动
public static final String 震动权限 = "android.permission.VIBRATE";

//允许程序使用红外设备
public static final String 红外线权限 = "android.permission.TRANSMIT_IR";

//允许程序使用NFC
public static final String NFC权限 = "android.permission.NFC";

//允许程序获取电池信息
public static final String 电池信息权限 = "android.permission.BATTERY_STATS";

}