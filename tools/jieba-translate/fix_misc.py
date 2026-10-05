# -*- coding: utf-8 -*-
"""零散错误簇修复补丁（对 tools/jieba-translate/translate.py 做锚定字符串替换）。

覆盖的错误类别与实测 before/after（中文版，基线 992，语法错误恒为 0）：

  1) 无法从静态上下文中引用非静态 X        25 → 1
     `@静态 变量 X` 生成普通实例字段，静态方法引用不到。parse_var 补 `static`。
  2) 方法不会覆盖或实现超类型的方法          35 → 10
     `@后缀代码("implements X")` 被当普通注解丢弃，父接口全丢。原样输出到类声明。
  3) 不兼容的类型: 意外的返回值             42 → 0
     结绳 `方法 =(…)` 是「本类型值构造」，体内是 `new 本类(...)`；按 void 生成会把
     `code expr` 变成丢弃值的语句。返回类型取本类名，无 return 时补 `return this;`。
  4) 此处需要接口                            4 → 0
     NON_EXTEND 接口类被无条件 `extends 接口`。改为：有显式基类则丢弃别名继承，
     无基类才 `implements 接口`。
  5) 对于setIcon(图片资源), 找不到合适的方法    6 → 0
     资源引用类（图片资源/动画资源/…）在结绳里是 `R.xxx` 整型资源 ID。类型位置映射 int。
  6) 无法取消引用int / double                10 → 0
     基础类型扩展方法 `值.到十六进制()`/`.到字节()`/`.到整数()` 内联成 Java 等价式。
  7) 不兼容的类型: boolean 不是函数接口        3 → 0
     `循环(集合 -> 元素)` 被当 `while (cond)`。改为 Java for-each（`var` 推断）。
  8) 需要数组, 但找到Object                  11 → 2
     `变量 结果 = 方法(...)` 推断成 Object，`结果[0]` 失败。新增全库方法返回类型索引。
"""
import io, ast, sys

# 目标文件。默认是仓库版；可选 argv[1] 覆盖（仅供补丁自测）。
P = sys.argv[1] if len(sys.argv) > 1 else \
    r'D:/android/projecet_iade/android-code-studio/tools/jieba-translate/translate.py'
s = io.open(P, encoding='utf-8').read()


def rep(old, new, tag):
    global s
    assert old in s, 'anchor missing: ' + tag
    assert s.count(old) == 1, 'anchor not unique: ' + tag
    s = s.replace(old, new, 1)


# ---------------------------------------------------------------- 1) 资源引用 → int
rep(
"""def map_type(t, tparams):
    \"\"\"把结绳类型表达式映射成 Java 类型。\"\"\"
    t = t.strip()
    if not t:
        return 'Object'
    if t.endswith('?'):""",
"""# 结绳的「资源引用」类：`@值输出规则("@drawable")` / `@值输入规则(...)` 声明
# 它们在结绳编译期被替换成 Android 的 `R.xxx` 整型资源 ID。转译器不生成 R 类，
# 若留成空壳类，`builder.setIcon(图标)` / `getDrawable(图片)` 会报「找不到合适的
# 方法」/「图片资源无法转换为int」（实测约 15 处）。类型位置直接映射成 int。
VALUE_RESOURCE = {
    '图片资源': 'int', '高清图片资源': 'int', '动画资源': 'int', 'XML资源': 'int',
    '文本资源': 'int', '主题资源': 'int', '组件样式': 'int',
}


def map_type(t, tparams):
    \"\"\"把结绳类型表达式映射成 Java 类型。\"\"\"
    t = t.strip()
    if not t:
        return 'Object'
    if t in VALUE_RESOURCE:               # 资源引用 → Android 整型资源 ID
        return VALUE_RESOURCE[t]
    if t.endswith('?'):""",
    'VALUE_RESOURCE')

# --------------------------------------------------- 2) 基础类型扩展方法内联
rep(
"""        body = _CLASSNAME_NEW_RE.sub(lambda m: m.group(1) + map_type_cls(m.group(2)), body)
    for k, v in (('本对象', 'this'), ('且', '&&'), ('或', '||'), ('非', '!'), ('空', 'null'),""",
"""        body = _CLASSNAME_NEW_RE.sub(lambda m: m.group(1) + map_type_cls(m.group(2)), body)
    # 基础类型的「扩展方法」：结绳在 整数/小数/字节 上挂了 `到十六进制()` /
    # `到字节()` / `到整数()` 等。Java 无扩展方法，`值.到字节()` 会报
    # 「无法取消引用int」。这里直接内联成等价的 Java 表达式（实测约 10 处）。
    body = re.sub(r'(?<![\\w一-鿿.])([A-Za-z_一-鿿][\\w一-鿿]*)\\.到十六进制\\(\\)',
                  r'Integer.toHexString(\\1)', body)
    body = re.sub(r'(?<![\\w一-鿿.])([A-Za-z_一-鿿][\\w一-鿿]*)\\.到八进制\\(\\)',
                  r'Integer.toOctalString(\\1)', body)
    body = re.sub(r'(?<![\\w一-鿿.])([A-Za-z_一-鿿][\\w一-鿿]*)\\.到二进制\\(\\)',
                  r'Integer.toBinaryString(\\1)', body)
    body = re.sub(r'(?<![\\w一-鿿.])([A-Za-z_一-鿿][\\w一-鿿]*)\\.到字节\\(\\)',
                  r'(byte)(\\1)', body)
    body = re.sub(r'(?<![\\w一-鿿.])([A-Za-z_一-鿿][\\w一-鿿]*)\\(([^()]*)\\)\\.到整数\\(\\)',
                  r'(int)(\\1(\\2))', body)
    for k, v in (('本对象', 'this'), ('且', '&&'), ('或', '||'), ('非', '!'), ('空', 'null'),""",
    'prim-ext')

# --------------------------------------------------- 3) __init__ 新增字段
rep(
"""        self.alias = None
        self.used_names = set()
        self.used_class_names = set()
        self.cur_class_zh = ''
        self.cur_class_out = ''
        self.imports = []
        self.tparams = []""",
"""        self.alias = None
        self.pending_suffix = ''    # @后缀代码("implements X") 暂存，供类声明拼接
        self.used_names = set()
        self.used_class_names = set()
        self.cur_class_zh = ''
        self.cur_class_out = ''
        self.imports = []
        self.tparams = []
        # 全库方法返回类型索引：`方法名 → {返回类型 Java 串}`。供变量类型推断用
        # （如 `变量 结果 = 取网页源码_同步_内部(...)` 需要知道它是 `Object[]`，
        # 否则推断成 Object，`结果[0]` 报「需要数组, 但找到Object」）。
        self.method_rets = {}
        self._index_method_rets()""",
    'init-fields')

# --------------------------------------------------- 4) 方法返回类型索引
rep(
"""    def peek(self, k=0):
        j = self.i + k
        return self.lines[j] if j < len(self.lines) else ''""",
"""    def _index_method_rets(self):
        \"\"\"扫描全库 `.t`，记录每个方法名对应的返回类型（可能多个）。

        结绳的方法签名常跨多行（参数表换行），返回类型写在最后一行末尾；
        这里按「括号配平」把签名拼成一行再解析。
        \"\"\"
        for p in glob.glob(os.path.join(os.path.dirname(self.path), '**', '*.t'), recursive=True):
            lines = io.open(p, encoding='utf-8', errors='replace').read().split('\\n')
            for j, ln in enumerate(lines):
                t = ln.strip()
                if not re.match(r'^方法\\s+[^\\s(]+\\s*\\(', t):
                    continue
                sig = t
                k = j
                while _paren_open(sig) > 0 and k + 1 < len(lines):
                    k += 1
                    sig = sig.rstrip() + ' ' + lines[k].strip()
                m = re.match(r'^方法\\s+([^\\s(]+)\\s*\\(.*\\)\\s*(?::|为)\\s*(\\S+)', sig)
                if m and m.group(2) != '无返回值':
                    self.method_rets.setdefault(m.group(1), set()).add(m.group(2))

    def _call_ret(self, val):
        \"\"\"若 val 是「方法调用」，返回该方法的 Java 返回类型；否则 ''。\"\"\"
        v = re.sub(r'^等待\\s+', '', (val or '').strip())
        m = re.match(r'^([一-鿿A-Za-z_][\\w一-鿿]*)\\s*\\(', v)
        if not m:
            return ''
        rets = self.method_rets.get(m.group(1))
        if not rets or len(rets) > 1:
            return ''       # 未知或重载返回不一致 → 不猜
        return map_type(next(iter(rets)), self.tparams)

    def peek(self, k=0):
        j = self.i + k
        return self.lines[j] if j < len(self.lines) else ''""",
    'method-rets')

# --------------------------------------------------- 5) @后缀代码 解析
rep(
"""            m = re.match(r'^@导入Java\\("([^"]+)"\\)', s)
            if m:
                self.imports.append(m.group(1)); continue
            if s.startswith('@'):
                self.handle_annotation(s); continue""",
"""            m = re.match(r'^@导入Java\\("([^"]+)"\\)', s)
            if m:
                self.imports.append(m.group(1)); continue
            m = re.match(r'^@后缀代码\\("([^"]+)"\\)', s)
            if m:
                # 结绳的「附加 implements/extends」注解。原样输出，否则
                # `implements SensorEventListener` / `extends Handler` 全丢，
                # 导致「方法不会覆盖」+「找不到符号」两类错误（实测约 40 处）。
                self.pending_suffix = m.group(1).strip(); continue
            if s.startswith('@'):
                self.handle_annotation(s); continue""",
    'suffix-parse')

# --------------------------------------------------- 6) 接口别名继承
rep(
"""        if self.alias and NON_EXTEND_KIND.get(name) == 'interface':
            # 目标是接口：Java 里只能 implements，`extends` 会报「此处需要接口」。
            # 实现接口后接口方法自动可调（结绳里这些类就是该接口）。
            al = self.alias.strip()
            extends = (extends.replace(' extends ', ' implements ') + ', ' + al) if extends else (' implements ' + al)""",
"""        if self.alias and NON_EXTEND_KIND.get(name) == 'interface':
            # 结绳里这些类**就是**该接口：类型位置映射成接口（map_type_cls），
            # 本类只留静态工厂壳。绝不 `extends 接口`（Java 语法错「此处需要接口」）；
            # 把显式 `extends 基类` 强改成 implements 又会要求类体补全接口的全部
            # 抽象方法（实测 ParameterizedType.getOwnerType →「不是抽象的」）。
            # 有显式基类时基类已 implements 该接口，别名冗余；无基类时用
            # `implements 接口` 让结绳里转发接口方法的方法体仍可解析。
            al = self.alias.strip()
            if not extends:
                extends = ' implements ' + al""",
    'iface-alias')

# --------------------------------------------------- 7) @后缀代码 拼接
rep(
"""        # 全被误挂上 图像缩放类型 的 ScaleType）。
        self.alias = None
        tdecl = ('<' + ', '.join(self.tparams) + '>') if self.tparams else ''""",
"""        # 全被误挂上 图像缩放类型 的 ScaleType）。
        self.alias = None
        # @后缀代码("implements X")：结绳用它声明「本类还实现/继承 X」。
        # 拼在 extends 之后（`class A extends B implements X` 合法）。
        if self.pending_suffix:
            suf = self.pending_suffix.strip()
            if suf and suf not in extends:
                extends = (extends + ' ' + suf) if extends else (' ' + suf)
            self.pending_suffix = ''
        tdecl = ('<' + ', '.join(self.tparams) + '>') if self.tparams else ''""",
    'suffix-append')

# --------------------------------------------------- 8) parse_var：static + 返回类型推断
rep(
"""    def parse_var(self, s):
        self.pending_static = False
        s = self._join(s)   # 结绳允许 `变量 x : 类型 = 表达式` 跨行续写
        m = re.match(r'^变量\\s+([^\\s:]+)\\s*(?:(?::|为)\\s*([^=]+))?\\s*(?:=\\s*(.+))?$', s)
        name, typ, val = m.group(1), (m.group(2) or '').strip(), (m.group(3) or '').strip()
        am = re.match(r'^(.+?)\\[(.+)\\]$', typ)   # 字节[1024] / 整数[宽度*高度]
        if am:
            jt = map_type(am.group(1).strip(), self.tparams)
            self.emit('%s[] %s = new %s[%s];' % (
                jt, name, jt, subst(am.group(2), [], self.tparams))); return
        jt = map_type(typ, self.tparams) if typ else self.infer_type(val)
        if val and re.match(r'^\\(.*\\)$', val) and jt not in ('int','long','short','byte','float','double','boolean','char','String','Object'):
            # 结绳构造语法：变量 x : 类型 = (a,b)  →  类型 x = new 类型(a,b)
            self.emit('%s %s = new %s%s;' % (jt, name, jt, val)); return
        self.emit('%s %s%s;' % (jt, name, (' = ' + subst(val, [], self.tparams)) if val else ''))""",
"""    def parse_var(self, s):
        st = self.pending_static          # @静态 变量 → 静态字段，否则静态方法引用不到
        self.pending_static = False
        pre = 'static ' if st else ''
        s = self._join(s)   # 结绳允许 `变量 x : 类型 = 表达式` 跨行续写
        m = re.match(r'^变量\\s+([^\\s:]+)\\s*(?:(?::|为)\\s*([^=]+))?\\s*(?:=\\s*(.+))?$', s)
        name, typ, val = m.group(1), (m.group(2) or '').strip(), (m.group(3) or '').strip()
        am = re.match(r'^(.+?)\\[(.+)\\]$', typ)   # 字节[1024] / 整数[宽度*高度]
        if am:
            jt = map_type(am.group(1).strip(), self.tparams)
            self.emit('%s%s[] %s = new %s[%s];' % (
                pre, jt, name, jt, subst(am.group(2), [], self.tparams))); return
        jt = map_type(typ, self.tparams) if typ else (self._call_ret(val) or self.infer_type(val))
        if val and re.match(r'^\\(.*\\)$', val) and jt not in ('int','long','short','byte','float','double','boolean','char','String','Object'):
            # 结绳构造语法：变量 x : 类型 = (a,b)  →  类型 x = new 类型(a,b)
            self.emit('%s%s %s = new %s%s;' % (pre, jt, name, jt, val)); return
        self.emit('%s%s %s%s;' % (pre, jt, name, (' = ' + subst(val, [], self.tparams)) if val else ''))""",
    'parse-var')

# --------------------------------------------------- 9) 值语义 `方法 =`
rep(
"""        is_op = name in self.OPMAP
        name = self.op_name(name)""",
"""        is_op = name in self.OPMAP
        value_sem = (name == '=')   # 结绳 `方法 =(...)` 是「本类型值构造」，语义上返回值
        name = self.op_name(name)""",
    'value-sem-flag')

rep(
"""        if self.embedded and jret == 'void':
            jret = 'Object'
        is_static = self.pending_static
        self.pending_static = False
        self.used_names.add(name)
        self.embedded = False
        self.cur_ret = jret
        self.emit('public %s%s %s(%s) {' % ('static ' if is_static else '', jret, name, params))
        self.parse_body()
        self.cur_ret = 'void'""",
"""        if self.embedded and jret == 'void':
            jret = 'Object'
        # 结绳 `方法 =(…)` 无返回类型时仍代表「构造一个本类实例」（如 `文件 =("路径")`
        # 体内是 `new java.io.File(...)`）。按 void 生成会把 `code expr` 变成
        # 丢弃值的语句（javac「意外的返回值」）。返回类型取本类名。
        if value_sem and jret == 'void':
            jret = self.cur_class_out or 'Object'
        is_static = self.pending_static
        self.pending_static = False
        self.used_names.add(name)
        self.embedded = False
        self.cur_ret = jret
        self.emit('public %s%s %s(%s) {' % ('static ' if is_static else '', jret, name, params))
        mark = len(self.out)
        self.parse_body()
        # 值语义 `=` 的体内可能只是字段赋值（`this.x = x;`），没有 return。
        # 那样 Java 会报「缺少返回语句」；补 `return this;`。
        if value_sem and not any(l.strip().startswith('return') for l in self.out[mark:]):
            for k in range(len(self.out) - 1, mark - 1, -1):
                if self.out[k].strip() == '}':
                    self.out.insert(k, 'return this;')
                    break
        self.cur_ret = 'void'""",
    'value-sem-body')

# --------------------------------------------------- 10) 循环(集合 -> 元素) → for-each
rep(
"""            v, a, b = args
            self.emit('for (int %s = %s; %s < %s; %s++) {' % (
                v, subst(a, [], self.tparams), v, subst(b, [], self.tparams), v))
        else:""",
"""            v, a, b = args
            self.emit('for (int %s = %s; %s < %s; %s++) {' % (
                v, subst(a, [], self.tparams), v, subst(b, [], self.tparams), v))
        elif len(args) == 1 and '->' in args[0]:
            # 结绳的 `循环(集合 -> 元素)`：Java 里是 for-each。用 `var` 让
            # javac 推断元素类型（否则元素是 Object，`v.键` 之类会「找不到符号」）。
            coll, var = args[0].split('->', 1)
            self.emit('for (var %s : %s) {' % (
                subst(var.strip(), [], self.tparams), subst(coll.strip(), [], self.tparams)))
        else:""",
    'foreach')

io.open(P, 'w', encoding='utf-8').write(s)
ast.parse(s)
print('fix_misc ok')
