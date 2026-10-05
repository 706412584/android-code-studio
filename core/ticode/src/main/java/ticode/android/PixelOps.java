package ticode.android;

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

import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JCollection;
import ticode.jvm.JHashMap;
import ticode.jvm.KeyValuePair;
import ticode.jvm.UUID;

public class PixelOps {
public static int DP到PX(int 值) {
float scale = AndroidApplication.取安卓应用().getResources().getDisplayMetrics().density;
return (int) (值 * scale + 0.5f);
}

public static int PX到DP(int 值) {
float scale = AndroidApplication.取安卓应用().getResources().getDisplayMetrics().density;
return (int) (值 / scale + 0.5f);
}

public static int SP到PX(int 值) {
android.util.DisplayMetrics metrics = AndroidApplication.取安卓应用().getResources().getDisplayMetrics();
return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 值, metrics);
}

public static int PX到SP(int 值) {
return (int) (值 / AndroidApplication.取安卓应用().getResources().getDisplayMetrics().scaledDensity);
}
}