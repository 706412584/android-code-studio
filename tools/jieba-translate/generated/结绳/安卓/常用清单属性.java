package 结绳.安卓;


public class 常用清单属性 {
//开启硬件加速，更快渲染图形
public static final String 开启硬件加速 = "android:hardwareAccelerated=\"true\"";

//允许本程序窗口尺寸被系统更改
public static final String 允许更改窗口大小 = "android:resizeableActivity=\"true\"";

//禁止本程序与其他程序一起运行
public static final String 禁止更改窗口大小 = "android:resizeableActivity=\"false\"";

//允许本程序被调试
public static final String 允许调试 = "android:debuggable=\"true\"";

//允许本程序备份数据
public static final String 允许备份 = "android:allowBackup=\"true\"";

//申请更大的运行内存，安卓应用默认运行内存限制为192M，附加本属性后将变为512M
public static final String 申请更大内存 = "android:largeHeap=\"true\"";
}