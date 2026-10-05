package ticode.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class Intent2 extends android.content.Intent {



public Object 置入(String 键名, Object 数据) {
this.putExtra(键名, 数据);
}




public void 置数据包(Bundle2 数据) {
this.putExtras(数据);
}




public Bundle2 取数据包() {
return this.getExtras();
}





public String 取文本(String 键名) {
return this.getStringExtra(键名);
}





public String[] 取文本数组(String 键名) {
return this.getStringArrayExtra(键名);
}





public int 取整数(String 键名, int 默认值) {
return this.getIntExtra(键名, 默认值);
}





public int[] 取整数数组(String 键名) {
return this.getIntArrayExtra(键名);
}





public long 取长整数(String 键名, long 默认值) {
return this.getLongExtra(键名, 默认值);
}





public char 取字符(String 键名, char 默认值) {
return this.getCharExtra(键名, 默认值);
}





public boolean 取逻辑值(String 键名, boolean 默认值) {
return this.getBooleanExtra(键名, 默认值);
}





public Object 取序列化对象(String 键名) {
return this.getSerializableExtra(键名);
}

//设置启动信息将报告的动作
public void 设置动作(String 动作) {
this.setAction(动作);
}

//获取已设置的动作
public String 获取动作() {
return this.getAction();
}

//报告将显示一些数据给用户
public static final String 显示动作 = "android.intent.action.VIEW";

//报告将发送数据，且未指定接受者
public static final String 发送数据_不定目标动作 = "android.intent.action.SEND";

//报告将发送数据，且指定接受者
public static final String 发送数据_指定目标动作 = "android.intent.action.SENDTO";

//报告将呼出电话界面
public static final String 呼出电话动作 = "android.intent.action.CALL";

//报告将直接拨打电话
public static final String 拨打电话动作 = "android.intent.action.DIAL";

//报告将显示数据以让用户编辑
public static final String 编辑数据动作 = "android.intent.action.EDIT";

//单独设置数据的uri部分
public void 设置URI(AndroidResourceId URI) {
this.setData(URI);
}

//单独设置数据的uri部分，参数会自动解析为uri
public void 设置URI文本(String URI文本) {
this.setData(android.net.Uri.parse(URI文本));
}

//单独设置数据的mime部分，mime俗称文件的后缀名即文件类型
public void 设置MIME(String MIME文本) {
this.setType(MIME文本);
}

//同时设置URI与MIME
public void 设置URI与MIME(AndroidResourceId URI, String MIME文本) {
this.setDataAndType(URI,MIME文本);
}

//同时设置URI与MIME，第一个参数会自动解析为uri
public void 设置URI文本与MIME(String URI文本, String MIME文本) {
this.setDataAndType(android.net.Uri.parse(URI文本),MIME文本);
}

//获取已设置的URI，如果想获取URI本身的文本可以使用 到文本()
public AndroidResourceId 获取URI() {
return this.getData();
}

//获取已设置的MIME
public String 获取MIME() {
return this.getType();
}

//文本文件，如txt
public static final String 文本文件 = "text/*";

//图片文件，如jpg/jpeg,png
public static final String 图片文件 = "image/*";

//视频文件，如mp4,aiv
public static final String 视频文件 = "video/*";

//音频文件，如mp3,wav,ogg
public static final String 音频文件 = "audio/*";

//应用文件，如apk,exe,app
public static final String 应用文件 = "application/*";

public void 设置类(AndroidEnv 环境, JavaClass java类) {
this.setClass(环境,java类);
}


//添加一个种类进入启动信息
public void 添加种类(String 新种类) {
this.addCategory(新种类);
}

//删除启动信息中一个指定种类
public void 删除种类(String 种类) {
this.removeCategory(种类);
}

//获取启动信息的所有种类
public String[] 获取种类() {
return this.getCategories().toArray(new String[0]);
}

//默认的种类
public static final String 默认种类 = "android.intent.category.DEFAULT";


//设置标记，当需要设置多个标记时，请使用位或运算符|来整合(如: 标记1 | 标记2 | 标记3)
public void 设置标记(int 标记) {
this.setFlags(标记);
}

//获取标记
public int 获取标记() {
return this.getFlags();
}

//启动窗口时禁用动画效果
public static final int 禁用切换窗口动画标记 = 65536;


//不指定需要切换的窗口，在全手机中寻找可用的窗口并且切换
public void 隐式启动窗口(AndroidEnv 环境) {
环境.startActivity(this);
}

//如果手机中有可响应切换的窗口则返回真，否则返回假
public boolean 有可响应切换窗口(AndroidEnv 环境) {
return (this.resolveActivity(环境.getPackageManager()) != null);
}
}