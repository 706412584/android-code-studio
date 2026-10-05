# -*- coding: utf-8 -*-
"""修「非包装类」的「找不到符号」（中文版 196 → 124，总错误 992 → 883）。

对仓库版 tools/jieba-translate/translate.py 做**锚定字符串替换**。
每处 old 都先 assert 存在再 replace(…, 1)，末尾 ast.parse 自检。
**幂等**：每步带 MARKERS 标记，已合并的步自动跳过（原版跑 10 步，
已部分合并的仓库版只补缺失的步），因此可安全重跑。

实测各组 checkpoint（中文版，语法错误全程 0）：
    原版        992（找不到符号 196）
    +A          982
    +A+C        930（找不到符号 177）
    +A+B+C+D    889（找不到符号 128）
    +A+B+C+D+E  883（找不到符号 124）

改的 10 处（都不是 @指代类 包装类那条线），按 A–F 六组：

A. @后缀代码("extends/implements …") —— 结绳把基类/接口写在注解里，类声明行
   不带 `:`。此前该注解被当普通注解丢弃 → 类丢基类，父类方法/字段全「找不到符号」。
   实测影响 高级适配器（notifyDataSetChanged/notifyItemXxx）、X窗口
   （super.onResume/onPause/onCreate…）、高级列表项目触摸辅助器
   （super.onSelectedChanged/onChildDraw/clearView）、消息处理器（sendMessage）、
   构建路径（addPolygon/contains）等。14 个类带该注解。

B. @全局类 静态方法 → import static。结绳里 `取数组长度(x)`/`延时(100)` 直接按名
   调用全局类的静态方法；Java 必须静态导入，否则「找不到符号」。共 35 个 @全局类。
   （只收 @静态 成员；只对非限定调用生成；逐类计算；同简名去重。）

C. parse_method(stub=True) 的 is_static 读取顺序 —— 原代码先把 pending_static
   置 False 再读它，导致所有跨方法块宏的存根方法都成了实例方法，
   全局类里按名调用 `提交到新线程运行()` 全挂。

D. import 组装改为**逐类**（原来按整个 .t 文件算，多个类共用一份 head）：
   - 同简名冲突（LinearLayout.LayoutParams vs RelativeLayout.LayoutParams）按
     首次出现保留错误的那个 → 相对布局 的 params.addRule/removeRule 找不到（10 处）；
   - 未使用的导入（GridLayout 落在 相对布局 头上）干扰解析。
   同简名冲突时按「外部类简名是否出现在本类正文里」打分选优。

E. `循环(集合 -> 元素)` → for-each。原样输出成 `while (集合 -> 元素)`（6 处）。

F. 移除 _cross_refs 里恒真的死代码 `if j == cls and LANG == 'zh': pass`。

不动的部分（源码自身缺陷 / 需人工改写，见交付说明）：
  字符串/文本 继承 final String（7 处 取字符/为空）、可枚举类 extends Iterable（1）、
  文本框.内容 / 自定义宫格列表框.订阅事件 / 分割线（字段误转译 / 缺类型定义）、
  网络请求.httpGetResult（跨方法块宏字段）、对象操作.新建对象(类型.class)（结绳元编程）、
  X窗口 super.*（父类 安卓X窗口 未声明方法，其实是 androidx 依赖缺失的下游）。
"""
import io, ast

P = r'D:/android/projecet_iade/android-code-studio/tools/jieba-translate/translate.py'
s = io.open(P, encoding='utf-8').read()


STEPS = []


# 每步「已完成」的判定标记（都是**我新增代码**里的独特片段），对**载入时的
# 原文**判定，而不是对逐步替换中的文本判定——否则同一组里先应用的一步会把
# 后一步的标记提前写进去，导致后一步被误跳过。仓库里若已出现标记，说明该步
# （可能由他人以等价方式）已合并，跳过。让补丁既能在未合并的原版上跑，
# 也能在已部分合并的仓库版上安全重跑（只补缺失的那几步）。
MARKERS = {
    'A handle_annotation @后缀代码': "m_suf = re.match(r'^@后缀代码",
    'A parse_class apply @后缀代码': 'if self.pending_suffix and not self.alias:',
    'B _GLOBAL_CLASSES': 'def _load_global_classes',
    'B call _load_global_classes': '    _load_global_classes(src)',
    'C __init__ pending_suffix': 'pending_suffix',
    'C stub is_static': '否则所有存根方法都生成成实例方法',
    'D _cross_refs_text': 'def _cross_refs_text',
    'D translate head -> _class_head': 'def _class_head',
    'D write loop per-class head': '_class_head(t.pkg, t.imports, p)',
    'E 循环 ->': "split('->', 1)",
}
_ORIG = s        # 载入时的原文快照，供幂等判定


def rep(old, new, tag):
    """登记一步锚定替换。**幂等**：MARKERS[tag] 若已在原文快照中，跳过。"""
    if MARKERS[tag] in _ORIG:
        STEPS.append((tag, None, None))     # 已应用（可能由他人等价实现）
        return s
    assert old in s, 'anchor missing: ' + tag
    STEPS.append((tag, old, new))
    return s.replace(old, new, 1)


# ---------------------------------------------------------------- F + D 基础
old = '''def _cross_refs(t):
    """返回本文件引用的 (其它包, 类名) 集合。

    判据：文本里出现某个**已知类名**（中文版是中文名，英文版是映射后的英文名），
    且它属于**本文件之外的包**。用于生成 import。
    """
    text = '\\n'.join(t.out)
    own = t.pkg
    out = set()
    for jb_pkg, cls_set in _LIB_CLASSES.items():
        mapped_pkg = map_pkg(jb_pkg)
        if mapped_pkg == own:
            continue
        for cls in cls_set:
            j = map_cls(cls)
            if j == cls and LANG == 'zh':
                pass
            # 词边界匹配类名（避免子串误判）
            if re.search(r'(?<![\\w\\u4e00-\\u9fff])' + re.escape(j) + r'(?![\\w\\u4e00-\\u9fff])', text):
                out.add((mapped_pkg, j))
    return out'''
new = '''def _cross_refs_text(text, own_pkg):
    """返回文本引用的 (其它包, 类名) 集合。

    判据：文本里出现某个**已知类名**（中文版是中文名，英文版是映射后的英文名），
    且它属于**本文件之外的包**。用于生成 import。入参是**单个类**的正文，
    因此同一个 .t 里不同类各算各的 import（否则相对布局会继承线性布局的
    `LinearLayout.LayoutParams` 导入，`params.addRule` 在错类型上找不到）。
    """
    out = set()
    for jb_pkg, cls_set in _LIB_CLASSES.items():
        mapped_pkg = map_pkg(jb_pkg)
        if mapped_pkg == own_pkg:
            continue
        for cls in cls_set:
            j = map_cls(cls)
            # 词边界匹配类名（避免子串误判）
            if re.search(r'(?<![\\w\\u4e00-\\u9fff])' + re.escape(j) + r'(?![\\w\\u4e00-\\u9fff])', text):
                out.add((mapped_pkg, j))
    return out'''
s = rep(old, new, 'D _cross_refs_text')

# ---------------------------------------------------------------- B 全局类
old = '''                _LIB_CLASSES.setdefault(pkg, set()).add(m.group(1))


_LIB_CLASSES = {}
'''
new = '''                _LIB_CLASSES.setdefault(pkg, set()).add(m.group(1))


_LIB_CLASSES = {}

# @全局类：其**静态方法**在结绳里可直接按名调用（等价 Java 静态导入）。
# 记录 类名 -> (结绳包名, {方法名})，供生成 `import static`。
_GLOBAL_CLASSES = {}

def _load_global_classes(src):
    global _GLOBAL_CLASSES
    _GLOBAL_CLASSES = {}
    for p in glob.glob(os.path.join(src, '**', '*.t'), recursive=True):
        pkg = ''; pending = False; cur = None; is_static = False
        for ln in io.open(p, encoding='utf-8', errors='replace'):
            t = ln.strip()
            if t.startswith('包名'):
                pkg = t[2:].strip()
                continue
            if t.startswith('@全局类'):
                pending = True
                continue
            if t.startswith('@静态'):
                is_static = True
                continue
            if t in ('结束 类', '结束类'):
                cur = None; pending = False; is_static = False
                continue
            m = re.match(r'^(?:类|公开类|抽象类)\\s+([^\\s:<]+)', t)
            if m:
                cur = m.group(1)
                if pending:
                    _GLOBAL_CLASSES[cur] = (pkg, set())
                    pending = False
                continue
            if cur and cur in _GLOBAL_CLASSES:
                # 只收合法 Java 标识符名：`方法 =(...)`（运算符重载）会产出 `=`，
                # 生成 `import static X.=` 是语法错误。
                mm = re.match(r'^(?:方法|属性读|属性写)\\s+([A-Za-z_\\u4e00-\\u9fff][\\w\\u4e00-\\u9fff]*)', t)
                if mm:
                    # 只有**静态**成员能静态导入：实例方法（如 安卓线程.ID 属性读）
                    # 会产出 `import static ...ID;` → 「找不到符号」。
                    if is_static:
                        _GLOBAL_CLASSES[cur][1].add(mm.group(1))
                    is_static = False
                elif t and not t.startswith('@') and not t.startswith('//'):
                    # `@静态` 与 `方法` 之间常夹 `@嵌入式代码`，注解不能清标志；
                    # 只有非注解的实义行（变量/常量/其它）才清。
                    is_static = False


def _global_static_imports(body_all):
    """为 @全局类 的静态方法生成 `import static`。

    结绳里 `取数组长度(x)`/`延时(100)` 直接按名调用全局类的静态方法；
    Java 必须显式静态导入，否则「找不到符号」。只对**非限定调用**
    （名字前不是 `.`）生成，避免把 `流.关闭()` 这类实例调用误当全局方法。
    本文件自身定义的同名方法优先（跳过），防止静态导入与本地方法冲突。
    """
    # 本类已声明的方法优先，不生成静态导入（否则 `线程池` 会给自身
    # `结束提交到缓存线程池` 生成自导入，而该定义实际嵌在匿名 Runnable 里
    # → 「找不到符号 静态 X」）。入参是**单个类**的方法体，故不会误判兄弟类。
    own = set(re.findall(
        r'(?m)^\\s*(?:(?:public|private|protected|static|final|abstract|'
        r'synchronized|native|default|strictfp)\\s+)+[\\w<>\\[\\],.]+\\s+'
        r'([\\w\\u4e00-\\u9fff]+)\\s*\\(', body_all))
    res, seen = [], set()
    for gcls, (gpkg, methods) in _GLOBAL_CLASSES.items():
        jgcls = map_cls(gcls)
        for m in sorted(methods):
            # 同名方法只导入一次：两个全局类都提供 `取安卓环境` 时，
            # 重复静态导入会报「对 X 的引用不明确」。
            if m in own or m in seen:
                continue
            if re.search(r'(?<![\\w.\\u4e00-\\u9fff])' + re.escape(m) + r'\\s*\\(', body_all):
                res.append('import static %s.%s.%s;' % (map_pkg(gpkg), jgcls, m))
                seen.add(m)
    return res
'''
s = rep(old, new, 'B _GLOBAL_CLASSES')

# ---------------------------------------------------------------- C __init__
old = '''        self.alias = None
        self.used_names = set()'''
new = '''        self.alias = None
        self.pending_suffix = None   # @后缀代码("extends X") 的待用值
        self.used_names = set()'''
s = rep(old, new, 'C __init__ pending_suffix')

# ---------------------------------------------------------------- A 注解收集
old = '''        m_imp = re.match(r'^@导入Java\\("([^"]+)"\\)', s)
        if m_imp:
            self.imports.append(m_imp.group(1))
            return
        if s.startswith('@嵌入式代码'):'''
new = '''        m_imp = re.match(r'^@导入Java\\("([^"]+)"\\)', s)
        if m_imp:
            self.imports.append(m_imp.group(1))
            return
        # @后缀代码("extends X") / ("implements Y")：结绳把基类/接口写在注解里，
        # 类声明行不带 `:`。此前该注解被丢弃 → 类丢失基类，父类方法全「找不到符号」。
        m_suf = re.match(r'^@后缀代码\\("([^"]*)"\\)', s)
        if m_suf:
            self.pending_suffix = m_suf.group(1)
            return
        if s.startswith('@嵌入式代码'):'''
s = rep(old, new, 'A handle_annotation @后缀代码')

# ---------------------------------------------------------------- A 类声明应用
old = '''            if al not in ('int', 'long', 'short', 'byte', 'char', 'float',
                          'double', 'boolean', 'void'):
                extends = ' extends ' + al
        # name in NON_EXTEND：目标是 final/接口/枚举/私有构造器，不能 extends。'''
new = '''            if al not in ('int', 'long', 'short', 'byte', 'char', 'float',
                          'double', 'boolean', 'void'):
                extends = ' extends ' + al
        # @后缀代码("extends X") / ("implements Y")：结绳把基类/接口写在注解里，
        # 类声明行不带 `:`。此前该注解被丢弃 → 类丢基类，父类方法/字段全
        # 「找不到符号」（实测 高级适配器 的 notifyDataSetChanged、X窗口 的
        # super.onResume、高级列表项目触摸辅助器 的 super.onSelectedChanged…）。
        # @指代类 优先（它才是具体平台类），仅无 alias 时采用后缀代码。
        if self.pending_suffix and not self.alias:
            sfx = self.pending_suffix.strip()
            # 追加在 extends 之后（`class A extends B implements X` 合法）；
            # 若写成 `implements X` 而 extends 是类，反而非法，故不转换。
            if sfx and sfx not in extends:
                extends = (extends + ' ' + sfx) if extends else (' ' + sfx)
        self.pending_suffix = None
        # name in NON_EXTEND：目标是 final/接口/枚举/私有构造器，不能 extends。'''
s = rep(old, new, 'A parse_class apply @后缀代码')

# ---------------------------------------------------------------- C stub static
old = '''        if stub:
            # 跨方法块宏的**定义**：只保留签名 + 空体，丢弃无法表达的方法体。
            # 调用点不受影响（parse_body 里另有处理），类花括号得以配平。
            self.pending_static = False
            self.pending_embedded = False
            self.emit('public %s%s %s(%s) { %s}' % (
                'static ' if self.pending_static else '', jret, name, params,
                '' if jret == 'void' else 'return null; '))'''
new = '''        if stub:
            # 跨方法块宏的**定义**：只保留签名 + 空体，丢弃无法表达的方法体。
            # 调用点不受影响（parse_body 里另有处理），类花括号得以配平。
            # 注意：is_static 必须在清空 pending_static **之前**取，
            # 否则所有存根方法都生成成实例方法（原 `static ` 判断读的是已被
            # 置 False 的字段），全局类里按名调用的 `提交到新线程运行()` 全挂。
            is_static = self.pending_static
            self.pending_static = False
            self.pending_embedded = False
            self.emit('public %s%s %s(%s) { %s}' % (
                'static ' if is_static else '', jret, name, params,
                '' if jret == 'void' else 'return null; '))'''
s = rep(old, new, 'C stub is_static')

# ---------------------------------------------------------------- E for-each
old = '''        if len(args) == 3:
            # 循环(i,0,N) → for (int i = 0; i < N; i++)
            v, a, b = args
            self.emit('for (int %s = %s; %s < %s; %s++) {' % (
                v, subst(a, [], self.tparams), v, subst(b, [], self.tparams), v))
        else:'''
new = '''        if len(args) == 3:
            # 循环(i,0,N) → for (int i = 0; i < N; i++)
            v, a, b = args
            self.emit('for (int %s = %s; %s < %s; %s++) {' % (
                v, subst(a, [], self.tparams), v, subst(b, [], self.tparams), v))
        elif len(args) == 1 and '->' in args[0]:
            # 循环(集合 -> 元素) → for (Object 元素 : 集合)
            coll, var = args[0].split('->', 1)
            self.emit('for (Object %s : %s) {' % (
                var.strip(), subst(coll.strip(), [], self.tparams)))
        else:'''
s = rep(old, new, 'E 循环 ->')

# ---------------------------------------------------------------- D 逐类组装
old = '''def translate(path, outdir):
    t = Translator(path)
    t.parse()
    # 组装
    body_all = chr(10).join(t.out)   # 先算出来，供 import 过滤判定
    head = []
    if t.pkg:
        head.append('package %s;' % t.pkg)
    head.append('')
    # 去重：Java 禁止两个 simple name 相同的 single-type-import
    # （实测 `android.widget.LinearLayout.LayoutParams` 与
    #   `android.widget.RelativeLayout.LayoutParams` 并存 → 「已定义具有相同简名的类型」）。
    # 星号导入与单类型导入分开去重；保留首次出现者。
    _seen_simple, _seen_star = set(), set()
    for imp in t.imports:
        # 只保留 body 里**真正用到**的导入（星号导入无法廉价判定，一律保留）。
        # 类体里的 @导入Java 会进文件级 imports，使同文件其它类也带上它；
        # 若该包在库外不存在（如 tdr.util），未使用也会报「程序包不存在」。
        if any(imp == p or imp.startswith(p + '.') for p in MISSING_PACKAGES):
            continue
        if not imp.endswith('.*'):
            simple = imp.rsplit('.', 1)[-1]
            if not re.search(r'(?<![\\w.])' + re.escape(simple) + r'(?![\\w])', body_all):
                continue
        if imp.endswith('.*'):
            if imp in _seen_star:
                continue
            _seen_star.add(imp)
            head.append('import %s;' % imp)
        else:
            simple = imp.rsplit('.', 1)[-1]
            if simple in _seen_simple:
                continue
            _seen_simple.add(simple)
            head.append('import %s;' % imp)
    head.append('')
    # 跨包引用必须显式 import：本库 40 个文件引用了其它包的类，而原 .t 靠
    # 「同包解析」并不写 import。全量编译时 javac 会从 sourcepath 隐式加载，
    # 掩盖这个问题；两套映射下包名不同，必须补上。
    for other_pkg, other_cls in sorted(_cross_refs(t)):
        head.append('import %s.%s;' % (other_pkg, other_cls))
    if any(_cross_refs(t)):
        head.append('')
    # 一个 .t 文件含多个顶层类，而 Java 每个 public 类必须独占同名文件——
    # 按顶层 `public (abstract )?class ` 切分，各自成文件。'''
new = '''def _class_head(pkg, imports, body):
    """按**单个类**的正文组装 import 头。

    导入必须逐类计算：一个 .t 里多个类共用一份 imports 列表，若按整文件过滤，
    （a）同简名冲突（`LinearLayout.LayoutParams` vs `RelativeLayout.LayoutParams`）
        会按首次出现保留错误的那个，另一个类的方法就在错类型上找不到；
    （b）未使用的导入（`android.widget.GridLayout` 落在 相对布局 头上）干扰解析。
    """
    head = []
    if pkg:
        head.append('package %s;' % pkg)
    head.append('')
    # Java 禁止两个 simple name 相同的 single-type-import
    # （`android.widget.LinearLayout.LayoutParams` 与
    #   `android.widget.RelativeLayout.LayoutParams` 并存 → 「已定义具有相同简名的类型」）。
    # 同简名冲突时不能简单「保留首次」：文件级顺序会把 线性布局 的
    # `LinearLayout.LayoutParams` 留给 相对布局，`params.addRule` 就在错类型上
    # 找不到符号。改为按**外部类名是否出现在本类正文里**打分选优。
    by_simple, star = {}, []
    for imp in imports:
        if any(imp == p or imp.startswith(p + '.') for p in MISSING_PACKAGES):
            continue
        if imp.endswith('.*'):
            if imp not in star:
                star.append(imp)
            continue
        simple = imp.rsplit('.', 1)[-1]
        # 只保留 body 里**真正用到**的导入。
        if not re.search(r'(?<![\\w.])' + re.escape(simple) + r'(?![\\w])', body):
            continue
        by_simple.setdefault(simple, []).append(imp)

    def _score(imp):
        # 外部类简名（`android.widget.RelativeLayout.LayoutParams` → `RelativeLayout`）
        # 出现在正文 → 该导入才是本类真正要用的那个。
        parts = imp.split('.')
        outer = parts[-2] if len(parts) >= 2 else ''
        return 1 if outer and re.search(
            r'(?<![\\w\\u4e00-\\u9fff])' + re.escape(outer) + r'(?![\\w\\u4e00-\\u9fff])', body) else 0

    for simple in by_simple:
        cands = by_simple[simple]
        if len(cands) > 1:
            cands = sorted(cands, key=_score, reverse=True)
        head.append('import %s;' % cands[0])
    for imp in star:
        head.append('import %s;' % imp)
    head.append('')
    # 跨包引用必须显式 import：本库 40 个文件引用了其它包的类，而原 .t 靠
    # 「同包解析」并不写 import。全量编译时 javac 会从 sourcepath 隐式加载，
    # 掩盖这个问题；两套映射下包名不同，必须补上。
    xrefs = sorted(_cross_refs_text(body, pkg))
    for other_pkg, other_cls in xrefs:
        head.append('import %s.%s;' % (other_pkg, other_cls))
    if xrefs:
        head.append('')
    # @全局类 的静态方法 → 静态导入（结绳里可直接按名调用）。
    st = _global_static_imports(body)
    if st:
        head.extend(st)
        head.append('')
    return head


def translate(path, outdir):
    t = Translator(path)
    t.parse()
    # 组装
    # 一个 .t 文件含多个顶层类，而 Java 每个 public 类必须独占同名文件——
    # 按顶层 `public (abstract )?class ` 切分，各自成文件。'''
s = rep(old, new, 'D translate head -> _class_head')

# ---------------------------------------------------------------- D 写出循环
old = '''        fn = nm.group(1) + '.java'
        io.open(os.path.join(d, fn), 'w', encoding='utf-8').write('\\n'.join(head) + '\\n' + p)
        n += 1'''
new = '''        fn = nm.group(1) + '.java'
        head = _class_head(t.pkg, t.imports, p)
        io.open(os.path.join(d, fn), 'w', encoding='utf-8').write('\\n'.join(head) + '\\n' + p)
        n += 1'''
s = rep(old, new, 'D write loop per-class head')

# ---------------------------------------------------------------- B 调用加载
old = '''    _load_lib_classes(src)
    files = glob.glob(os.path.join(src, '**', '*.t'), recursive=True)'''
new = '''    _load_lib_classes(src)
    _load_global_classes(src)
    files = glob.glob(os.path.join(src, '**', '*.t'), recursive=True)'''
s = rep(old, new, 'B call _load_global_classes')

def apply(path, enabled=None):
    """把 STEPS（可按 tag 过滤）应用到 path 指向的 translate.py。"""
    src = io.open(path, encoding='utf-8').read()
    for tag, old, new in STEPS:
        if old is None:                     # 登记时即判定「已应用」
            continue
        if enabled is not None and tag not in enabled:
            continue
        assert old in src, 'anchor missing: ' + tag
        src = src.replace(old, new, 1)
    ast.parse(src)          # 自检
    io.open(path, 'w', encoding='utf-8').write(src)
    return src


if __name__ == '__main__':
    apply(P)
    print('fix_symbols.py: %d 处锚定替换完成，ast 自检通过' % len(STEPS))
