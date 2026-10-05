package ticode.meng;

import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.RecyclerView.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import ticode.android.Adapter2;
import ticode.android.AndroidActivity;
import ticode.android.AndroidEnv;
import ticode.android.ComponentContainer;
import ticode.android.VisualComponent;
import ticode.jvm.JCollection;

public class ListItemTouchState {

public static final int 无 = 0;
public static final int 滑动 = 1;
public static final int 拖拽 = 2;

}