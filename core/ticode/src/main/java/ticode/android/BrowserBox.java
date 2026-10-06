package ticode.android;

import android.content.Context;
import android.widget.ProgressBar;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.app.Activity;
import android.app.DownloadManager;
import android.widget.FrameLayout;
import java.io.File;
import android.graphics.Bitmap;
import android.annotation.TargetApi;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class BrowserBox extends VisualComponent {

private ProgressBar mProgressBar;
private boolean Prv = true;
private View mView;
private int visibleAbility;
private int original;
private WebChromeClient.CustomViewCallback mCallback;
private static ValueCallback < Uri > message_upload;
private static ValueCallback < Uri[] > message_upload2;
private static JsPromptResult htcs;
private java.util.ArrayList<String> JsI = new java.util.ArrayList();

//设置是否显示进度条
public void 显示进度条(boolean 是否显示进度条) {
//code this.Prv = #是否显示进度条;
mProgressBar.setVisibility((this.Prv = 是否显示进度条) ? View.VISIBLE : View.GONE);
}

//浏览框UA
public String UA() {
return getView().getSettings().getUserAgentString();
}

//设置浏览框UA
public void UA(String UA) {
getView().getSettings().setUserAgentString(UA);
}

public static final int 缓存_默认 = -1;

//仅使用网络，不使用缓存
public static final int 缓存_仅网络 = 2;

//仅使用缓存，不使用网络
public static final int 缓存_仅缓存 = 3;

//只要本地有，无论是否过期，都使用缓存中的数据，没有就使用网络
public static final int 缓存_缓存_其它网络 = -1;

public void 缓存模式(int 缓存模式) {
getView().getSettings().setCacheMode(缓存模式);
}

//判断浏览框是否可后退
public boolean 可后退() {
return getView().canGoBack();
}

//判断浏览框是否可前进
public boolean 可前进() {
return getView().canGoForward();
}

//获取浏览框当前网址
public String 网址() {
return getView().getUrl();
}

public void 网址(String 网址) {
getView().loadUrl(网址);
}

//获取浏览框当前网页标题
public String 标题() {
return getView().getTitle();
}

//获取浏览框当前网页加载进度
public int 进度() {
return getView().getProgress();
}

//获取 HTML 内容的高度
public int 页面高度() {
return getView().getContentHeight();
}

//获取当前页面的 favicon
public BitmapObject 网页图标() {
return getView().getFavicon();
}

//获取 网页301 转跳前的原始链接
public String 原始链接() {
return getView().getOriginalUrl();
}

//加载网址
public void 加载网址(String 网址) {
getView().loadUrl(网址);
}

//加载数据
public void 加载数据(String 数据) {
try {
getView().loadDataWithBaseURL("", 数据, "text/html", "utf-8", null);
} catch (Exception e) {
e.printStackTrace();
}
}

//加载数据
public void 加载数据2(String 域名, String 数据, String MIME类型, String 编码, String 链接) {
try {
getView().loadDataWithBaseURL(域名, 数据, MIME类型, 编码, 链接);
} catch (Exception e) {
e.printStackTrace();
}
}

public void 禁止加载网络图片(boolean 是否禁止) {
getView().getSettings().setBlockNetworkImage(是否禁止);
}

public void 可自动播放(boolean 是否) {
getView().getSettings().setMediaPlaybackRequiresUserGesture(!是否);
}

public void 可访问本地文件(boolean 是否) {
getView().getSettings().setAllowFileAccessFromFileURLs(是否);
getView().getSettings().setAllowUniversalAccessFromFileURLs(是否);
}

//通过Url打开应用
public void 打开应用(String url) {
try {
Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
context.startActivity(intent);
} catch (Exception e) {
e.printStackTrace();
//context.弹出提示("未安装该应用");
}
}

//停止加载当前网页
public void 停止加载() {
getView().stopLoading();
}

//重新加载当前网页
public void 重载() {
刷新网页();
}

//重新加载当前网页
public void 刷新网页() {
getView().reload();
}

//网页后退
public void 后退() {
getView().goBack();
}

//网页前进
public void 前进() {
getView().goForward();
}






//设置某个网址的cookie
public void 置cookie(String 网址, String cookie) {
CookieManager manager = CookieManager.getInstance();
manager.setCookie(网址, cookie);
}

//获取某个网址的cookie
public String 取cookie(String 网址) {
CookieManager manager = CookieManager.getInstance();
return manager.getCookie(网址);
}

//清除浏览历史
public void 清除历史() {
getView().clearHistory();
}

//清除输入过的表单
public void 清除表单() {
getView().clearFormData();
}

//清除浏览框cookie
public void 清除cookie() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
CookieManager.getInstance().removeAllCookies(null);
} else {
CookieSyncManager.createInstance(context);
CookieManager.getInstance().removeAllCookie();
CookieSyncManager.getInstance().sync();
}
}

//清除浏览框缓存
public void 清除缓存() {
getView().clearCache(true);
File cacheFile = new File(context.getCacheDir().getParent() + "/app_webview");
clearCacheFolder(cacheFile, System.currentTimeMillis());
}

//用于拦截到下载的下载，返回下载任务的id
public long 下载(String 下载网址, String 保存路径) {
DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
Uri uri = Uri.parse(下载网址);
uri.getLastPathSegment();
DownloadManager.Request request = new DownloadManager.Request(uri);
File file = new File(保存路径);
request.setDestinationInExternalPublicDir(file.getParent(), file.getName());
request.setTitle(file.getName());
request.setDescription(下载网址);
File f = new File(保存路径);
if(f.exists())
f.delete();
long downloadId = downloadManager.enqueue(request);
return downloadId;
}

//上传文件的回调
public void 回调(int 请求码, int 结果码, Intent2 数据) {
if (请求码 == 5173) {
if (null == message_upload) {
return;
}
Uri result = 数据 == null || 结果码 != Activity.RESULT_OK ? null  : 数据.getData();
message_upload.onReceiveValue(result);
message_upload = null;
} else if (请求码 == 5174) {
if (null == message_upload2) {
return;
}
message_upload2.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(结果码, 数据));
message_upload2 = null;
}
}

//添加一个JS接口
public void 添加JS接口(String 接口名, String 方法名) {
String js = ("if(window."+ 接口名 + " == null){window."+ 接口名 + "=function (){}}window."+ 接口名 + "."+方法名 + "=function (a = '', b = ''){return prompt('[JsI"+ 接口名 +"- #-"+ 方法名 + "-] _'+a,b)};");
执行JS(js);
JsI.add(js);
}










//用于取消或中断JS交互事件的回调
public void 取消JS交互事件回调() {
htcs.cancel();
}

public void 执行JS(String JS) {
if (JS.为空()) {
return;
}
getView().evaluateJavascript("javascript:" + JS, new ValueCallback<String>() {
//@Override
public void onReceiveValue(String value) {
if(value == null || value.startsWith("null")) return;
value = ( "-\"'_\"" + value + "\"_'-\"").replace( "-\"'_\"\"","").replace( "\"\"_'-\"","");
执行JS回调(value);
}
});
}

public void 释放浏览框() {
getView().removeAllViews();
getView().destroy();
}

//不要在此方法内进行耗时操作
public String JS交互事件(String 接口名, String 方法名, String 数据1, String 数据2) { return null; } // 事件

//执行JS 方法的回调，JS里用 return 返回数据
public void 执行JS回调(String 数据) { } // 事件

//拦截到Url时触发该事件
public boolean 拦截到Url(String url) { return false; } // 事件

//拦截到Uri时触发该事件
public void 拦截到Uri(String uri) { } // 事件

//网页开始加载时触发该事件，返回加载的网址
public void 网页开始加载(String 网址) { } // 事件

//网页加载完成时触发该事件，返回加载的网址
public void 网页加载完成(String 网址) { } // 事件

//网页加载进度改变时触发该事件，返回进度
public void 进度值改变(int 进度) { } // 事件

//接收到网页标题触发该事件，返回网页标题
public void 接收到标题(String 网址) { } // 事件

//接收到网页图标触发该事件，返回可绘制对象
public void 接收到图标(BitmapObject 图标) { } // 事件

//网页拦截到网页请求时触发该事件，返回请求的网址
public void 拦截到请求(String 网址) { } // 事件

//网页拦截到下载请求时触发该事件，返回下载地址,名称,类型,以及大小
public void 拦截到下载(String 网址, String 名称, String 类型, long 大小) { } // 事件

//网页 Console Message， 等级： 0-调试, 1-错误, 2-日志, 3-异常, 4-警告
public boolean 控制台日志(int 行数, String 信息, int 等级, String 来源ID) { return false; } // 事件









public void 注入JS接口类(Object 接口类, String 接口名) {
getView().addJavascriptInterface(接口类, 接口名);
}

private void enabledCookie(WebView web) {
CookieManager instance = CookieManager.getInstance();
if (Build.VERSION.SDK_INT < 21) {
CookieSyncManager.createInstance(context);
}
instance.setAcceptCookie(true);
if (Build.VERSION.SDK_INT >= 21) {
instance.setAcceptThirdPartyCookies(web, true);
}
}
private void init() {
mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
mProgressBar.setLayoutParams(new WebView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(2 * context.getResources().getDisplayMetrics().density + 0.5f), 0, 0));
getView().addView(mProgressBar);
mProgressBar.setVisibility(View.GONE);
getView().setWebChromeClient(new MyWebChromeClient());
getView().setWebViewClient(new MyWebViewClient());
getView().setDownloadListener(new MyDownloadListener());
WebSettings mWebSettings = getView().getSettings();

mWebSettings.setJavaScriptEnabled(true);
mWebSettings.setDefaultTextEncodingName("utf-8");
mWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);//缓存模式

mWebSettings.setPluginState(WebSettings.PluginState.ON);

mWebSettings.setUseWideViewPort(true);
mWebSettings.setAllowFileAccess(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R); //文件访问
mWebSettings.setSupportZoom(true);
mWebSettings.setLoadWithOverviewMode(true);
mWebSettings.setBuiltInZoomControls(true);
mWebSettings.setUseWideViewPort(true);
mWebSettings.setLoadWithOverviewMode(true);
mWebSettings.setSupportZoom(true);
mWebSettings.setBuiltInZoomControls(true);
mWebSettings.setDisplayZoomControls(false);
mWebSettings.setSavePassword(true);
mWebSettings.setSaveFormData(true);
mWebSettings.setJavaScriptEnabled(true);
mWebSettings.setDomStorageEnabled(true);
mWebSettings.setJavaScriptCanOpenWindowsAutomatically(true);
mWebSettings.setLoadsImagesAutomatically(true);
mWebSettings.setDatabaseEnabled(true);
mWebSettings.setGeolocationDatabasePath(context.getDir("database", 0).getPath());
mWebSettings.setGeolocationEnabled(true);

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
mWebSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
}
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
mWebSettings.setMediaPlaybackRequiresUserGesture(true);
}
mWebSettings.setAllowFileAccessFromFileURLs(!(Build.VERSION.SDK_INT >= 16)); //允许从文件URL访问文件
mWebSettings.setAllowUniversalAccessFromFileURLs(!(Build.VERSION.SDK_INT >= 30));

CookieManager instance = CookieManager.getInstance();
if (Build.VERSION.SDK_INT < 21) {
CookieSyncManager.createInstance(context.getApplicationContext());
}
instance.setAcceptCookie(true);
if (Build.VERSION.SDK_INT >= 21) {
instance.setAcceptThirdPartyCookies(getView(), true);
}
enabledCookie(getView());
}

private void showVideo(View view, WebChromeClient.CustomViewCallback callback) {
if (context instanceof Activity) {
Activity activity = (Activity)context;
mView = view;
visibleAbility = activity.getWindow().getDecorView().getSystemUiVisibility();
original = activity.getRequestedOrientation();
mCallback = callback;
FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
decor.addView(view, new FrameLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT,
ViewGroup.LayoutParams.MATCH_PARENT));
view.setBackgroundColor(0xff66ccff);
activity.getWindow().getDecorView().setSystemUiVisibility(
View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
View.SYSTEM_UI_FLAG_FULLSCREEN |
View.SYSTEM_UI_FLAG_IMMERSIVE);
activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
}
}

private void closeVideo() {
if (context instanceof Activity) {
Activity activity = (Activity)context;
FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
decor.removeView(mView);
mView = null;
decor.setSystemUiVisibility(visibleAbility);
activity.setRequestedOrientation(original);
mCallback.onCustomViewHidden();
mCallback = null;
}
}

private class MyWebViewClient extends WebViewClient {
@Override
public boolean shouldOverrideUrlLoading(WebView view, final String url) {
if (url.startsWith("http") || url.startsWith("file")) {
return 拦截到Url(url);
} else {
拦截到Uri(url);
return true;
}
}

@Override
public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
handler.proceed();
}

@Override
public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
if (Build.VERSION.SDK_INT < 21) 拦截到请求(url);
return super.shouldInterceptRequest(view, url);
}

@Override
public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
拦截到请求(request.getUrl().toString());
return super.shouldInterceptRequest(view, request);
}

@Override
public void onPageStarted(WebView view, String url, Bitmap bitmap) {
网页开始加载(url);
}
@Override
public void onPageFinished(WebView view, String url) {
执行JS(JsI());
网页加载完成(url);
}
}

public String JsI(){
String s = "";
for(String ss : JsI)
s += ss;
return s;
}

private class MyWebChromeClient extends WebChromeClient {

@Override
public boolean onJsPrompt(WebView view, String url, String message,
String defaultValue, JsPromptResult result) {
htcs = result;
if(message.startsWith("[JsI")){
try{
String[] z = message.split("-] _",2);
String[] name = z[0].split("- #-",2);
String cname = name[0].replace("[JsI","");
String mname = name[1];
String nr = z[1];
String r = JS交互事件(cname,mname,nr,defaultValue);
result.confirm(r);
}catch(Throwable e){
取消JS交互事件回调();
}
return true;
}
return super.onJsPrompt(view, url, message, defaultValue, result);
}

@Override
public void  onProgressChanged(WebView view, int progress) {
进度值改变(progress);
执行JS(JsI()); // 注入放这里 其实并不消耗性能，因为加载网页时只会执行几次
//if (progress > 20 && progress < 50 ) 执行JS(JsI());
//else if (progress > 69 && progress < 81 ) 执行JS(JsI());
if(Prv){
if (progress == 100) {
mProgressBar.setVisibility(View.GONE);
}
else {
if (mProgressBar.getVisibility() == View.GONE)
mProgressBar.setVisibility(View.VISIBLE);
mProgressBar.setProgress(progress);
}
}
super.onProgressChanged(view, progress);
}

@Override
public void onReceivedTitle(WebView view, String title) {
super.onReceivedTitle(view, title);
接收到标题(title);
}






@Override
public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
if (mView != null) {
onHideCustomView();
return;
}
showVideo(view, callback);
}

@Override
public void onHideCustomView() {
if (mView == null) {
return ;
}
closeVideo();
getView().setVisibility(View.VISIBLE);
}

@Override
public View getVideoLoadingProgressView() {
return super.getVideoLoadingProgressView();
}

public void openFileChooser(ValueCallback < Uri > uri, String type, String capture) {
if (context instanceof Activity) {
Activity activity = (Activity)context;
message_upload = uri;
Intent i = new Intent(Intent.ACTION_GET_CONTENT);
i.addCategory(Intent.CATEGORY_OPENABLE);
i.setType("*/*");
if(type.contains("image")){
i = new Intent(Intent.ACTION_PICK);
i.setType(type);
}
//activity.startActivityForResult(i, 5173);
activity.startActivityForResult(Intent.createChooser(i, "文件选择"), 5173);
}
}

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public boolean onShowFileChooser(WebView view, ValueCallback < Uri[] > message, WebChromeClient.FileChooserParams params) {
if (context instanceof Activity) {
Activity activity = (Activity)context;
if (message_upload2 != null) {
message_upload2.onReceiveValue(null);
message_upload2 = null;
}
message_upload2 = message;
Intent i = params.createIntent();
if(i.getType().contains("image")){
i = new Intent(Intent.ACTION_PICK);
i.setType(params.createIntent().getType());
}
try {
activity.startActivityForResult(i, 5174);
return true;
} catch (ActivityNotFoundException e) {
message_upload2 = null;
}
}
return false;
}

//boolean onJsAlert(WebView view, String url, String message, JsResult result)
//boolean onJsConfirm(WebView view, String url, String message, JsResult result)
//JsResult.confirm();

public void onReceivedIcon(WebView view, Bitmap icon){//网站图标
接收到图标(icon);
}

public boolean onConsoleMessage(ConsoleMessage cm){
return 控制台日志(cm.lineNumber(), cm.message(), cm.messageLevel().ordinal(), cm.sourceId());
}

}

private static int clearCacheFolder(File dir, long time) {
int deletedFiles = 0;
if (dir != null && dir.isDirectory()) {
try {
for (File child : dir.listFiles()) {
if (child.isDirectory()) deletedFiles += clearCacheFolder(child, time);
if (child.lastModified() < time) if (child.delete()) deletedFiles++;
}
} catch (Exception e) {
e.printStackTrace();
}
}
return deletedFiles;
}

class MyDownloadListener implements DownloadListener {

@Override
public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
Uri uri = Uri.parse(url);
String mFilename = uri.getLastPathSegment();
if (contentDisposition != null) {
String p = "filename=\"";
int i = contentDisposition.indexOf(p);
if (i != - 1) {
i += p.length();
int n = contentDisposition.indexOf('"', i);
if (n > i)
mFilename = contentDisposition.substring(i, n);
}
}
拦截到下载(url, mFilename, mimetype, contentLength);
}
}

public WebView getView() {
return (WebView)super.getView();
}

public BrowserBox(android.content.Context context) {
super(context);
this.context = context;
init();
}

@Override
public WebView onCreateView(Context context) {
return new WebView(context);
}
}