package ticode.zh.android;

import android.os.Build;

public class 设备信息 {
public static final Integer 安卓版本号;

public static final String 主板信息;

public static final String 系统启动程序版本号;

public static final String 品牌;

public static final String CPU指令集;

public static final String CPU指令集2;

public static final String 设备参数;

public static String 显示屏参数;

public static final String 唯一识别码;

public static final String 硬件名称;

public static final String 硬件制造商;

public static final String 硬件序列号;

public static final String 用户可见名称;

public static final String 产品名称;

public static final String 无线电固件版本;

static {
安卓版本号 = Build.VERSION.SDK_INT;
主板信息 = Build.BOARD;
系统启动程序版本号 = Build.BOOTLOADER;
品牌 = Build.BRAND;
CPU指令集 = Build.CPU_ABI;
CPU指令集2 = Build.CPU_ABI2;
设备参数 = Build.DEVICE;
显示屏参数 = Build.DISPLAY;
唯一识别码 = Build.FINGERPRINT;
硬件名称 = Build.HARDWARE;
硬件制造商 = Build.MANUFACTURER;
硬件序列号 = Build.SERIAL;
用户可见名称 = Build.MODEL;
产品名称 = Build.PRODUCT;
无线电固件版本 = Build.RADIO;
}
}