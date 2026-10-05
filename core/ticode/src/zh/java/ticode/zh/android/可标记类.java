package ticode.zh.android;

import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class 可标记类 {
Object 标记值;

public void 标记(Object 标记值) {
this.标记值 = 标记值;
}

public Object 标记() {
return (标记值);
}
}