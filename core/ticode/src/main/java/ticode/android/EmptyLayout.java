package ticode.android;

import android.widget.FrameLayout;
import android.graphics.*;
import android.view.*;
import java.util.*;

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