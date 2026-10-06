package ticode.jvm;


public class HashMapTemplate<T1, T2> extends java.util.HashMap<T1, T2> {
public T2 取索引_op(T1 键) {
return 取项目(键);
}

public void 设索引_op(T1 键, T2 值) {
添加项目(键, 值);
}

public boolean 是_op(T1 键) {
return 是否存在(键);
}

public void 添加项目(T1 键, T2 值) {
this.put(键, 值);
}

public void 删除项目(T1 键) {
this.remove(键);
}

public T2 取项目(T1 键) {
return this.get(键);
}

public boolean 是否存在(T1 键) {
return this.containsKey(键);
}

public void 清空() {
this.clear();
}

public int 长度() {
return this.size();
}
}