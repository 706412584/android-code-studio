# -*- coding: utf-8 -*-
"""@指代类 里走「类型别名」的类：类型位置直接映射成目标 Java 类型。

收进来的判据（都是实测，不是猜）：
  1) 接口——`extends` 非法，必须 `implements`（14 个，净收益 -80）；
  2) 枚举 / 私有构造器——无法继承，实例方法本就不可用（13 个）；
  3) 少数 final 类——**仅当实测有净收益**（逐个试出来的 8 个）。

警告：**不要批量收 final 类**。final 类改成类型别名后实例方法全丢，实测把
候选 final 全收进去会把错误从 1070 抬到 1221。本表的 final 项是逐项验证过的。
"""

NON_EXTEND = {
    'Java参数化类型': 'java.lang.reflect.ParameterizedType',
    'Java泛型变量': 'java.lang.reflect.TypeVariable',
    'Java泛型数组类型': 'java.lang.reflect.GenericArrayType',
    'Java注解': 'java.lang.annotation.Annotation',
    'Java类型': 'java.lang.reflect.Type',
    'Java通配符类型': 'java.lang.reflect.WildcardType',
    'WiFi信息': 'android.net.wifi.WifiInfo',
    '位图压缩格式': 'android.graphics.Bitmap.CompressFormat',
    '位图对象': 'android.graphics.Bitmap',
    '位图配置': 'android.graphics.Bitmap.Config',
    '图像缩放类型': 'android.widget.ImageView.ScaleType',
    '字体对象': 'android.graphics.Typeface',
    '字符串': 'CharSequence',
    '拖放事件': 'android.view.DragEvent',
    '数据库': 'android.database.sqlite.SQLiteDatabase',
    '枚举器': 'java.util.Iterator',
    '类名枚举器': 'java.util.Enumeration',
    '组件属性动画播放器': 'android.view.ViewPropertyAnimator',
    '绘制和形状': 'android.graphics.drawable.GradientDrawable.Orientation',
    '菜单': 'android.view.Menu',
    '菜单项': 'android.view.MenuItem',
    '触摸事件': 'android.view.MotionEvent',
    '记录集': 'android.database.Cursor',
    '语言环境': 'java.util.Locale',
    '适配器': 'android.widget.Adapter',
    '通信中间件': 'android.os.IBinder',
    '附加资源管理器': 'android.content.res.AssetManager',
    '预备启动信息': 'android.app.PendingIntent',
}

NON_EXTEND_KIND = {
    'Java参数化类型': 'interface',
    'Java泛型变量': 'interface',
    'Java泛型数组类型': 'interface',
    'Java注解': 'interface',
    'Java类型': 'interface',
    'Java通配符类型': 'interface',
    'WiFi信息': 'private-ctor',
    '位图压缩格式': 'enum',
    '位图对象': 'private-ctor',
    '位图配置': 'enum',
    '图像缩放类型': 'enum',
    '字体对象': 'private-ctor',
    '字符串': 'interface',
    '拖放事件': 'private-ctor',
    '数据库': 'private-ctor',
    '枚举器': 'interface',
    '类名枚举器': 'interface',
    '组件属性动画播放器': 'private-ctor',
    '绘制和形状': 'enum',
    '菜单': 'interface',
    '菜单项': 'interface',
    '触摸事件': 'private-ctor',
    '记录集': 'interface',
    '语言环境': 'final',
    '适配器': 'interface',
    '通信中间件': 'interface',
    '附加资源管理器': 'private-ctor',
    '预备启动信息': 'private-ctor',
}
