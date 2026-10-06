package ticode.android;

import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

public class BroadcastReceiver2 extends android.content.BroadcastReceiver {
public int 结果码() {
return this.getResultCode();
}

public void 结果码(int 结果码) {
this.setResultCode(结果码);
}

public String 结果内容() {
return this.getResultData();
}

public void 结果内容(String 内容) {
this.setResultData(内容);
}
}