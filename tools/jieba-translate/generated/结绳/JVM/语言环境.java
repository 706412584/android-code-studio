package 结绳.JVM;

import java.util.Timer;
import java.util.TimerTask;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import java.util.UUID;

public class 语言环境 {
public static final 语言环境 中文;

public static final 语言环境 简体中文;

public static final 语言环境 繁体中文;

public static final 语言环境 英语;

public static final 语言环境 法语;

public static final 语言环境 日语;

public static final 语言环境 意大利语;

public static final 语言环境 朝鲜语;

public static final 语言环境 德语;

public static final 语言环境 英语_英国;

public static final 语言环境 英语_美国;

public static final 语言环境 英语_加拿大;

public static final 语言环境 法语_加拿大;

public void 赋值_op(String 语言代码, String 国家或地区代码) {
return new java.util.Locale(语言代码, 国家或地区代码);
}

public 语言环境 新建语言环境(String 语言代码, String 国家或地区代码) {
return new java.util.Locale(语言代码, 国家或地区代码);
}

static {
中文 = java.util.Locale.CHINESE;
简体中文 = java.util.Locale.SIMPLIFIED_CHINESE;
繁体中文 = java.util.Locale.TRADITIONAL_CHINESE;
英语 = java.util.Locale.ENGLISH;
法语 = java.util.Locale.FRENCH;
日语 = java.util.Locale.JAPANESE;
意大利语 = java.util.Locale.ITALIAN;
朝鲜语 = java.util.Locale.KOREAN;
德语 = java.util.Locale.GERMAN;
英语_英国 = java.util.Locale.UK;
英语_美国 = java.util.Locale.US;
英语_加拿大 = java.util.Locale.CANADA;
法语_加拿大 = java.util.Locale.CANADA_FRENCH;
}
}


