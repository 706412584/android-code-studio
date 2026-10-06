# -*- coding: utf-8 -*-
"""@指代类 里走「类型别名」的类：类型位置直接映射成目标 Java 类型。

只收两类（**实测**，不是猜）：
  1) 接口 interface——`extends` 非法，必须 `implements`；
  2) 枚举 enum——无法继承，实例方法本就不可用。

**private-ctor / final 类故意不收**，尽管它们同样「无法继承」。原因有实测数据：
这些包装类往往**转发目标类的实例方法**（如 `位图对象` 有 30 个
`this.getDensity()` / `this.recycle()` 之类）。改成类型别名后实例方法全丢，
`this.xxx()` 变「找不到符号」，比留着 `extends` 报一处「无法从最终X进行继承」
更糟。实测对比（中文版）：
    interface + enum + private-ctor + 若干 final = 1002
    interface + enum                              = 992   ← 采用
    interface + enum + 3 个 final                 = 1036
    再加 9 个 final                               = 1117

结论：这个「包装类 vs 原生类」的矛盾没有通用解（`文本构建器` 既要继承
`JString`、又代表 final 的 `StringBuilder`），逐项调优的收益也很小。
"""

NON_EXTEND = {
    'Java参数化类型': 'java.lang.reflect.ParameterizedType',
    'Java泛型变量': 'java.lang.reflect.TypeVariable',
    'Java泛型数组类型': 'java.lang.reflect.GenericArrayType',
    'Java注解': 'java.lang.annotation.Annotation',
    'Java类型': 'java.lang.reflect.Type',
    'Java通配符类型': 'java.lang.reflect.WildcardType',
    '位图压缩格式': 'android.graphics.Bitmap.CompressFormat',
    '位图配置': 'android.graphics.Bitmap.Config',
    '图像缩放类型': 'android.widget.ImageView.ScaleType',
    '字符串': 'CharSequence',
    '枚举器': 'java.util.Iterator',
    '类名枚举器': 'java.util.Enumeration',
    '绘制和形状': 'android.graphics.drawable.GradientDrawable.Orientation',
    '菜单': 'android.view.Menu',
    '菜单项': 'android.view.MenuItem',
    '记录集': 'android.database.Cursor',
    '适配器': 'android.widget.Adapter',
    '通信中间件': 'android.os.IBinder',
    '可枚举类': 'java.util.Iterable',
    '可枚举条目类': 'java.util.Map',
}

NON_EXTEND_KIND = {
    'Java参数化类型': 'interface',
    'Java泛型变量': 'interface',
    'Java泛型数组类型': 'interface',
    'Java注解': 'interface',
    'Java类型': 'interface',
    'Java通配符类型': 'interface',
    '位图压缩格式': 'enum',
    '位图配置': 'enum',
    '图像缩放类型': 'enum',
    '字符串': 'interface',
    '枚举器': 'interface',
    '类名枚举器': 'interface',
    '绘制和形状': 'enum',
    '菜单': 'interface',
    '菜单项': 'interface',
    '记录集': 'interface',
    '适配器': 'interface',
    '通信中间件': 'interface',
    '可枚举类': 'interface',
    '可枚举条目类': 'interface',
}
