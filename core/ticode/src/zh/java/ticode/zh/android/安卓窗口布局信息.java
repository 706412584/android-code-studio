package ticode.zh.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class 安卓窗口布局信息 extends android.content.pm.ActivityInfo.WindowLayout {

public boolean 等于_op(安卓窗口布局信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓窗口布局信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public void 赋值_op(int 宽度, float 宽度百分数, int 高度, float 高度百分数, int 对齐方式, int 最小宽度, int 最小高度) {
return new android.content.pm.ActivityInfo.WindowLayout(宽度,宽度百分数,高度,高度百分数,对齐方式,最小宽度,最小高度);
}

public int 对齐方式() {
return this.gravity;
}

public int 高度() {
return this.height;
}

public float 高度百分数() {
return this.heightFraction;
}

public int 最小高度() {
return this.minHeight;
}

public int 最小宽度() {
return this.minWidth;
}

public int 宽度() {
return this.width;
}

public float 宽度百分数() {
return this.widthFraction;
}

}