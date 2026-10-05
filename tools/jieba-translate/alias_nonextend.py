# -*- coding: utf-8 -*-
"""@指代类 的目标里**只能用 implements** 的那些（接口）。

来源：转译后 javac 的实证错误「此处需要接口」，不是靠反射猜的。

为什么只收接口：
- 接口：`extends` 非法，改 `implements` 后接口方法自动可调 → 净收益（实测 -80 处错误）
- final / 枚举 / 私有构造器：改成类型别名后**实例方法全丢**
  （`GZIP输出流1.写出字节集(...)` 这类调用变成「找不到符号」），实测把错误从
  1180 抬到 1358，故这些**故意不收**——保留 extends 虽然报一处「无法从最终X进行继承」，
  但那是一处显式错误，好过几十处隐性丢失。

因此本表的类走 `implements`；其余 @指代类 仍走 `extends`。
"""

NON_EXTEND = {
    'Java参数化类型': 'java.lang.reflect.ParameterizedType',
    'Java泛型变量': 'java.lang.reflect.TypeVariable',
    'Java泛型数组类型': 'java.lang.reflect.GenericArrayType',
    'Java注解': 'java.lang.annotation.Annotation',
    'Java类型': 'java.lang.reflect.Type',
    'Java通配符类型': 'java.lang.reflect.WildcardType',
    '字符串': 'CharSequence',
    '枚举器': 'java.util.Iterator',
    '类名枚举器': 'java.util.Enumeration',
    '菜单': 'android.view.Menu',
    '菜单项': 'android.view.MenuItem',
    '记录集': 'android.database.Cursor',
    '适配器': 'android.widget.Adapter',
    '通信中间件': 'android.os.IBinder',
}

NON_EXTEND_KIND = {
    'Java参数化类型': 'interface',
    'Java泛型变量': 'interface',
    'Java泛型数组类型': 'interface',
    'Java注解': 'interface',
    'Java类型': 'interface',
    'Java通配符类型': 'interface',
    '字符串': 'interface',
    '枚举器': 'interface',
    '类名枚举器': 'interface',
    '菜单': 'interface',
    '菜单项': 'interface',
    '记录集': 'interface',
    '适配器': 'interface',
    '通信中间件': 'interface',
}
