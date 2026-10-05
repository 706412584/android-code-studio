package ticode.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.*;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout.LayoutParams;
import android.widget.GridLayout;
import android.view.View;
import rn_1.*;
import java.util.*;
import android.view.View;

public class EmptyLayout extends LayoutComponent {
public EmptyLayout(android.content.Context context) {
super(context);
}

@Override
public FrameLayout onCreateView(android.content.Context context) {
FrameLayout view = new FrameLayout(context);
return view;
}

@Override
public FrameLayout getView() {
return (FrameLayout) view;
}
}