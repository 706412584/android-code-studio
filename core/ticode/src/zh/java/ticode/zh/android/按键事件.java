package ticode.zh.android;


public abstract class 按键事件 extends android.view.KeyEvent {



public 按键动作 动作() {
return (按键动作)this.getAction();
}




public int 按键代码() {
return this.getKeyCode();
}
}