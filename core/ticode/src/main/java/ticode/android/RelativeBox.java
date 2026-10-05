package ticode.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.*;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import java.util.*;

public class RelativeBox extends AdjustableMarginLayout {
public RelativeBox(android.content.Context context) {
super(context);
}

@Override
public android.widget.RelativeLayout onCreateView(android.content.Context context) {
android.widget.RelativeLayout view = new android.widget.RelativeLayout(context);
return view;
}

@Override
public android.widget.RelativeLayout getView() {
return (android.widget.RelativeLayout) view;
}

//设置对应组件位于布局中间
public void 位于布局中间(VisualComponent 欲设置组件, boolean 是否位于中间) {
View view = 欲设置组件.getView();
LayoutParams params = (LayoutParams) view.getLayoutParams();
if (是否位于中间) {
params.addRule(RelativeLayout.CENTER_IN_PARENT);
} else {
params.removeRule(RelativeLayout.CENTER_IN_PARENT);
}
view.setLayoutParams(params);
}

//设置对应组件位于布局顶部
public void 位于布局顶部(VisualComponent 欲设置组件, boolean 是否位于顶部) {
View view = 欲设置组件.getView();
LayoutParams params = (LayoutParams) view.getLayoutParams();
if (是否位于顶部) {
params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
} else {
params.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
}
view.setLayoutParams(params);
}

//设置对应组件位于布局底部
public void 位于布局底部(VisualComponent 欲设置组件, boolean 是否位于底部) {
View view = 欲设置组件.getView();
LayoutParams params = (LayoutParams) view.getLayoutParams();
if (是否位于底部) {
params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
} else {
params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
}
view.setLayoutParams(params);
}

//设置对应组件位于布局左边
public void 位于布局左边(VisualComponent 欲设置组件, boolean 是否位于左边) {
View view = 欲设置组件.getView();
LayoutParams params = (LayoutParams) view.getLayoutParams();
if (是否位于左边) {
params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
} else {
params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
}
view.setLayoutParams(params);
}

//设置对应组件位于布局右边
public void 位于布局右边(VisualComponent 欲设置组件, boolean 是否位于右边) {
View view = 欲设置组件.getView();
LayoutParams params = (LayoutParams) view.getLayoutParams();
if (是否位于右边) {
params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
} else {
params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
}
view.setLayoutParams(params);
}

//设置对应组件位于某个组件的左边
public void 位于某组件左边(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.LEFT_OF, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件位于某个组件的右边
public void 位于某组件右边(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.RIGHT_OF, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件位于某个组件的上面
public void 位于某组件之上(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.ABOVE, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件位于某个组件的下面
public void 位于某组件之下(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.BELOW, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件与某个组件的左边平齐
public void 与某组件左边平齐(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.ALIGN_LEFT, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件与某个组件的右边平齐
public void 与某组件右边平齐(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.ALIGN_RIGHT, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件与某个组件的顶部平齐
public void 与某组件顶部平齐(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.ALIGN_TOP, 目标组件ID);
view.setLayoutParams(params);
}

//设置对应组件与某个组件的底部平齐
public void 与某组件底部平齐(VisualComponent 欲设置组件, int 目标组件ID) {
View view = 欲设置组件.getView();
RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
params.addRule(RelativeLayout.ALIGN_BOTTOM, 目标组件ID);
view.setLayoutParams(params);
}
}