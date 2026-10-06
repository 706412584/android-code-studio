package ticode.zh.android;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;
import android.view.*;
import android.util.*;
import android.net.*;
import android.database.*;
import android.provider.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.system.*;
import android.graphics.*;
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

public class 像素操作 {
public static int DP到PX(int 值) {
float scale = 安卓应用.取安卓应用().getResources().getDisplayMetrics().density;
return (int) (值 * scale + 0.5f);
}

public static int PX到DP(int 值) {
float scale = 安卓应用.取安卓应用().getResources().getDisplayMetrics().density;
return (int) (值 / scale + 0.5f);
}

public static int SP到PX(int 值) {
android.util.DisplayMetrics metrics = 安卓应用.取安卓应用().getResources().getDisplayMetrics();
return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 值, metrics);
}

public static int PX到SP(int 值) {
return (int) (值 / 安卓应用.取安卓应用().getResources().getDisplayMetrics().scaledDensity);
}
}