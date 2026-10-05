package ticode.android;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintLayout;

public class ConstraintBox extends LayoutComponent {
private final static String PARENT = "父布局";
public ConstraintBox(android.content.Context context) {
super(context);
}

@Override
public ConstraintLayout onCreateView(android.content.Context context) {
ConstraintLayout view = new ConstraintLayout(context);
return view;
}

@Override
public ConstraintLayout getView() {
return (ConstraintLayout) view;
}

public void 顶边到顶边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.topToTop = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.topToTop = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 顶边到底边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.topToBottom = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.topToBottom = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.topToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 底边到顶边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.bottomToTop = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.bottomToTop = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.bottomToTop = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 底边到底边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.bottomToBottom = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.bottomToBottom = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 左边到左边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.leftToLeft = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.leftToLeft = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 左边到右边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.leftToRight = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.leftToRight = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.leftToRight = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 右边到左边(VisualComponent 组件, Object 目标对象) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (目标对象 instanceof Integer) {
params.rightToLeft = (int)目标对象;
} else if (目标对象 instanceof VisualComponent) {
params.rightToLeft = ((VisualComponent)目标对象).getView().getId();
} else if (PARENT.equals(目标对象)) {
params.rightToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
}
组件.getView().setLayoutParams(params);
}

public void 自身宽高比例(VisualComponent 组件, Object 宽高比例) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (宽高比例 instanceof Integer || 宽高比例 instanceof Float || 宽高比例 instanceof Double) {
params.dimensionRatio = "H,1:" + (double)宽高比例;
} else if (宽高比例 instanceof String) {
float v = computePercentage(宽高比例);
params.dimensionRatio = "H,1:" + v;
}
组件.getView().setLayoutParams(params);
}

public void 宽度比例(VisualComponent 组件, Object 比例) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.matchConstraintPercentWidth = computePercentage(比例);
组件.getView().setLayoutParams(params);
}

public void 高度比例(VisualComponent 组件, Object 比例) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.matchConstraintPercentHeight = computePercentage(比例);
组件.getView().setLayoutParams(params);
}

public void 横向链式排列(VisualComponent 组件, FlexChainRule 排列规则) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.horizontalChainStyle = 排列规则;
组件.getView().setLayoutParams(params);
}

public void 纵向链式排列(VisualComponent 组件, FlexChainRule 排列规则) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.verticalChainStyle = 排列规则;
组件.getView().setLayoutParams(params);
}

public void 横向权重(VisualComponent 组件, Object 权重) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (权重 instanceof Integer) {
params.horizontalWeight = (int)权重;
} else if (权重 instanceof Float || 权重 instanceof Double) {
params.horizontalWeight = (float)权重;
} else if (权重 instanceof String) {
params.horizontalWeight = computePercentage(权重);
}
组件.getView().setLayoutParams(params);
}

public void 纵向权重(VisualComponent 组件, Object 权重) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
if (权重 instanceof Integer) {
params.verticalWeight = (int)权重;
} else if (权重 instanceof Float || 权重 instanceof Double) {
params.verticalWeight = (float)权重;
} else if (权重 instanceof String) {
params.verticalWeight = computePercentage(权重);
}
组件.getView().setLayoutParams(params);
}

public void 横向偏移比例(VisualComponent 组件, Object 比例) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.horizontalBias = computePercentage(比例);
组件.getView().setLayoutParams(params);
}

public void 纵向偏移比例(VisualComponent 组件, Object 比例) {
ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams)组件.getView().getLayoutParams();
if (params == null) {
params = new ConstraintLayout.LayoutParams(-2, -2);
}
params.verticalBias = computePercentage(比例);
组件.getView().setLayoutParams(params);
}

}