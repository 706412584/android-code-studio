package 结绳.安卓;

import android.content.*;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;
import android.util.*;
import android.graphics.*;

public class 悬浮窗 {

//创建一个悬浮窗，可创建多个不同标记的悬浮窗，标记相同会返回已存在的悬浮窗
public 悬浮窗 创建悬浮窗(组件容器 布局, String 标记) {
悬浮窗 fw = 悬浮窗.createFloatingWindow(标记, 布局.getLayout().getView());
if(fw.ViewContainer==null){
fw.ViewContainer = 布局;
布局.布局被加载();
}
return fw;
}

public void X坐标(int X) {
setX(X);
}

public int X坐标() {
return getX();
}

public void Y坐标(int Y) {
setY(Y);
}

public int Y坐标() {
return getY();
}

//一般情况无需设置，默认自适应
public void 固定宽度(int 宽) {
setWidth(宽);
}

//一般情况无需设置，默认自适应
public void 固定高度(int 高) {
setHeight(高);
}

//当悬浮窗靠近屏幕边缘时自动吸附
public void 边缘吸附(boolean 吸附) {
setEdgeSnapping(吸附);
}

public boolean 边缘吸附() {
return isEdgeSnapping();
}

//当悬浮窗距离屏幕边缘 小于指定距离时吸附，默认为0，自动吸附
public void 吸附距离(int 距离) {
setEdgeSnappingDistance(距离);
}

public int 吸附距离() {
return getEdgeSnappingDistance();
}

public void 吸附动画时长(int 毫秒) {
setEdgeSnappingAnimatedDuration(毫秒);
}

public int 吸附动画时长() {
return getEdgeSnappingAnimatedDuration();
}

public void 长按触发时长(int 毫秒) {
setLongTriggerDuration(毫秒);
}

public int 长按触发时长() {
return getLongTriggerDuration();
}

//悬浮窗是否可以拖动
public void 可移动(boolean 是否) {
setMove(是否);
}

public boolean 可移动() {
return isMove();
}

//悬浮窗是否可以被触摸，为假时悬浮窗不可触摸，可以触摸悬浮窗下面的组件，触摸穿透
public void 可触摸(boolean 是否) {
setTouch(是否);
}

public boolean 可触摸() {
return isTouch();
}

//默认为假，为真时可能会导致外部编辑框等无法使用
public void 可获取焦点(boolean 是否) {
setFocusable(是否);
}

public boolean 可获取焦点() {
return isFocusable();
}

//悬浮窗是否可以显示到状态栏区域
public void 可显示到状态栏(boolean 是否) {
setDisplayStatusBar(是否);
}

public boolean 可显示到状态栏() {
return isDisplayStatusBar();
}

//悬浮窗是否可以超出屏幕
public void 可显示到屏幕外(boolean 是否) {
setNoLimitDisplay(是否);
}

public boolean 可显示到屏幕外() {
return isNoLimitDisplay();
}

//使用前台服务监听屏幕方向，为真时显示一条后台常驻通知
public void 横竖屏适应(boolean 是否) {
setAutoScreenOrientation(是否);
}

public boolean 横竖屏适应() {
return isAutoScreenOrientation();
}

//当屏幕方向变化时自动移动到相对位置
public void 横竖屏位置适应(boolean 是否) {
setAutoScreenOrientationMove(是否);
}

public boolean 横竖屏位置适应() {
return isAutoScreenOrientationMove();
}

//全局显示悬浮窗，需要授权悬浮窗权限
public void 全局悬浮(boolean 是否) {
setGlobal(是否);
}

public boolean 全局悬浮() {
return isGlobal();
}

public void 标记(String 标记) {
setTag(标记);
}

public String 标记() {
return getTag();
}

public boolean 是否显示() {
return isShow();
}

public boolean 是否隐藏() {
return isHide();
}

public boolean 可自动弹出输入法() {
return getAutoEditable();
}

public void 可自动弹出输入法(boolean 是否) {
setAutoEditable(是否);
}

//推荐在 悬浮窗内编辑框被单击事件里执行，用于获取焦点，显示输入法，可以输入
public void 弹出输入法() {
setEditable(true);
}

public void 更新位置(int X, int Y, boolean 触发事件) {
updateLocation(X,Y, 触发事件);
}

//立即吸附到边缘
public void 吸附() {
edgeSnapping();
}

public void 显示() {
Show();
}

public void 隐藏() {
Hide();
悬浮窗隐藏();
}

//关闭后无法通过 标记获取
public void 关闭() {
Dismiss();
悬浮窗关闭();
}

public void 置布局(组件容器 容器) {
if(容器 == 取布局()) return;
ViewContainer = 容器;
setContentView(容器.getLayout().getView());
容器.布局被加载();
}

//获取悬浮窗 组件容器 对象
public 组件容器 取布局() {
return ViewContainer;
}

public 安卓环境 取安卓环境() {
return context;
}

// 指定 悬浮窗触摸 响应的组件，需要是 悬浮窗容器的组件，指定后 悬浮窗的单击、触摸、拖动等操作都与其关联
public void 指定触摸监听组件(可视化组件 组件, boolean 保留根布局触摸) {
initViewTouch(组件.getView(), 保留根布局触摸);
}

public void 悬浮窗显示() { return null; } // 事件
public void 悬浮窗隐藏() { return null; } // 事件
public void 悬浮窗关闭() { return null; } // 事件

public void 悬浮窗被单击(触摸事件 来源事件, int X, int Y) { return null; } // 事件
public void 悬浮窗被长按(触摸事件 来源事件, int X, int Y) { return null; } // 事件
public void 悬浮窗被触摸(触摸事件 来源事件, int X, int Y) { return null; } // 事件
public void 悬浮窗被拖动(触摸事件 来源事件, int X, int Y) { return null; } // 事件
public void 悬浮窗被放开(触摸事件 来源事件, int X, int Y) { return null; } // 事件
public void 悬浮窗位置变化(int X, int Y) { return null; } // 事件
public void 悬浮窗外侧操作(触摸事件 来源事件) { return null; } // 事件

public void 悬浮窗获取权限() { return null; } // 事件
public void 悬浮窗获取权限成功() { return null; } // 事件
public void 悬浮窗获取权限失败() { return null; } // 事件

public void 悬浮窗屏幕方向变化(int 方向, int 宽, int 高) { return null; } // 事件
public void 悬浮窗上下文菜单显示() { return null; } // 事件
public void 悬浮窗上下文菜单隐藏() { return null; } // 事件


public boolean 是否有全局悬浮窗权限() {
return isPermission();
}

public void 申请全局悬浮窗权限() {
requestPermissions();
}

public boolean 是否存在悬浮窗(String 标记) {
return 取悬浮窗(标记) != null;
}

public void 显示指定悬浮窗(String 标记) {
取悬浮窗(标记).显示();
}

public void 隐藏指定悬浮窗(String 标记) {
取悬浮窗(标记).隐藏();
}

public void 关闭指定悬浮窗(String 标记) {
取悬浮窗(标记).关闭();
}

public void 关闭所有悬浮窗() {
集合 集合 = 取所有悬浮窗();
while (集合 -> 值) {
((悬浮窗)值).关闭();
}
}

public 悬浮窗 取悬浮窗(String 标记) {
return getFWindow(标记);
}

public 集合 取所有悬浮窗() {
集合 集合;
for (悬浮窗 fw : FMap.values())
集合.add(fw);
return 集合;
}

public void 置上下文菜单布局(组件容器 容器) {
contextMemuView = 容器.getLayout().getView();
}

public void 隐藏上下文菜单() {
HideContextMemu();
if(actionModeCallback != null) actionModeCallback.onDestroyActionMode(actionMode);
}

public void 显示上下文菜单() {
ShowContextMemu();
}

public String 上下文菜单选择内容() {
return getSelectionString();
}

public void 上下文菜单粘贴内容(String 内容) {
if(focusView instanceof android.widget.EditText) replaceString((android.widget.EditText)focusView ,内容);
}

//返回焦点 View
public Object 取焦点视图() {
return focusView;
}

组件容器 ViewContainer = null;

//悬浮窗表，用于存储全部悬浮窗实例
public static Map<String, 悬浮窗> FMap;
//标记
public String tag;
//全局窗口状态监听，用于在非全局显示，应用内显示切换窗口时的显示移除添加
public static android.app.Application.ActivityLifecycleCallbacks ActivityLifecycle;
//窗口管理器
public WindowManager windowManager;
//布局属性
public WindowManager.LayoutParams layoutParams;
//上下文环境
public Context context;
//布局View
public ViewGroup rootView;
public View contentView;
//宽高
public int width = -2, height = -2;
//显示位置坐标
public int mx = 0, my = 0;
//显示相关		首次显示			是否显示		是否隐藏		是否全局显示		是否关闭
public boolean firstShow = true, isShow = false, isHide = false, isGlobal = false, isDismiss = false;
//显示相关		可显示到状态栏				可显示到屏幕外				自动屏幕方向适应				自动屏幕方向位置适应
public boolean isDisplayStatusBar = false, isNoLimitDisplay = false,  autoScreenOrientation = false, autoScreenOrientationMove = false;
//操作相关		可触摸			可获取焦点			可移动		输入编辑状态
public boolean isTouch = true, isFocusable = false, isMove = true, isAutoEditable = true, editable = false;
//吸附相关		边缘吸附				   吸附距离					吸附动画时长
public boolean edgeSnapping = false; int edgeSnappingDistance = 0, edgeSnappingAnimatedDuration = -1;
//长按触发时长
public int longTriggerDuration = 1000;

public 悬浮窗(String ftag, View v){
initRootView(v);//初始化View
initContentView(v);//初始化contentView
isGlobal = !(context instanceof Activity); //非窗口，启用全局
initWManager();//初始化 窗口管理器
if (FMap == null) FMap = new HashMap<>(); //新建用于储存全部悬浮窗的哈希表
FMap.put(ftag, this);//添加到表，以便使用标记获取此悬浮窗
//注册全局窗口生命周期监听
if(ActivityLifecycle == null && !isGlobal) registerActivityLifecycle((android.app.Application)context.getApplicationContext());
rootView.setVisibility(View.GONE);//隐藏View
this.tag = ftag;//保存标记
if(sW == 0 || sH == 0) { sW = screenWidth(); sH = screenHeight(); }
}

public static 悬浮窗 createFloatingWindow(String ftag, View v){
if(FMap != null && FMap.containsKey(ftag)) {
return FMap.get(ftag);
}
return new 悬浮窗(ftag, v);
}

public void initRootView(View v){
context = v.getContext(); //获取Context
rootView = new FrameLayout(context){
public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback, int type) {
ActionMode am = super.startActionModeForChild(originalView, callback, type);
if(am != null) return am;
actionModeCallback = callback;
focusView = rootView.getRootView().findFocus();
updateActionMode();
ShowContextMemu();
return actionMode;
}
}; //根布局
initViewTouch(rootView, false);
try{
rootView.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChangeListener);
} catch (Exception e) {}
rootView.getViewTreeObserver().addOnGlobalFocusChangeListener(focusChangeListener);
}

public View contextMemuView;
public WindowManager.LayoutParams contextMemuLayoutParams;
public View focusView;

//取选中文本
public String getSelectionString() {
if(focusView == null && !(focusView instanceof TextView)) return "";
TextView tv = (TextView)focusView;
int selectionStart = tv.getSelectionStart();
int selectionEnd = tv.getSelectionEnd();
if(selectionStart == selectionEnd) return "";
return tv.getText().toString().substring(selectionStart, selectionEnd);
}

//替换文本(编辑框粘贴)
public void replaceString(EditText v, CharSequence s) {
v.getText().replace(v.getSelectionStart(), v.getSelectionEnd(), s, 0, s.length());
}

//上下文菜单布局视图
public View getContextMemuView() {
if(contextMemuView == null) {
int butW = 像素操作.SP到PX(40);
ViewGroup cmv = new LinearLayout(context);
contextMemuView = cmv;
cmv.setTag(9979);
Button but1 = new Button(context);
cmv.addView(but1, butW, -2);
but1.setPadding(0,0,0,0);
but1.setText("复制");
but1.setOnClickListener((View v) -> {
if(focusView == null) return;
String substring = getSelectionString();
((ClipboardManager) focusView.getContext().getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText(null,substring));
if(actionModeCallback != null) actionModeCallback.onDestroyActionMode(actionMode);
HideContextMemu();
});

Button but2 = new Button(context);
cmv.addView(but2, butW, -2);
but2.setPadding(0,0,0,0);
but2.setText("粘贴");
but2.setOnClickListener((View v) -> {
if(focusView == null && !(focusView instanceof EditText)) return;
ClipData clipData = ((ClipboardManager) v.getContext().getSystemService("clipboard")).getPrimaryClip();
if (clipData != null && clipData.getItemCount() > 0) {
CharSequence pasteText = clipData.getItemAt(0).coerceToText(v.getContext());
replaceString((EditText)focusView, pasteText);
if(actionModeCallback != null) actionModeCallback.onDestroyActionMode(actionMode);
HideContextMemu();
}
});
but2.setVisibility(View.GONE);

Button but3 = new Button(context);
cmv.addView(but3, butW, -2);
but3.setPadding(0,0,0,0);
but3.setText("全选");
but3.setOnClickListener((View v) -> {
if(focusView instanceof EditText) ((EditText)focusView).selectAll();
});
but3.setVisibility(View.GONE);
}
return contextMemuView;
}

//上下文菜单布局属性
public WindowManager.LayoutParams getContextMemuLayoutParams() {
if(contextMemuLayoutParams == null) {
contextMemuLayoutParams = new WindowManager.LayoutParams();
contextMemuLayoutParams.width = -2;
contextMemuLayoutParams.height = 100;
contextMemuLayoutParams.format = layoutParams.format;
contextMemuLayoutParams.memoryType = layoutParams.memoryType;
contextMemuLayoutParams.type = layoutParams.type;
contextMemuLayoutParams.gravity = layoutParams.gravity;
contextMemuLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
contextMemuLayoutParams.x = layoutParams.x;
contextMemuLayoutParams.y = layoutParams.y;
}
return contextMemuLayoutParams;
}

//更新上下文菜单
public void updateContextMemu(int x, int y) {
getContextMemuLayoutParams().x = x;
getContextMemuLayoutParams().y = y;
try {
windowManager.updateViewLayout(getContextMemuView(), getContextMemuLayoutParams());
} catch (Exception e) {}
}

//显示上下文菜单
public void ShowContextMemu() {
if(focusView == null || actionModeCallback == null || getContextMemuView().getParent() != null) return;
try {
Object _tag = getContextMemuView().getTag();
if(_tag != null && _tag instanceof Integer && (int)_tag == 9979) {
boolean f = focusView instanceof EditText;
TextView tv = (TextView)focusView;
int selectionStart = tv.getSelectionStart();
int selectionEnd = tv.getSelectionEnd();
ViewGroup cmv = (ViewGroup)getContextMemuView();
cmv.getChildAt(0).setVisibility(selectionStart == selectionEnd ? View.GONE : View.VISIBLE);
cmv.getChildAt(1).setVisibility(f ? View.VISIBLE : View.GONE);
cmv.getChildAt(2).setVisibility(f ? View.VISIBLE : View.GONE);
}
windowManager.addView(getContextMemuView(), getContextMemuLayoutParams());
} catch (Exception e) {}
悬浮窗上下文菜单显示();
}

//隐藏上下文菜单
public void HideContextMemu() {
if(getContextMemuView().getParent() == null) return;
try {
windowManager.removeView(getContextMemuView());
} catch (Exception e) {}
悬浮窗上下文菜单隐藏();
}

//上下文菜单动作回调
public ActionMode.Callback actionModeCallback;

//延迟显示，防抖
public Runnable showViewRunnable = new Runnable() {public void run() {
ShowContextMemu();
}};

//上下文菜单动作器
public ActionMode actionMode = new ActionMode(){
public void invalidate(){
invalidateContentRect();
};
public void invalidateContentRect() {
updateActionMode();
}
public void hide(long duration) {
rootView.removeCallbacks(showViewRunnable);
if(duration == 0) rootView.postDelayed(showViewRunnable, 100);
else {
HideContextMemu();
}
}
public void finish(){
if(actionModeCallback != null) actionModeCallback.onDestroyActionMode(this);
HideContextMemu();
actionModeCallback = null;
};
public void setTitle(CharSequence title){};public void setTitle(int resId){};public void setSubtitle(CharSequence subtitle){};public void setSubtitle(int resId){};public void setCustomView(View view){};public Menu getMenu(){return null;};public CharSequence getTitle(){return null;};public CharSequence getSubtitle(){return null;};public View getCustomView(){return null;};public MenuInflater getMenuInflater(){return null;};
};

//更新上下文菜单动作器
public void updateActionMode(){
if(focusView == null) return;
if(actionModeCallback == null && !(actionModeCallback instanceof ActionMode.Callback2)) return ;
Rect rect = new Rect();
((ActionMode.Callback2) actionModeCallback).onGetContentRect(actionMode, focusView, rect);
int[] l = new int[2];
focusView.getLocationOnScreen(l);
int x = ((rect.left + rect.right) / 2) + l[0], y = rect.bottom + l[1];//((rect.top + rect.bottom) / 2) + l[1];
updateContextMemu(x, y);
}

//全局焦点监听
public android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusChangeListener = new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
public void onGlobalFocusChanged(android.view.View oldFocus, android.view.View newFocus) {
//if (oldFocus != null)
//oldFocus.clearFocus();
if (newFocus != null) {
focusView = newFocus;
if(newFocus instanceof android.widget.EditText){
if(isAutoEditable) setEditable(true);
} else if(editable) setEditable(false);
}
}
};

//初始化 触摸相关操作
public void initViewTouch(View v, boolean rootTouch){
if(rootTouch){
initViewTouch(rootView, false);
} else if(v != rootView){
rootView.setOnTouchListener(null);
rootView.setOnKeyListener(null);
rootView.setOnTouchListener(new RootViewOutsideTouchListener());
}
v.setOnTouchListener(new ViewTouchListener());//实现触摸拖动等操作
v.setFocusableInTouchMode(true);//允许触摸获取焦点
v.setOnKeyListener(new View.OnKeyListener() {
public boolean onKey(View view, int keyCode, android.view.KeyEvent event) {
//if(android.view.KeyEvent.KEYCODE_BACK == keyCode)
setEditable(false);
return false;
}
});
}

public void initContentView(View v){
contentView = v;
ViewGroup vg = (ViewGroup)v.getParent();
if(vg != null) vg.removeView(v);
rootView.removeAllViews();
rootView.addView(v);
}

//置悬浮窗布局View
public void setContentView(View v){
//removeView();
if(context != v.getContext()){
context = v.getContext();
}
initContentView(v);//初始化ContentView
initViewTouch(rootView, false);
try{
if(isShow || isHide)
windowManager.addView(rootView, layoutParams);
} catch (Exception e) {}
}

//取悬浮窗布局contentView
public View getContentView(){
return contentView;
}

//取悬浮窗布局rootView
public View getRootView(){
return rootView;
}

public void initWManager() {
initWManager(context);
}

//初始 WindowManager
public void initWManager(Context c) {
if (isGlobal || !(c instanceof Activity)) { //全局显示 || 非窗口
windowManager = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
} else { //仅Activity窗口显示
windowManager = ((Activity)c).getWindowManager();
}
initLParams();
}

//初始 布局属性
public void initLParams() {
layoutParams = new WindowManager.LayoutParams();
layoutParams.width = width;
layoutParams.height = height;
//透明
layoutParams.format = android.graphics.PixelFormat.TRANSLUCENT;
//GPU绘制
layoutParams.memoryType = WindowManager.LayoutParams.MEMORY_TYPE_GPU;
//输入模式
layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
if (isGlobal) { //判断窗口类型
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
} else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
} else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
layoutParams.type = WindowManager.LayoutParams.TYPE_TOAST;
} else {
layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
}

}
initFlags();
layoutParams.gravity = Gravity.START | Gravity.TOP;
//悬浮窗起始位置
layoutParams.x = mx;
layoutParams.y = my;
}

public void initFlags(){
//布局外触摸
int flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
//判断可获取焦点
if(!isFocusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
if(editable && !isFocusable) flags ^= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
//判断可触摸
if(!isTouch) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
//判断显示到状态栏
if(isDisplayStatusBar) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
//判断显示到屏幕外
if(isNoLimitDisplay) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
layoutParams.flags = flags;
//更新View
if(isShow || isHide) updateViewLayout();
}

//首次显示初始化
public void firstShow() {
if (isShow) return;
try{
if(isDismiss) removeView();
} catch (Exception e) {}
isShow = true;
firstShow = false;
if (rootView.getParent()!=null) return; //判断是否已被添加到布局
if(context instanceof Activity){
Activity a = (Activity)context;
if(a.isFinishing() || a.isDestroyed()){ //Activity 生命周期结束
initWManager(rootView.getContext());
} else initWManager(context);
} else initWManager(context);
isDismiss = false;
windowManager.addView(rootView, layoutParams);
if(!FMap.containsKey(tag)) FMap.put(tag, this);
悬浮窗显示();
}

//Activity模式，处理显示
public void updateView(Context c) {
if(!FMap.containsKey(tag) && isShow) {
removeView();
return;
}
if (isGlobal) {
context = c;
return;
};
if(!isShow && firstShow) return;

if(c instanceof Activity){
Activity a = (Activity)c;
if(a.isFinishing() || a.isDestroyed()) return;//Activity 生命周期结束
}

if (context == c) return;
removeView();
context = c;
initWManager(c);

try{
if(isShow || isHide)
windowManager.addView(rootView, layoutParams);
} catch (Exception e) {}
}

// 更新位置 layoutParams
public void updateLocation() {
updateViewLayout();
}

//更新位置
public void updateLocation(int x, int y) {
layoutParams.x = mx = x;
layoutParams.y = my = y;
updateViewLayout();
}

//更新位置
public void updateLocation(int x, int y, boolean callback) {
layoutParams.x = mx = x;
layoutParams.y = my = y;
updateViewLayout(callback);
}

//移除View视图
public void removeView(){
try {
windowManager.removeView(rootView);
} catch (Exception e) {}
}

public boolean getAutoEditable(){
return isAutoEditable;
}

public void setAutoEditable(boolean s){
isAutoEditable = s;
}

//输入法软键盘
public void setEditable(boolean s){
if(editable = s) {
((android.view.inputmethod.InputMethodManager)context.getSystemService("input_method")).showSoftInput(rootView, 0);
} else {
android.view.View currentFocus = rootView.getRootView().findFocus();
if (currentFocus != null) {
currentFocus.clearFocus();
((android.view.inputmethod.InputMethodManager) context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
} else ((android.view.inputmethod.InputMethodManager) context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(rootView.getApplicationWindowToken(), 0);
}
initFlags();
}

//更新视图or属性
public void updateViewLayout(){
updateViewLayout(true);
}
public void updateViewLayout(boolean callback){
try{
if(isShow || isHide)
windowManager.updateViewLayout(rootView, layoutParams);
if(callback) 悬浮窗位置变化(mx,my);
} catch (Exception e) {}
}

//X坐标
public void setX(int x){
layoutParams.x = mx = x;
updateViewLayout();
}
public int getX(){ return mx; }

//Y坐标
public void setY(int y){
layoutParams.y = my = y;
updateViewLayout();
}
public int getY(){ return my; }

//宽
public void setWidth(int w){
layoutParams.width = width = w;
updateViewLayout();
}
public int getWidth(){ return width; }

//高
public void setHeight(int h){
layoutParams.height = height = h;
updateViewLayout();
}
public int getHeight(){ return height; }

//边缘吸附
public void setEdgeSnapping(boolean b){
edgeSnapping = b;
}
public boolean isEdgeSnapping(){ return edgeSnapping; }

//吸附距离
public void setEdgeSnappingDistance(int d){
edgeSnappingDistance = d;
}
public int getEdgeSnappingDistance(){ return edgeSnappingDistance; }

//吸附动画时长
public void setEdgeSnappingAnimatedDuration(int d){
edgeSnappingAnimatedDuration = d;
}
public int getEdgeSnappingAnimatedDuration(){ return edgeSnappingAnimatedDuration; }

//长按触发时长
public void setLongTriggerDuration(int l){
longTriggerDuration = l;
}
public int getLongTriggerDuration(){ return longTriggerDuration; }

//可移动
public void setMove(boolean b){
isMove = b;
}
public boolean isMove(){ return isMove; }

//可触摸
public void setTouch(boolean b){
isTouch = b;
initFlags();
}
public boolean isTouch(){ return isTouch; }

//可获取焦点
public void setFocusable(boolean b){
isFocusable = b;
initFlags();
}
public boolean isFocusable(){ return isFocusable; }

//可显示到状态栏
public void setDisplayStatusBar(boolean b){
isDisplayStatusBar = b;
initFlags();
}
public boolean isDisplayStatusBar(){ return isDisplayStatusBar; }

//可显示到屏幕外
public void setNoLimitDisplay(boolean b){
isNoLimitDisplay = b;
initFlags();
}
public boolean isNoLimitDisplay(){ return isNoLimitDisplay; }

//横屏适应
public void setAutoScreenOrientation(boolean b){
autoScreenOrientation = b;
if(autoScreenOrientation) {
_屏幕方向监听服务.启动服务(context);
} else {
_屏幕方向监听服务.停止服务(context);
}
}
public boolean isAutoScreenOrientation(){ return autoScreenOrientation; }

//横屏位置适应
public void setAutoScreenOrientationMove(boolean b){
autoScreenOrientationMove = b;
}
public boolean isAutoScreenOrientationMove(){ return autoScreenOrientationMove; }

//全局显示
public void setGlobal(boolean b){
if(isGlobal == b) return;
isGlobal = b;
boolean s = isShow;
if(isShow || isHide) {
removeView();
isShow = false;
firstShow = true;
}
initWManager();
if(s) Show();
}
public boolean isGlobal(){ return isGlobal; }

//标记
public void setTag(String s){
FMap.remove(tag);
tag = s;
FMap.put(tag, this);
}
public String getTag(){ return tag; }

//是否可视
public void setVisible(boolean b) {
if(b) {
Show();
} else {
Hide();
}
}
public boolean isVisible() { return isShow(); }

//是否显示
public boolean isShow() { return isShow; }

//是否隐藏
public boolean isHide() { return isHide; }

//是否关闭
public boolean isDismiss() { return firstShow && !isShow() && !isHide(); }

//吸附
public void edgeSnapping() {
if(!isShow) return;
if(valueAnimator!=null) valueAnimator.cancel();
int maxDuration = 220;
int duration;
int lx = mx;
int rx = (screenWidth() - lx) - rootView.getWidth();
if(lx < rx){
duration = edgeSnappingAnimatedDuration == -1 ? (lx > 0 ? maxDuration * (lx/lx) : 100) : edgeSnappingAnimatedDuration;
animSlide(lx, 0, duration);
}else{
duration = edgeSnappingAnimatedDuration == -1 ? (rx > 0 ? maxDuration * (rx/rx) : 100) : edgeSnappingAnimatedDuration;
animSlide(lx, screenWidth() - rootView.getWidth(), duration);
}
}

//显示
public void Show() {
rootView.setVisibility(View.VISIBLE);
isHide = false;
if (firstShow) {
if (!isGlobal) {
firstShow();
return;
}
_悬浮窗_权限申请窗口.requestPermission(context, new _悬浮窗_权限申请窗口.FPListener() {
public void onAcquired() {
firstShow();
悬浮窗获取权限成功();
}
public void onStart(){
悬浮窗获取权限();
}
public void onSuccess() {
firstShow();
悬浮窗获取权限成功();
}
public void onFailed() {
悬浮窗获取权限失败();
}
});
} else {
if (isShow) return;
isShow = true;
悬浮窗显示();
}
}

//隐藏
public void Hide() {
if (!isShow) return;
rootView.setVisibility(View.GONE);
isShow = false;
isHide = true;
}

//关闭
public void Dismiss() {
if (!isShow) return;
removeView();
isShow = false;
isHide = false;
firstShow = true;
FMap.remove(tag);
}

public void requestPermissions() {
if(isPermission()) {
悬浮窗获取权限成功();
return;
}
_悬浮窗_权限申请窗口.requestPermission(context, new _悬浮窗_权限申请窗口.FPListener() {
public void onAcquired() {
悬浮窗获取权限成功();
}
public void onStart() {
悬浮窗获取权限();
}
public void onSuccess() {
悬浮窗获取权限成功();
}
public void onFailed() {
悬浮窗获取权限失败();
}
});
}
public boolean isPermission() {
return _悬浮窗_权限申请窗口.isPermission(context);
}

//取指定Tag悬浮窗
public static 悬浮窗 getFWindow(String tag) {
try{
return FMap == null ? null : FMap.get(tag);
} catch (Exception e) {
android.util.Log.i("TieApp", String.valueOf("不存在此标记的悬浮窗。"));
}
return null;
}

//取所有悬浮窗
public static java.util.ArrayList<悬浮窗> getAllFWindow() {
if(FMap == null) return null;
java.util.ArrayList<悬浮窗> l = new java.util.ArrayList<悬浮窗>();
for (悬浮窗 fw : FMap.values())
l.add(fw);
return l;
}

//模拟长按暂存变量
public long time = 0;
public boolean isLongClick = false;
public boolean stoped = false;
public MotionEvent longEvent;
public MyThread longThread;

// 模拟长按事件
class MyThread extends Thread {
public void run() {
while (System.currentTimeMillis() - time < longTriggerDuration && !stoped) ;
if (stoped) return;
isLongClick = true;
((android.app.Activity)context).runOnUiThread(new Runnable() {
public void run() {
悬浮窗被长按(longEvent, (int)longEvent.getRawX(), (int)longEvent.getRawY());
}
});
}
}

// 监听触摸
public float downX = 0, downY = 0, upX = 0, upY = 0; //相对于view左上角的坐标
public class ViewTouchListener implements android.view.View.OnTouchListener {
public float lastX, lastY, moveX, moveY;
public boolean click;
public int sw , sh;
public boolean onTouch(View v, MotionEvent event) {
switch (event.getAction()) {
case MotionEvent.ACTION_DOWN:
if(valueAnimator!=null) valueAnimator.cancel();
downX = lastX = event.getRawX();
downY = lastY = event.getRawY();
悬浮窗被触摸(event, mx, my);
sw = screenWidth();
sh = screenHeight();
if(editable) setEditable(false);
click = true;
isLongClick = false;
//move = false;
stoped = false;
time = System.currentTimeMillis();
(longThread = new MyThread()).start();
break;
case MotionEvent.ACTION_MOVE:
//拖动 计算坐标 更新位置
float cx = event.getRawX();
float cy = event.getRawY();
moveX = cx - lastX;
moveY = cy - lastY;
lastX = cx;
lastY = cy;
if(!isMove) return false;
//if ((Math.abs(cx - downX) >= 2 || Math.abs(cy - downY) >= 4)) move = true;
if ((Math.abs(cx - downX) >= 2 || Math.abs(cy - downY) >= 2)) stoped = true;
//if(move){
if(isNoLimitDisplay){
updateLocation(mx += (int)moveX,my += (int)moveY);
} else {
updateLocation((mx += (int)moveX) < 0 ? 0 : (mx > sw -  v.getWidth() ? sw - v.getWidth() : mx),
(my += (int)moveY) < 0 ? 0 : (my > sh - v.getHeight() ? sh - v.getHeight() : my));
}
click = false;
//}
悬浮窗被拖动(event, mx, my);
break;
case MotionEvent.ACTION_UP:
if(longThread != null && longThread.isInterrupted()) longThread.interrupt();
stoped = true;
upX = event.getRawX();
upY = event.getRawY();
int vw = v.getWidth();
if(edgeSnapping && isMove){
int maxDuration = 220;
int duration;
int lx = mx;
int rx = (sw - lx) - vw;
int moX = (int)(mx + moveX);
if(lx < rx && edgeSnappingDistance == 0 ? true : lx < edgeSnappingDistance){
duration = edgeSnappingAnimatedDuration == -1 ? (lx > 0 ? maxDuration * (lx/lx) : 100) : edgeSnappingAnimatedDuration;
if(lx > 0) animSlide(moX,0,duration);
else animSlide(moX,0,duration);
}else if(edgeSnappingDistance == 0 ? true : rx < edgeSnappingDistance){
duration = edgeSnappingAnimatedDuration == -1 ? (rx > 0 ? maxDuration * (rx/rx) : 100) : edgeSnappingAnimatedDuration;
animSlide(moX,sw-vw,duration);
}
}
if(!isLongClick) if(!(click = (Math.abs(upX - downX) > 2) || (Math.abs(upY - downY) > 2)))
悬浮窗被单击(event, mx, my);
isLongClick = false;
悬浮窗被放开(event, mx, my);
if(isNoLimitDisplay)
updateLocation(mx = mx < 0 ? 0 : (mx > sw ? sw - v.getWidth() : mx),
my = my < 0 ? 0 : (my > sh ? sh - v.getHeight() : my));
break;
case MotionEvent.ACTION_CANCEL:
break;
case MotionEvent.ACTION_OUTSIDE:
stoped = true;
click = false;
if(editable) setEditable(false);
悬浮窗外侧操作(event);
break;
}
longEvent = event;
return click;
}
}

public class RootViewOutsideTouchListener implements android.view.View.OnTouchListener {
public boolean onTouch(View v, MotionEvent event) {
if(MotionEvent.ACTION_OUTSIDE == event.getAction()){
if(editable) setEditable(false);
悬浮窗外侧操作(event);
}
return false;
}
}

public Context getContext() {
return context;
}

//吸附动画处理
public boolean aLeftOr = false;
public android.animation.ValueAnimator valueAnimator;
//播放属性动画
public void animSlide(int from, int to, int duration){
if(aLeftOr = (from < 0)) from = -from;
//if(valueAnimator!=null && !valueAnimator.hasEnded()) valueAnimator.cancel();
valueAnimator = android.animation.ValueAnimator.ofInt(from, to);
valueAnimator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
public void onAnimationUpdate(android.animation.ValueAnimator animation) {
int viewLeft = (int)animation.getAnimatedValue();
if(!aLeftOr) updateLocation(mx = viewLeft,my);
else updateLocation(mx = -viewLeft,my);
悬浮窗被拖动(longEvent, mx, my);
}
});
valueAnimator.setDuration(duration < 0 ? 0 : duration);
valueAnimator.start();
}

public void smoothMove(int x, int y, int duration){
duration = duration < 0 ? 0 : duration;
android.animation.ValueAnimator valueAnimatorX, valueAnimatorY;
//if(valueAnimator!=null && !valueAnimator.hasEnded()) valueAnimator.cancel();
valueAnimatorX = android.animation.ValueAnimator.ofInt(getX(), x);
valueAnimatorX.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
public void onAnimationUpdate(android.animation.ValueAnimator animation) {
int viewLeft = (int)animation.getAnimatedValue();
updateLocation(mx = viewLeft,my);
悬浮窗被拖动(null, mx, my);
}
});
valueAnimatorX.setDuration(duration);
valueAnimatorX.start();

valueAnimatorY = android.animation.ValueAnimator.ofInt(getY(), y);
valueAnimatorY.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
public void onAnimationUpdate(android.animation.ValueAnimator animation) {
int viewTop = (int)animation.getAnimatedValue();
updateLocation(mx ,my = viewTop);
悬浮窗被拖动(null, mx, my);
}
});
valueAnimatorY.setDuration(duration);
valueAnimatorY.start();
}

//屏幕 宽 高
public static int sW,sH;

//屏幕宽
public int screenWidth(){
if(sW == 0) sW = context.getResources().getDisplayMetrics().widthPixels;
int h = sH == 0 ? screenHeight() : sH;
if(isDisplayStatusBar) {
return sW + (sW > h ? getStatusBarHeight() : 0);
}
return sW;
}

//屏幕高
public int screenHeight(){
if(sH == 0) sH = context.getResources().getDisplayMetrics().heightPixels;
int w = sW == 0 ? screenWidth() : sW;
int bar = getStatusBarHeight();;
if(isDisplayStatusBar) {
return sH + (sH > w ? bar : 0);
}
return sH + (sH > w ? 0 : -bar);
}

public int getStatusBarHeight() {
return context.getResources().getDimensionPixelSize(context.getResources().getIdentifier("status_bar_height", "dimen", "android"));
}

public int getNavigationBarHeight() {
return context.getResources().getDimensionPixelSize(context.getResources().getIdentifier("navigation_bar_height", "dimen", "android"));
}

public void screenOrientationChange(int w, int h) {
if(autoScreenOrientationMove && (sW != w)) {
//旧屏幕宽高
int ow = screenWidth();
int oh = screenHeight();
//新屏幕宽高
this.sW = w;
this.sH = h;
//新屏幕宽高
int nw = screenWidth();
int nh = screenHeight();
//视图宽高
int vw = rootView.getWidth();
int vh = rootView.getHeight();
//位置
int x = getX();
int y = getY();

int bvw = vw / 2;
boolean r = false;
if(x + bvw > (ow/2)) r = true;
if(r) x += vw;
int xx,xy;
xx = Math.round(((float)x / (float)ow) * (float)nw);
xy = Math.round(((float)y / (float)oh) * (float)nh);
if(r) xx -= vw;
updateLocation(xx, xy);
}
悬浮窗屏幕方向变化(sW > sH ? 0 : 1, screenWidth(), screenHeight());
}

//注册窗口生命周期监听
public void registerActivityLifecycle(android.app.Application a){
a.registerActivityLifecycleCallbacks( ActivityLifecycle = new android.app.Application.ActivityLifecycleCallbacks() {
public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
public void onActivityStarted(Activity activity) {
//for (FWView fw : FMap.values()) fw.updateView(activity);
}
public void onActivityResumed(Activity activity) {
for (悬浮窗 fw : FMap.values())
fw.updateView(activity);
}
public void onActivityPaused(Activity activity) {}
public void onActivityStopped(Activity activity) {}
public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
public void onActivityDestroyed(Activity activity) {
//for (FWView fw : FMap.values()) //fw.removeView(activity);
}
});
}

}

