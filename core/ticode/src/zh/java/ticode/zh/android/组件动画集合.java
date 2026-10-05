package ticode.zh.android;


public class 组件动画集合 extends android.view.animation.AnimationSet {

public static android.view.animation.AnimationSet 新建集合() {
return new android.view.animation.AnimationSet(true);
}

//添加一个动画到动画集合中
public void 添加动画(组件动画 欲添加动画) {
this.addAnimation(欲添加动画);
}

public 组件动画[] 取所有动画() {
return (组件动画[])this.getAnimations().toArray(new 组件动画[0]);
}
}