package ticode.zh.android;


public class 消息 {

public static android.os.Message 获取消息(消息处理器 处理器, int 标记值) {
return 消息.obtain(处理器,标记值);
}

public void 置数据(android.os.Bundle 数据) {}

public android.os.Bundle 取数据包() {return null; }

public int 标记值() {return 0; }

public void 标记值(int 值) {}

public Object 参数() {return null; }

public void 参数(Object 值) {}
}