package ticode.android;


public class DragEvent2 extends android.view.DragEvent {



public int 动作() {
return this.getAction();
}




public double 横坐标() {
return this.getX();
}




public double 纵坐标() {
return this.getY();
}
}