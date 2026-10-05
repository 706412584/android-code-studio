package ticode.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class FeatureGroupInfo extends android.content.pm.FeatureGroupInfo {

public boolean 等于_op(FeatureGroupInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(FeatureGroupInfo 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public FeatureInfo[] 功能信息集() {
return this.features;
}

public void 功能信息集(FeatureInfo[] 功能信息集) {
this.features = 功能信息集;
}

}