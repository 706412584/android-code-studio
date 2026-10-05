package ticode.meng;

import com.google.android.flexbox.*;
import android.view.View;
import android.view.ViewGroup;

import ticode.android.AdjustableMarginLayout;
import ticode.android.DrawableObject;
import ticode.android.VisualComponent;

public class FlexDividerMode {

public static final FlexDividerMode 无 = 0;
public static final FlexDividerMode 起始位 = 1;
public static final FlexDividerMode 中间 = 1 << 1;
public static final FlexDividerMode 结束位 = 1 << 2;

}