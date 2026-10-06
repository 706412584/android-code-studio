# -*- coding: utf-8 -*-
"""修 API 级别不匹配：用 compileSdk 36 编译通过、但在低版本设备上
NoSuchMethodError / NoSuchFieldError / 类加载失败。

用 android-28 jar 编译 ticode 定位到 14 处（compileSdk 36 看不出来）。
10 处在 SDK 29 设备上真崩（API 30+），4 处在 minSdk 24 设备上崩。
"""
import io, os, re, subprocess

REPO = 'D:/android/projecet_iade/android-code-studio'
SRC = REPO + '/core/ticode/src/zh/java'

P = [
    # 1) 安卓窗口：Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (API 30)
    ('ticode/zh/android/安卓窗口.java',
     'android.content.Intent it = new android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);',
     '// ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 是 API 30 常量，低版本无此字段。\n'
     '// 该值就是其字符串字面量，直接用；上面已有 <30 的运行时守卫。\n'
     'android.content.Intent it = new android.content.Intent(\n'
     '"android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");'),

    # 2) WiFi信息：4 个 API 29/30 方法 → 反射
    ('ticode/zh/android/WiFi信息.java',
     '''public int 发送速率() {
return this.内部对象.getTxLinkSpeedMbps();
}''',
     '''public int 发送速率() {
return 反射取速率("getTxLinkSpeedMbps");
}'''),
    ('ticode/zh/android/WiFi信息.java',
     '''public int 接收速率() {
return this.内部对象.getRxLinkSpeedMbps();
}''',
     '''public int 接收速率() {
return 反射取速率("getRxLinkSpeedMbps");
}'''),
    ('ticode/zh/android/WiFi信息.java',
     'return this.内部对象.getPasspointFqdn();',
     'return 反射取文本("getPasspointFqdn");'),
    ('ticode/zh/android/WiFi信息.java',
     'return this.内部对象.getPasspointProviderFriendlyName();',
     'return 反射取文本("getPasspointProviderFriendlyName");'),
    ('ticode/zh/android/WiFi信息.java',
     '''private int 反射取速率(String 方法名) {''',
     '''private String 反射取文本(String 方法名) {
try {
java.lang.reflect.Method m = android.net.wifi.WifiInfo.class.getMethod(方法名);
Object r = m.invoke(this.内部对象);
return r instanceof String ? (String) r : null;
} catch (Throwable t) {
return null;
}
}

private int 反射取速率(String 方法名) {'''),

    # 3) 位图压缩格式：WEBP_LOSSY / WEBP_LOSSLESS (API 30)
    ('ticode/zh/android/位图压缩格式.java',
     '''public static final android.graphics.Bitmap.CompressFormat WEBP_有损 = CompressFormat.WEBP_LOSSY;

public static final android.graphics.Bitmap.CompressFormat WEBP_无损 = CompressFormat.WEBP_LOSSLESS;

}''',
     '''// WEBP_LOSSY / WEBP_LOSSLESS 是 API 30 枚举常量；低版本类加载时静态初始化会
// NoSuchFieldError。用反射取，缺失则回退到 WEBP。
public static final android.graphics.Bitmap.CompressFormat WEBP_有损 = 压缩格式("WEBP_LOSSY");

public static final android.graphics.Bitmap.CompressFormat WEBP_无损 = 压缩格式("WEBP_LOSSLESS");

private static android.graphics.Bitmap.CompressFormat 压缩格式(String 名) {
try {
return (android.graphics.Bitmap.CompressFormat)
android.graphics.Bitmap.CompressFormat.class.getField(名).get(null);
} catch (Throwable t) {
return CompressFormat.WEBP;
}
}
}'''),

    # 4) 安卓程序包信息：isApex 字段 (API 29)
    ('ticode/zh/android/安卓程序包信息.java',
     '''public boolean 是apex包() {
return this.isApex;
}

public void 是apex包(boolean 是apex包) {
this.isApex = 是apex包;
}''',
     '''// isApex 是 API 29 字段，低版本无此字段 → NoSuchFieldError。反射读写。
public boolean 是apex包() {
try {
return (Boolean) android.content.pm.PackageInfo.class.getField("isApex").get(this);
} catch (Throwable t) {
return false;
}
}

public void 是apex包(boolean 是apex包) {
try {
android.content.pm.PackageInfo.class.getField("isApex").set(this, 是apex包);
} catch (Throwable t) {
// 低版本无此字段，忽略
}
}'''),

    # 5) 安卓服务信息：getForegroundServiceType() (API 29)
    ('ticode/zh/android/安卓服务信息.java',
     '''public int 获取前台服务类型() {
return this.getForegroundServiceType();
}''',
     '''public int 获取前台服务类型() {
// getForegroundServiceType 是 API 29 方法，低版本 NoSuchMethodError。反射调用。
try {
return (Integer) android.content.pm.ServiceInfo.class
.getMethod("getForegroundServiceType").invoke(this);
} catch (Throwable t) {
return 0;
}
}'''),

    # 6) 浏览框：Build.VERSION_CODES.R (API 30)
    ('ticode/zh/android/浏览框.java',
     'mWebSettings.setAllowFileAccess(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R); //文件访问',
     '// VERSION_CODES.R 是 API 30 常量，低版本无此字段。用字面量 30。\n'
     'mWebSettings.setAllowFileAccess(Build.VERSION.SDK_INT >= 30); //文件访问'),

    # 7) 系统操作：Context/Activity.getDisplay() (API 30) → WindowManager.getDefaultDisplay()
    ('ticode/zh/android/系统操作.java',
     'return (int) 上下文环境.getDisplay().getRefreshRate();',
     '// Context.getDisplay() 是 API 30 方法；WindowManager.getDefaultDisplay() 全版本可用。\n'
     'return (int) ((android.view.WindowManager) 上下文环境\n'
     '.getSystemService(android.content.Context.WINDOW_SERVICE))\n'
     '.getDefaultDisplay().getRefreshRate();'),
    ('ticode/zh/android/系统操作.java',
     'Display display = 窗口环境.getDisplay();',
     '// Activity.getDisplay() 是 API 30 方法；getWindowManager().getDefaultDisplay() 全版本可用。\n'
     'Display display = 窗口环境.getWindowManager().getDefaultDisplay();'),
]

for rel, o, n in P:
    p = REPO + '/core/ticode/src/zh/java/' + rel
    s = io.open(p, encoding='utf-8').read()
    c = s.count(o)
    if c == 0:
        print('MISS: %s  <<%s>>' % (rel, o[:50])); continue
    io.open(p, 'w', encoding='utf-8').write(s.replace(o, n))
    print('ok(%d): %s' % (c, rel))

# 用 SDK 28 验证（能过 28 即兼容 minSdk 24 的这一层；API30 用法已全部消除）
for ver in ('28', '36'):
    env = dict(os.environ, JAVA_HOME='C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2',
               ANDROID_JAR='D:/android/platforms/android-%s/android.jar' % ver)
    subprocess.run(['bash', REPO + '/tools/ticode-compile.sh', 'zh', '500'],
                   capture_output=True, cwd=REPO, env=env)
    t = io.open('D:/android/tmp/err-zh-utf8.txt', encoding='utf-8').read()
    n = len(re.findall(r'错误:', t))
    print('编译错误(SDK%s): %d' % (ver, n))
    if n:
        for line in t.split('\n'):
            if '错误:' in line:
                print('   ', line[:150])
