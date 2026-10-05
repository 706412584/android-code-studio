package ticode.zh.android;


public class 工具栏 extends 布局组件 {

public 工具栏(android.content.Context context) {
super(context);
}

@Override
public androidx.appcompat.widget.Toolbar onCreateView(android.content.Context context) {
androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(context);
toolbar.setOnMenuItemClickListener(new androidx.appcompat.widget.Toolbar.OnMenuItemClickListener(){
@Override
public boolean onMenuItemClick(android.view.MenuItem item) {
菜单项被单击(item.getTitle().toString());
return true;
}
});
toolbar.setNavigationOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View view) {
//被单击();
}});
return toolbar;
}

@Override
public androidx.appcompat.widget.Toolbar getView() {
return (androidx.appcompat.widget.Toolbar) view;
}

//设置工具栏标题
public void 标题(String 标题) {
getView().setTitle(标题);
}

//获取工具栏标题
public String 标题() {
return getView().getTitle().toString();
}

//设置工具栏副标题
public void 副标题(String 标题) {
getView().setSubtitle(标题);
}

//获取工具栏副标题
public String 副标题() {
return getView().getSubtitle().toString();
}

//设置工具栏标题颜色
public void 标题颜色(String 颜色) {
getView().setTitleTextColor(android.graphics.Color.parseColor(颜色));
}

//设置工具栏副标题颜色
public void 副标题颜色(String 颜色) {
getView().setSubtitleTextColor(android.graphics.Color.parseColor(颜色));
}

public void 标题边距(int 左, int 上, int 右, int 下) {
getView().setTitleMargin(左,上,右,下);
}

public void 标题左边距(int 边距) {
getView().setTitleMarginStart(边距);
}

public void 标题上边距(int 边距) {
getView().setTitleMarginTop(边距);
}

public void 标题右边距(int 边距) {
getView().setTitleMarginEnd(边距);
}

public void 标题下边距(int 边距) {
getView().setTitleMarginBottom(边距);
}

//设置Logo
public void 图标(可绘制对象 图片) {
getView().setLogo(图片);
}

//设置Logo描述
public void 图标描述(String 描述) {
getView().setLogoDescription(描述);
}

//设置导航图标
public void 导航图标(可绘制对象 图片) {
getView().setNavigationIcon(图片);
}

//设置导航描述
public void 导航描述(String 描述) {
getView().setNavigationContentDescription(描述);
}

public void 导航左边距(int 边距) {
getView().setContentInsetStartWithNavigation(边距);
}

public void 菜单按钮左边距(int 边距) {
getView().setContentInsetEndWithActions(边距);
}

public void 可折叠(boolean 是否) {
getView().setCollapsible(是否);
}

public void 折叠图标(可绘制对象 图片) {
getView().setCollapseIcon(图片);
}

public void 折叠描述(String 描述) {
getView().setCollapseContentDescription(描述);
}

public void 溢出图标(可绘制对象 图片) {
getView().setOverflowIcon(图片);
}

public void 内容边距(int 左, int 右) {
getView().setContentInsetsRelative(左,右);
}

public void 内容边距_相对(int 起始, int 结尾) {
getView().setContentInsetsRelative(起始,结尾);
}

//添加菜单项
public void 添加菜单(String 标题) {
getView().getMenu().add(标题);
}
















public 菜单项 添加菜单项2(int 组ID, int ID, int 序号, String 标题) {
return getView().getMenu().add(组ID, ID, 序号, 标题);
}








public void 添加子菜单(int 组ID, int ID, int 序号, String 标题) {
getView().getMenu().addSubMenu(组ID, ID, 序号, 标题);
}

public void 关闭菜单() {
getView().dismissPopupMenus();
}

public void 显示到状态栏(boolean 是否) {
getView().setFitsSystemWindows(是否);
}

public void 显示组件到状态栏(可视化组件 组件, boolean 是否) {
组件.getView().setFitsSystemWindows(是否);
}

//菜单项被单击时触发该事件
public void 菜单项被单击(String 菜单项) { } // 事件
public void 导航键被单击() { } // 事件

}