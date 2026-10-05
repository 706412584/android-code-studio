package ticode.android;


public class ComponentAnimationSet extends android.view.animation.AnimationSet {

public static ComponentAnimationSet 新建集合() {
return new ComponentAnimationSet(true);
}

//添加一个动画到动画集合中
public void 添加动画(ComponentAnimation 欲添加动画) {
this.addAnimation(欲添加动画);
}

public ComponentAnimation[] 取所有动画() {
return this.getAnimations().toArray(new ComponentAnimation[0]);
}
}