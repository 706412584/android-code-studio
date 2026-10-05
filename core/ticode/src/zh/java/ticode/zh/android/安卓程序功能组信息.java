package ticode.zh.android;


public class 安卓程序功能组信息 extends android.content.pm.FeatureGroupInfo {

public boolean 等于_op(安卓程序功能组信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序功能组信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public 安卓程序功能信息[] 功能信息集() {
return this.features;
}

public void 功能信息集(安卓程序功能信息[] 功能信息集) {
this.features = 功能信息集;
}

}