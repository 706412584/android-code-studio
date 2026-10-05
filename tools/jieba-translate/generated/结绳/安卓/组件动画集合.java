package 结绳.安卓;


public class 组件动画集合 extends 组件动画 {

public 组件动画集合 新建集合() {
return new 组件动画集合(true);
}

//添加一个动画到动画集合中
public void 添加动画(组件动画 欲添加动画) {
this.addAnimation(欲添加动画);
}

public 组件动画[] 取所有动画() {
return this.getAnimations().toArray(new 组件动画[0]);
}
}