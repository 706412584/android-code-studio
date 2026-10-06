package ticode.zh.android;


public class 拖放事件 {
private android.view.DragEvent 内部对象;

public 拖放事件(android.view.DragEvent 事件对象) {
this.内部对象 = 事件对象;
}

public android.view.DragEvent 取内部对象() {
return this.内部对象;
}

public int 动作() {
return this.内部对象.getAction();
}

public double 横坐标() {
return this.内部对象.getX();
}

public double 纵坐标() {
return this.内部对象.getY();
}
}
