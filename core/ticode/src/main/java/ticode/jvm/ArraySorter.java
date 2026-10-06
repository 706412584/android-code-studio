package ticode.jvm;

import java.util.*;

public class ArraySorter<T1> {



public void 排序(T1[] 欲排序数组) {
Arrays.sort(欲排序数组, new Comparator<T1>() {
@Override
public int compare(T1 o1, T1 o2) {
return 比较对象(o1, o2);
}
});
}








public int 比较对象(T1 对象1, T1 对象2) { return 0; } // 事件
}