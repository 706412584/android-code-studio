package ticode.zh.android;


public abstract class 拖放事件 extends android.view.DragEvent {



public 拖放动作 动作() {
return (拖放动作)this.getAction();
}




public double 横坐标() {
return this.getX();
}




public double 纵坐标() {
return this.getY();
}
}