#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
结绳(.t) → Java 转译器。

结绳是「中文声明语法 + 内嵌 Java(code 块)」的混合体。本脚本做机械映射，
产出可直接 javac 的 Java 源码。

映射规则见 RULES 注释。只处理「不依赖结绳运行时基类」的类（继承目标在
本库内或 Object），其余类需人工处理基类垫片。
"""
import io, os, re, sys, glob, collections
# 与本脚本同目录的 class_map.py / dedup_set.py / alias_nonextend.py。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from class_map import CLASS_MAP, PACKAGE_MAP   # 结绳类名/包名 → 英文
# 库外缺失的包：源码用 @导入Java / @外部Java文件 引用，但那些 .java 从未随库发布。
# 对这些包的 import（含 `pkg.*` 星号形式）一律丢弃，否则报「程序包不存在」。
MISSING_PACKAGES = ('rn_1', 'tdr.util', 'com.Meng.decoration')

from dedup_set import (CONFIRMED as DROP_CLASSES,          # 与模板重叠、需剔除的类
                       DROP_CLASSES_NO_EXTERNAL,         # 依赖库外缺失 Java 类的孤立件
                       DROP_METHODS_NO_EXTERNAL)         # 只剔方法、保留类
from alias_nonextend import NON_EXTEND, NON_EXTEND_KIND   # @指代类 里不可继承的目标

# 语言模式：en=英文类名+ticode.* 包；zh=中文类名+ticode.zh.* 包
LANG = os.environ.get('TICODE_LANG', 'en')

PKG_ZH = {'结绳.JVM': 'ticode.zh.jvm', '结绳.Meng': 'ticode.zh.meng',
          '结绳.基本': 'ticode.zh.base', '结绳.安卓': 'ticode.zh.android'}

def map_pkg(p):
    return PKG_ZH.get(p, p) if LANG == 'zh' else PACKAGE_MAP.get(p, p)

def map_cls(n):
    return n if LANG == 'zh' else CLASS_MAP.get(n, n)


def map_type_cls(n):
    """类型位置上的类名。

    不可继承的 @指代类（`位图对象` == `Bitmap`）直接映射成 Java 原生类型：
    这些类在结绳里**就是**那个类型，只是额外挂了静态工厂方法；因为目标是
    final / 接口 / 枚举 / 私有构造器，生成 `extends` 必然编译不过。
    静态壳另生成（见 parse_class），故 `位图对象.从文件路径创建位图(...)` 仍可解析。
    """
    return NON_EXTEND.get(n) or map_cls(n)

# 结绳类型 → Java 类型
TYPE_MAP = {
    '文本': 'String', '整数': 'int', '长整数': 'long', '短整数': 'short',
    '字节': 'byte', '单精度小数': 'float', '双精度小数': 'double',
    '逻辑型': 'boolean', '字符': 'char', '对象': 'Object', '小数': 'double',
    '无返回值': 'void', '通用型': 'Object', '变体型': 'Object',
}
# 结绳包名 → 输出包名（保持结绳.* 命名空间，避免与既有类冲突）
def out_package(jb_pkg):
    return jb_pkg.strip()

BOX = {'int': 'Integer', 'long': 'Long', 'short': 'Short', 'byte': 'Byte',
       'float': 'Float', 'double': 'Double', 'boolean': 'Boolean', 'char': 'Character'}

def map_type(t, tparams):
    """把结绳类型表达式映射成 Java 类型。"""
    t = t.strip()
    if not t:
        return 'Object'
    if t.endswith('?'):                      # 可空 → 装箱类型
        return BOX.get(map_type(t[:-1], tparams), map_type(t[:-1], tparams))
    # 模板类型
    m = re.match(r'^模板类型(\d*)$', t)
    if m:
        return tparams[int(m.group(1) or 1) - 1] if tparams else 'Object'
    # 数组
    if t.endswith('[]'):
        return map_type(t[:-2], tparams) + '[]'
    # 集合/哈希表 简写
    if t == '文本集合' or t == '文本数组':
        return 'java.util.List<String>'
    if t == '整数集合':
        return 'java.util.List<Integer>'
    if t == '文本到文本哈希表':
        return 'java.util.Map<String,String>'
    # 先查基础类型表（int/String/Object…），再映射本库类名（安卓窗口→AndroidActivity）。
    # map_cls 在中文版是恒等函数，英文版才改名，故两版共用这一条路径。
    return map_type_cls(TYPE_MAP.get(t, t))

def _brace_balance(text):
    """剥离字符串/字符字面量与行注释后的 { } 净余额。0 表示配平。"""
    out = 0
    for ln in text.split('\n'):
        t = _STR_LIT.sub('', ln)
        t = re.sub(r'//.*', '', t)
        out += t.count('{') - t.count('}')
    return out


_STR_LIT = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


# @code 块内裸中文类名的整词匹配（按长度倒序，长名优先）。
_CLASSNAME_RE = re.compile(
    # 前瞻/后顾必须同时排除 CJK：否则 `TextBox` 会匹配进 `开始TextBox` 里，
    # 把中文标识符切碎（`开始TextBox` → `开始TextBox`）。
    r'(?<![A-Za-z0-9_一-鿿])(' +
    '|'.join(re.escape(k) for k in sorted(CLASS_MAP, key=len, reverse=True)) +
    r')(?![A-Za-z0-9_一-鿿])(?!\s*\.)')


# 结绳表达式里的类引用（非 @code 上下文也出现）：
#   `设备信息.安卓版本号`（静态字段）  /  `new 高级适配器(...)`（构造）
# 只在「后跟 . 」或「前有 new」时替换，避免误伤与类名同名的变量
# （如 `变量 文本 : 文本` 里的变量名 `文本`）。
_NAMES = '|'.join(re.escape(k) for k in sorted(CLASS_MAP, key=len, reverse=True))
_CLASSNAME_DOT_RE = re.compile(r'(?<![A-Za-z0-9_一-鿿])(' + _NAMES + r')(?=\s*\.)')
_CLASSNAME_NEW_RE = re.compile(r'(new\s+)(' + _NAMES + r')(?![A-Za-z0-9_一-鿿])')


def subst(body, params, tparams, in_code=False):
    """把 #x / #this / #<T> 换成 Java。"""
    # 字符串/字符字面量先摘出：下面所有变换都是「文本级」正则，不区分
    # 代码与字面量。字面量里的 `{a,b}`、`(x : y)`、`空/真/假` 会被误改；
    # 更糟的是跨字面量的括号/花括号会被配成一对（实测 `添加JS接口` 的 JS 串）。
    _lits = []

    def _stash_lit(text):
        _lits.append(text)
        return '\x00%d\x00' % (len(_lits) - 1)

    def _stash(m):
        return _stash_lit(m.group(0))

    # [[...]] 先摘：它内部可能有引号，且转义要在摘普通字符串之前完成。
    body = re.sub(r'\[\[([^\]]*)\]\]',
                  lambda m: _stash_lit('"' + m.group(1).replace('"', chr(92) + '"') + '"'), body)
    body = _STR_LIT.sub(_stash, body)

    def rep_hash(m):
        tok = m.group(1)
        if tok == 'this':
            return 'this'
        if tok.startswith('<') and tok.endswith('>'):
            return tok[1:-1]
        return tok
    # 结绳编译期占位符 `#[debug]`/`#[date]`/`#[time]`/`#[line]`/`#[source]`：
    # 由结绳编译器替换。Java 里给合理默认值（`#[debug]` → false 等），
    # 否则 `#` 是非法字符，整个类语法错、javac 跳过全工程语义分析。
    _PLACEHOLDER = {'debug': 'false', 'date': '""', 'time': '0L',
                    'line': '0', 'source': '""'}
    body = re.sub(r'#\[([a-zA-Z_]+)\]',
                  lambda m: _PLACEHOLDER.get(m.group(1), 'null'), body)
    body = re.sub(r'@模板类型(\d*)', lambda m: 'T' + (m.group(1) or '1'), body)
    body = re.sub(r'#(<[^>]+>|[A-Za-z_一-鿿][A-Za-z0-9_一-鿿]*)', rep_hash, body)
    # 结绳字面量与逻辑词。用词边界，避免把 `空布局`(类名) 里的 `空` 换成 null。
    B = r'(?<![A-Za-z0-9_一-鿿])%s(?![A-Za-z0-9_一-鿿])'
    body = re.sub(r'等待\s+', '', body)               # 等待 = await 关键字，去掉
    # 调用实参里的空槽：`f(a, , ,b)` → `f(a, null, null, b)`（结绳允许省略参数）
    body = re.sub(r'(?<=[\w\u4e00-\u9fff)])\s*\(\s*([^()]*?)\s*\)',
                  lambda m: '(' + ','.join(x if x.strip() else 'null' for x in m.group(1).split(',')) + ')',
                  body) if re.search(r'\(\s*,|,\s*,|,\s*\)', body) else body
    body = re.sub('等待\\s+', '', body)   # 等待 = await 关键字
    body = re.sub(r'\(([\u4e00-\u9fffA-Za-z_][\w\u4e00-\u9fff]*(?:\[[^\]]*\])*)\s*:\s*([\u4e00-\u9fffA-Za-z_][\w\u4e00-\u9fff.]*)\)',
                  lambda m: '((%s)%s)' % (map_type(m.group(2), tparams), m.group(1)), body)  # (名[..] : 类型) 强转
    body = re.sub(r'=\s*code\s+', '= ', body)          # 常量 = code 表达式
    body = re.sub(r'\[\[([^\]]*)\]\]', lambda m: '"' + m.group(1).replace('"', chr(92) + '"') + '"', body)  # [[...]] 文本字面量
    body = re.sub(r'\(([一-鿿A-Za-z_][\w一-鿿]*)\s*:\s*([一-鿿A-Za-z_][\w一-鿿.]*)\)',
                  lambda m: '((%s)%s)' % (map_type(m.group(2), tparams), m.group(1)), body)  # (名 : 类型) 强转
    def _at_map(m):
        # `@X` 或 `@X.Y.Z`：只按**首段**判断是不是本库类名，其余（`.成员`）原样保留。
        # 反例：`#<@启动信息>.FLAG_ACTIVITY_NEW_TASK` 展开成 `@启动信息.FLAG_...`，
        # 贪婪正则会连 `.FLAG_...` 一起吃进组 1，导致查表失败、`@` 残留。
        full = m.group(1)
        head = full.split('.')[0]
        if head in CLASS_MAP:
            return map_type(head, tparams) + full[len(head):]
        return '@' + full

    JAVA_ANN = ('Override', 'Deprecated', 'SuppressWarnings', 'SafeVarargs', 'FunctionalInterface')

    def _at_map_decl(m):
        full = m.group(1)
        head = full.split('.')[0]
        if head in JAVA_ANN:
            return '@' + full
        if head in CLASS_MAP:
            return map_type(head, tparams) + full[len(head):]
        return map_type(head, tparams) + full[len(head):]

    body = re.sub(r'@([一-鿿A-Za-z_][一-鿿A-Za-z0-9_.]*)',
                  _at_map if in_code else _at_map_decl, body)
    if in_code:
        # 先处理 `X.成员`：X 是静态调用的限定符，保留壳名（`位图对象.静态工厂(...)`）。
        body = _CLASSNAME_DOT_RE.sub(lambda m: map_cls(m.group(1)), body)
        # 再处理裸类名（排除后跟 `.` 的，上面已处理）：多为类型位置 → 映射成目标类型。
        body = _CLASSNAME_RE.sub(lambda m: map_type_cls(m.group(1)), body)
    else:
        # 结绳表达式里的类引用（`设备信息.安卓版本号`、`new 高级适配器(...)`）。
        body = _CLASSNAME_DOT_RE.sub(lambda m: map_cls(m.group(1)), body)
        body = _CLASSNAME_NEW_RE.sub(lambda m: m.group(1) + map_type_cls(m.group(2)), body)
    for k, v in (('本对象', 'this'), ('且', '&&'), ('或', '||'), ('非', '!'), ('空', 'null'),
                 ('真', 'true'), ('假', 'false')):
        body = re.sub(B % k, v, body)
    # 结绳数组字面量 {a,b}（Java 里是初始化块语法，作表达式非法）
    body = re.sub(r'\{([^{};]*,[^{};]*)\}', lambda m: 'new Object[]{' + m.group(1) + '}', body)
    body = re.sub(r'new\s+([\w一-鿿<>\[\]]+)\[\]\s*new Object\[\]\{', lambda m: 'new ' + m.group(1) + '[]{', body)
    body = body.replace('new Object[] new Object[]{', 'new Object[]{')
    # 还原字面量
    body = re.sub(r'\x00(\d+)\x00', lambda m: _lits[int(m.group(1))], body)
    return body

def _paren_open(s):
    """字符串/字符字面量之外的「净开括号数」。>0 表示语句未闭合、需续行。"""
    return _STR_LIT.sub('', s).count('(') - _STR_LIT.sub('', s).count(')')


def extract_paren(s, start):
    """从 s[start]=='(' 起做括号配平，返回括号内文本。"""
    depth = 0
    for i in range(start, len(s)):
        if s[i] == '(':
            depth += 1
        elif s[i] == ')':
            depth -= 1
            if depth == 0:
                return s[start+1:i]
    return s[start+1:]

def split_top(s, sep=','):
    """按顶层分隔符切分（忽略括号/尖括号内）。"""
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        if ch == sep and depth == 0:
            out.append(cur); cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [x.strip() for x in out if x.strip()]


def _cross_refs(t):
    """返回本文件引用的 (其它包, 类名) 集合。

    判据：文本里出现某个**已知类名**（中文版是中文名，英文版是映射后的英文名），
    且它属于**本文件之外的包**。用于生成 import。
    """
    text = '\n'.join(t.out)
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
            if re.search(r'(?<![\w\u4e00-\u9fff])' + re.escape(j) + r'(?![\w\u4e00-\u9fff])', text):
                out.add((mapped_pkg, j))
    return out


def _load_lib_classes(src):
    """扫描源 .t，建立 结绳包名 -> 类名集合（供跨包 import 判定）。"""
    global _LIB_CLASSES
    _LIB_CLASSES = {}
    for p in glob.glob(os.path.join(src, '**', '*.t'), recursive=True):
        pkg = ''
        for ln in io.open(p, encoding='utf-8', errors='replace'):
            t = ln.strip()
            if t.startswith('包名'):
                pkg = t[2:].strip()
            m = re.match(r'^(?:类|公开类|抽象类)\s+([^\s:<]+)', t)
            if m and pkg:
                _LIB_CLASSES.setdefault(pkg, set()).add(m.group(1))


_LIB_CLASSES = {}

class Translator:
    def __init__(self, path):
        self.path = path
        self.lang = LANG
        self.lines = io.open(path, encoding='utf-8', errors='replace').read().split('\n')
        self.i = 0
        self.out = []
        self.pkg = ''
        self.embedded = False
        self.pending_embedded = False
        self.pending_static = False
        self.cur_ret = 'void'   # 当前方法返回类型（决定 `code expr` 是否补 return）
        self.try_open = False   # 容错处理() 开了 try，等 结束容错() 收
        self.alias = None
        self.used_names = set()
        self.used_class_names = set()
        self.cur_class_zh = ''
        self.cur_class_out = ''
        self.imports = []
        self.tparams = []
        # 块注释扫描。块起始用 `'*/' not in t`，同行闭合的 `/* x */ code` 保持原样。
        self.comment_lines = set()
        in_c = False
        for idx, ln in enumerate(self.lines):
            t = ln.strip()
            if in_c:
                self.comment_lines.add(idx)
                self.lines[idx] = ''
                if '*/' in t:
                    in_c = False
                continue
            if t.startswith('/*'):
                # 同行闭合判定必须从 index 2 起找 '*/'：
                # `/*/`（如 `/*//说明`）在偏移 1-2 处就有 `*/`，
                # 用 `'*/' not in t` 会把「块开始」误判成「已闭合」，
                # 结果两个分支都不命中，注释体被当 Java 原样输出。
                if '*/' in t[2:]:
                    self.comment_lines.add(idx)
                    self.lines[idx] = ''
                else:
                    in_c = True
                    self.comment_lines.add(idx)
                    self.lines[idx] = ''

    def peek(self, k=0):
        j = self.i + k
        return self.lines[j] if j < len(self.lines) else ''

    def next(self):
        v = self.peek(); self.i += 1; return v

    def _join(self, s):
        while _paren_open(s) > 0 and self.i < len(self.lines):
            s = s.rstrip() + ' ' + self.next().strip()
        return s

    def next_joined(self):
        """读一行；若括号未闭合，继续拼接后续行（结绳参数表可跨行）。"""
        v = self.next()
        while _paren_open(v) > 0 and self.i < len(self.lines):
            v = v.rstrip() + ' ' + self.next().strip()
        return v

    def emit(self, s, indent=0):
        # 构造器名跟随类名：结绳 code 块里写作 `public 中文类名(...)`，
        # 类名英文化后必须同步，否则 Java 判为「缺返回类型的方法声明」。
        if (self.cur_class_zh and self.cur_class_zh != self.cur_class_out
                and self.cur_class_zh in s):
            s = re.sub(r'(?<![\w一-鿿])' + re.escape(self.cur_class_zh)
                       + r'(?![\w一-鿿])', self.cur_class_out, s)
        self.out.append('    ' * indent + s)

    def parse(self):
        while self.i < len(self.lines):
            idx = self.i
            raw = self.next()
            s = raw.strip()
            if idx in self.comment_lines:
                # 块注释已被清空（见 __init__）；这里必须 emit 清空后的内容，
                # 否则会把 `/* ... 结束 方法*/` 这类含终止关键字的注释原样吐出来。
                self.emit(self.lines[idx].strip()); continue
            if not s:
                self.emit('')
                continue
            if s.startswith('//') or s.startswith('/*') or s.startswith('*'):
                self.emit(s); continue
            if s.startswith('包名'):
                self.pkg = map_pkg(s[2:].strip()); continue
            m = re.match(r'^@导入Java\("([^"]+)"\)', s)
            if m:
                self.imports.append(m.group(1)); continue
            if s.startswith('@'):
                self.handle_annotation(s); continue
            if s.startswith('类 ') or s.startswith('公开类 ') or s.startswith('抽象类 '):
                self.parse_class(s); continue
            # 顶层散落语句
            self.emit(s)

    def handle_annotation(self, s):
        # @导入Java 也可能写在**类体里**（源里大量如此），不能只认文件顶层。
        # 原先类体的 `@` 分支走这里，而这里不处理 @导入Java → 该类所有 import 丢失，
        # `Intent`/`Uri`/`Settings`/`StrictMode` 全变「找不到符号」。
        m_imp = re.match(r'^@导入Java\("([^"]+)"\)', s)
        if m_imp:
            self.imports.append(m_imp.group(1))
            return
        if s.startswith('@嵌入式代码'):
            # 必须写 pending_embedded：parse_method 开头用
            # `self.embedded = self.pending_embedded` 覆盖 self.embedded，
            # 只设 self.embedded 会被立刻冲掉，@嵌入式代码 形同不存在。
            self.pending_embedded = True
            return
        if s.startswith('@静态'):
            self.pending_static = True
            return
        if s.startswith('@指代类'):
            # 记录：本类直接包裹该 Java 类
            m = re.match(r'^@指代类\("([^"]+)"\)', s)
            if m:
                self.alias = m.group(1)
        # 其它注解（含 @废弃使用("...")、@虚拟方法）一律丢弃
        return

    # 结绳「块宏」：code 块不闭合（如 synchronized 开括号在方法A、闭括号在方法B），
    # 无法机械转译，需人工改写。按类名跳过。
    MACRO_CLASSES = {'线程锁'}

    # 跨方法「块宏」：结绳把 `new Runnable(){ public void run(){` 开在一个
    # @嵌入式代码 方法里、闭合的 `}})` 写在**另一个**方法里，Java 的方法边界
    # 无法表达。这些方法的**方法体丢弃**（保留空方法签名），类即可配平；
    # 同类的其它方法（如 是否处于主线程）照常生成，调用点才能解析。
    MACRO_METHODS = {
        '流程处理': {'容错运行', '容错处理', '结束容错',
                 '开始俘获异常', '俘获所有异常', '取俘获异常', '结束俘获异常',
                 '提交到新线程运行', '结束提交到新线程',
                 '提交到主线程运行', '提交到主线程运行2', '结束提交到主线程'},
        '网络请求': {'GET异步请求', 'POST异步请求', '结束网络请求'},
    }

    def parse_class(self, decl):
        # 类名可能带泛型实参（数组排序器<模板类型1>），先把泛型摘掉再取名字。
        m = re.match(r'^(类|公开类|抽象类)\s+([^\s:<]+)\s*(<[^>]*>)?\s*(?::\s*(.+))?$', decl)
        if not m:
            self.emit('// TODO 无法解析的类声明: ' + decl); return
        kw, name, tp, base = m.group(1), m.group(2), m.group(3), m.group(4)
        jname = map_cls(name)          # 输出用的类名（英文版会变）
        self.tparams = []
        if tp:
            for p in re.findall(r'模板类型(\d*)', tp):
                self.tparams.append('T' + (p or '1'))
        extends = ''
        if base:
            b = base.strip()
            # 继承目标也可能是 基类<模板类型1>
            bm = re.match(r'^([^\s<]+)\s*(<[^>]*>)?$', b)
            if bm:
                bname = bm.group(1)
                bargs = bm.group(2)
                if bargs:
                    jargs = ', '.join(map_type(x, self.tparams) for x in split_top(bargs[1:-1]))
                    extends = ' extends %s<%s>' % (map_cls(bname), jargs)
                else:
                    extends = ' extends ' + map_cls(bname)
            else:
                extends = ' extends ' + map_cls(b)
        # @指代类("X")：结绳「本类包装 Java 类 X」。无显式基类时让生成的类
        # extends X，否则 X 的方法/字段全不可见（实测约 1500 处「找不到符号」：
        # `this.append(...)`、`this.getWindow()`、`this.getSystemService(...)` 等）。
        # 基本类型别名（int/long/…）无法继承，跳过。
        # @指代类 优先于显式基类：它才是具体的 Android/JDK 平台类（Activity、
        # Application、Context…），提供 getWindow()/overridePendingTransition() 等
        # 平台 API；显式基类（如 安卓环境 : Context）只是同一平台类的薄抽象。
        # 若两者都写，取别名——否则平台方法全部「找不到符号」（实测 25 处 getWindow）。
        if self.alias and NON_EXTEND_KIND.get(name) == 'interface':
            # 目标是接口：Java 里只能 implements，`extends` 会报「此处需要接口」。
            # 实现接口后接口方法自动可调（结绳里这些类就是该接口）。
            al = self.alias.strip()
            extends = (extends.replace(' extends ', ' implements ') + ', ' + al) if extends else (' implements ' + al)
        elif self.alias and name not in NON_EXTEND:
            # 可继承：让包装类 extends 目标 Java 类，从而拿到其全部实例方法。
            al = self.alias.strip()
            if al not in ('int', 'long', 'short', 'byte', 'char', 'float',
                          'double', 'boolean', 'void'):
                extends = ' extends ' + al
        # name in NON_EXTEND：目标是 final/接口/枚举/私有构造器，不能 extends。
        # 不生成 extends —— 本类退化成「同名静态壳」（只承载静态工厂方法）；
        # 类型位置由 map_type_cls 映射成目标原生类型。
        # @指代类 是**逐类**的，用完必须清空：否则同一文件里后续所有类都会
        # 继承上一个类的 @指代类 目标（实测 进度条/拖动条/视频播放器/浏览框
        # 全被误挂上 图像缩放类型 的 ScaleType）。
        self.alias = None
        tdecl = ('<' + ', '.join(self.tparams) + '>') if self.tparams else ''
        JAVA_SIMPLE = {'UUID','Date','Timer','File','Thread','String','Integer','Long',
                       'Boolean','Double','Float','Object','Math','System','Runtime'}
        if self.alias and name in JAVA_SIMPLE and name not in self.used_class_names:
            name = name + '扩展'          # 与 java.* 简单名同名会遮蔽，加后缀区分
        self.used_class_names.add(name)
        if name in DROP_CLASSES or name in DROP_CLASSES_NO_EXTERNAL:
            # 与 QuickDevelop 模板重叠：跳过整个类（含类体），不产出文件。
            # 必须逐类判断——一个 .t 文件含多个类，被剔的未必是首个。
            depth = 1
            while self.i < len(self.lines) and depth > 0:
                tt = self.next().strip()
                if re.match(r'^(?:类|公开类|抽象类)\s', tt):
                    depth += 1
                elif tt == '结束 类':
                    depth -= 1
            return
        if name in self.MACRO_CLASSES:
            # 跳过类体，输出占位说明
            self.emit('')
            self.emit('// [需人工改写] 结绳块宏类：' + name)
            depth = 1
            while self.i < len(self.lines) and depth > 0:
                t = self.next().strip()
                if t.startswith(('类 ', '公开类 ', '抽象类 ')):
                    depth += 1
                elif t == '结束 类':
                    depth -= 1
            return
        self.emit('')
        self.used_names = set()
        # 记录「中文类名 → 输出类名」，供 subst 替换构造器名。
        self.cur_class_zh = name
        self.cur_class_out = jname
        self.emit('public %sclass %s%s%s {' % (
            'abstract ' if kw == '抽象类' else '', jname, tdecl, extends))
        # 类体
        in_code = False
        while self.i < len(self.lines):
            idx = self.i
            raw = self.next(); s = raw.strip()
            if s == '@code':
                in_code = True; continue
            if s == '@end':
                in_code = False; continue
            if in_code:
                # @code 块内是原生 Java，必须原样输出（只做 #x/字面量替换），
                # 否则 `@Override protected void f(){` 这类行会被 `@` 分支吞掉。
                self.emit(subst(s, [], self.tparams, in_code=True)); continue
            if idx in self.comment_lines:
                # 块注释已被清空（见 __init__）；这里必须 emit 清空后的内容，
                # 否则会把 `/* ... 结束 方法*/` 这类含终止关键字的注释原样吐出来。
                self.emit(self.lines[idx].strip()); continue
            if s == '' :
                self.emit(''); continue
            if s.startswith('//') or s.startswith('/*') or s.startswith('*'):
                self.emit(s); continue
            if s in ('结束 类',) or s == '结束类':
                break
            if s.startswith('@'):
                self.handle_annotation(s)
                continue
            if s.startswith('常量'):
                self.parse_const(s); continue
            if s.startswith('变量'):
                self.parse_var(s); continue
            if s.startswith('方法'):
                jm = self._join(s)
                mm = re.match(r'^方法\s+([^\s(]+)', jm)
                if mm and mm.group(1) in DROP_METHODS_NO_EXTERNAL.get(name, ()):
                    # 依赖库外缺失的 Java 类 → 整段跳过（方法一并去掉）
                    self._skip_method_body()
                    continue
                if mm and mm.group(1) in self.MACRO_METHODS.get(name, ()):
                    # 跨方法块宏 → 保留签名、丢弃方法体（类才能配平）
                    self.parse_method(jm, stub=True)
                    continue
                self.parse_method(jm); continue
            if s.startswith('属性读'):
                self.parse_prop_read(self._join(s)); continue
            if s.startswith('属性写'):
                self.parse_prop_write(self._join(s)); continue
            if s.startswith('定义事件'):
                self.parse_event(self._join(s)); continue
            if s.startswith('事件 '):
                # `事件 类型:名(params) [: ret]` → 覆写方法（参数表可能跨行）
                j = self._join(s)
                j = re.sub(r'^事件\s+[^\s:]+:', '方法 ', j)
                self.parse_method(j); continue
            if s.startswith('如果'):
                self.parse_if(s); continue
            if s.startswith('假如'):
                self.parse_switch(s); continue
            if s.startswith('容错处理'):
                self.emit('try {'); self.try_open = True; continue
            if s.startswith('结束容错'):
                if self.try_open:
                    self.emit('} catch (Exception e) { }'); self.try_open = False
                continue
            if s.startswith('循环'):
                self.parse_while(s); continue
            if s.startswith('返回'):
                r = s[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s.startswith('是 '):
                self.emit('return ' + subst(s[2:].strip(), [], self.tparams) + ';'); continue
            if s.startswith('code '):
                body = s[5:].strip()
                if not re.match(r'^(if|for|while|try|return|throw|\{)', body) and not body.endswith(';'):
                    self.emit('return ' + subst(body, [], self.tparams) + ';')
                else:
                    self.emit(subst(body, [], self.tparams))
                continue
            # 其它（表达式、赋值）
            self.emit(subst(s, [], self.tparams))
        self.emit('}')

    @staticmethod
    def infer_type(val):
        v = (val or '').strip()
        if re.match(r'^(0x[0-9a-fA-F]+|\d+)$', v): return 'int'
        if re.match(r'^\d+[lL]$', v): return 'long'
        if re.match(r'^\d+\.\d*[fF]?$', v): return 'double'
        if v.startswith('"'): return 'String'
        if v in ('真','假','true','false'): return 'boolean'
        return 'Object'

    def parse_const(self, s):
        self.pending_static = False
        m = re.match(r'^常量\s+([^\s:]+)\s*(?:(?::|为)\s*([^=]+))?\s*(?:=\s*(.+))?$', s)
        name, typ, val = m.group(1), (m.group(2) or '').strip(), (m.group(3) or '').strip()
        if val.startswith('code '):
            val = val[5:].strip()
        # 结绳的枚举惯用法：类里只有 `@静态 常量 X : 自身类名 = <整数>`。
        # 若按声明类型生成 `public static final 弹性布局_主轴对齐方式 左或上 = 0;`
        # 会报「int 无法转换为 弹性布局_主轴对齐方式」（该模式约 70 处）。
        # 这类常量值就是字面量，取**值的类型**（int 等）。
        if typ and typ.strip() == self.cur_class_zh and val:
            jt = self.infer_type(val)
        else:
            jt = map_type(typ, self.tparams) if typ else self.infer_type(val)
        mod = 'public static final'
        self.emit('%s %s %s%s;' % (mod, jt, name, (' = ' + subst(val, [], self.tparams)) if val else ''))

    BARE_ARRAY = re.compile(r'^([一-鿿A-Za-z_][一-鿿A-Za-z0-9_.]*)\[([^\]]+)\]\s+([一-鿿A-Za-z_][一-鿿A-Za-z0-9_]*)\s*(?:=\s*(.+?))?;?$')

    def parse_var(self, s):
        self.pending_static = False
        s = self._join(s)   # 结绳允许 `变量 x : 类型 = 表达式` 跨行续写
        m = re.match(r'^变量\s+([^\s:]+)\s*(?:(?::|为)\s*([^=]+))?\s*(?:=\s*(.+))?$', s)
        name, typ, val = m.group(1), (m.group(2) or '').strip(), (m.group(3) or '').strip()
        am = re.match(r'^(.+?)\[(.+)\]$', typ)   # 字节[1024] / 整数[宽度*高度]
        if am:
            jt = map_type(am.group(1).strip(), self.tparams)
            self.emit('%s[] %s = new %s[%s];' % (
                jt, name, jt, subst(am.group(2), [], self.tparams))); return
        jt = map_type(typ, self.tparams) if typ else self.infer_type(val)
        if val and re.match(r'^\(.*\)$', val) and jt not in ('int','long','short','byte','float','double','boolean','char','String','Object'):
            # 结绳构造语法：变量 x : 类型 = (a,b)  →  类型 x = new 类型(a,b)
            self.emit('%s %s = new %s%s;' % (jt, name, jt, val)); return
        self.emit('%s %s%s;' % (jt, name, (' = ' + subst(val, [], self.tparams)) if val else ''))

    def parse_params(self, ps):
        """结绳参数表 → (java参数列表, 参数名列表)"""
        out, names = [], []
        for p in split_top(ps):
            p = p.strip()
            if not p: continue
            # 分隔符取**最后一个** `:` 或 ` 为 `，兼容 `名:类型` 与 `A 为 B 为 对象`
            sep = None
            for cand in (' 为 ', ':'):
                k = p.rfind(cand)
                if k > 0:
                    sep = (k, len(cand)); break
            if sep:
                n = p[:sep[0]].strip()
                rest = p[sep[0] + sep[1]:].strip()
                vm = re.match(r'^(.+?)\s*=\s*(.+)$', rest)
                t = vm.group(1).strip() if vm else rest
                out.append('%s %s' % (map_type(t, self.tparams), n)); names.append(n)  # 默认值丢弃
            else:
                # 只有默认值、没写类型：`渲染类型=0` → Object 渲染类型 = 0
                dm = re.match(r'^([一-鿿A-Za-z_][\w一-鿿]*)\s*=\s*(.+)$', p)
                if dm:
                    out.append('Object ' + dm.group(1)); names.append(dm.group(1))
                else:
                    out.append('Object ' + p); names.append(p)
        return ', '.join(out), names

    def _skip_method_body(self):
        """跳过当前方法的整个方法体（直到 `结束 方法`），不产出任何代码。"""
        while self.i < len(self.lines):
            t = self.next().strip()
            if t in ('结束 方法', '结束方法'):
                return

    def parse_method(self, s, stub=False):
        s = self.next_joined() if False else s
        m = re.match(r'^方法\s+([^\s(]+)\s*\((.*)\)\s*(?:(?::|为)\s*(.+))?$', s)
        if not m:
            self.emit('// TODO 无法解析: ' + s); return
        name, ps, ret = m.group(1), m.group(2), (m.group(3) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'void'
        params, names = self.parse_params(ps)
        if stub:
            # 跨方法块宏的**定义**：只保留签名 + 空体，丢弃无法表达的方法体。
            # 调用点不受影响（parse_body 里另有处理），类花括号得以配平。
            self.pending_static = False
            self.pending_embedded = False
            self.emit('public %s%s %s(%s) { %s}' % (
                'static ' if self.pending_static else '', jret, name, params,
                '' if jret == 'void' else 'return null; '))
            self._skip_method_body()
            return
        # 运算符重载 → 具名方法
        self.embedded = self.pending_embedded; self.pending_embedded = False
        is_op = name in self.OPMAP
        name = self.op_name(name)
        if is_op:
            name = name + '_op'   # Java 无运算符重载；与具名方法并存会重名，统一加后缀
        # `@嵌入式代码 方法 f(...)`（无返回类型）等价于「表达式即返回值」：
        # 方法体只有一条 `code expr`，若按 void 生成会得到 `return expr;` → 类型错误。
        if self.embedded and jret == 'void':
            jret = 'Object'
        is_static = self.pending_static
        self.pending_static = False
        self.used_names.add(name)
        self.embedded = False
        self.cur_ret = jret
        self.emit('public %s%s %s(%s) {' % ('static ' if is_static else '', jret, name, params))
        self.parse_body()
        self.cur_ret = 'void'

    def parse_prop_read(self, s):
        self.pending_static = False
        m = re.match(r'^属性读\s+([^\s(]+)\s*\(\s*\)\s*(?:(?::|为)\s*(.+))?$', s)
        name, ret = m.group(1), (m.group(2) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'Object'
        self.embedded = self.pending_embedded; self.pending_embedded = False
        self.embedded = False
        self.cur_ret = jret
        self.emit('public %s %s() {' % (jret, name))
        self.parse_body()
        self.cur_ret = 'void'

    def parse_prop_write(self, s):
        self.pending_static = False
        m = re.match(r'^属性写\s+([^\s(]+)\s*\(([^)]*)\)$', s)
        name, ps = m.group(1), m.group(2)
        params, names = self.parse_params(ps)
        name = self.op_name(name)
        self.embedded = self.pending_embedded; self.pending_embedded = False
        self.emit('public void %s(%s) {' % (name, params))
        self.parse_body()

    OPMAP = {'+': '加', '-': '减', '*': '乘', '/': '除', '==': '等于', '!=': '不等于',
             '<': '小于', '>': '大于', '<=': '小于等于', '>=': '大于等于', '=': '赋值',
             '[]': '取索引', '[]=': '设索引', '?': '是'}

    def op_name(self, name):
        return self.OPMAP.get(name, name)

    def parse_event(self, s):
        self.pending_static = False
        m = re.match(r'^定义事件\s+([^\s(]+)\s*\(([^)]*)\)\s*(?:(?::|为)\s*(.+))?$', s)
        name, ps, ret = m.group(1), m.group(2), (m.group(3) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'void'
        params, names = self.parse_params(ps)
        if jret == 'void':
            self.emit('public void %s(%s) { } // 事件' % (name, params))
        else:
            self.emit('public %s %s(%s) { return %s; } // 事件' % (
                jret, name, params, 'false' if jret == 'boolean' else ('0' if jret in ('int','long','double','float') else 'null')))

    # 控制流/声明头行：以这些词开头且不以 `;` 结尾时，绝不能补 `;`
    # （`if (...)` 换行体、`else`、`catch (E e)`、`public void f()` 等）。
    HEAD_KW = ('if', 'else', 'for', 'while', 'switch', 'case', 'default', 'do',
               'try', 'catch', 'finally', 'synchronized', 'return', 'throw',
               'break', 'continue', 'class', 'interface', 'enum', 'public',
               'private', 'protected', 'static', 'final', 'abstract', 'native')

    @staticmethod
    def _needs_semi(b):
        """@code 块内该行是否缺分号（结绳允许省略，Java 不允许）。

        保守策略：只在「看起来是完整语句、且明显不是续行/控制流头」时才补。
        """
        t = b.strip()
        if not t:
            return False
        if t.endswith((';', '{', '}', ',', ':', '(')):
            return False
        if t.startswith(('//', '/*', '*', '}', '@')):
            return False            # 注释 / 注解（@Override 等）不补分号
        if t.startswith(('+', '-', '*', '/', '=', '.', '?', ':')):
            return False            # 续行（上一行的表达式没写完）
        if t.endswith(('+', '-', '*', '/', '=', '&&', '||', '.', '?', '&', '|', '=')):
            return False
        if re.search(r'\bnew$', t):
            return False            # `= new` 换行接构造器，属续行
        if _paren_open(t) > 0:
            return False            # 括号未闭合，后面还有续行
        for kw in Translator.HEAD_KW:
            if t == kw or t.startswith(kw + ' ') or t.startswith(kw + '('):
                return False        # 控制流/声明头行
        return True

    def consume_code_block(self):
        """消费 @code ... @end，块内是 Java（原样输出 + 补分号）。"""
        buf = []
        while self.i < len(self.lines):
            r2 = self.next(); s2 = r2.strip()
            if s2 == '@end':
                break
            buf.append(s2)
        if len(buf) == 1 and not re.match(r'^(if|for|while|try|return|throw|\{)', buf[0]) and not buf[0].endswith(';'):
            # 单表达式 @code：非 void 方法补 `return`；void 方法只作语句。
            pre = 'return ' if self.cur_ret != 'void' else ''
            self.emit(pre + subst(buf[0], [], self.tparams, in_code=True) + ';')
        else:
            for k, b in enumerate(buf):
                body = subst(b, [], self.tparams, in_code=True)
                # 下一条有效行（跳过注释与空行）
                nxt = ''
                for m2 in range(k + 1, len(buf)):
                    c = buf[m2].strip()
                    if c and not c.startswith(('//', '/*', '*')):
                        nxt = c; break
                if self._needs_semi(b):
                    if not (nxt.startswith(('{', '}', '[', ')', '+', '-', '*', '/', '.', '?', ':', '&', '|', '='))
                            or nxt.startswith(('else', 'catch', 'finally'))):
                        body += ';'
                elif body.endswith(';') and nxt.startswith(')'):
                    # 反向误补：`f(a, b;` 后面跟 `)` —— 去掉本行的 `;`
                    body = body[:-1]
                self.emit(body)

    def parse_body(self):
        """读方法/属性体，直到 结束 方法 / 结束 属性。"""
        self.try_open = False
        while self.i < len(self.lines):
            raw = self.next(); s = raw.strip()
            if s in ('结束 方法', '结束 属性', '结束 事件', '结束方法', '结束属性', '结束事件'):
                break
            if s == '@code':
                self.consume_code_block(); continue
            if s.startswith('code '):
                self.emit_stmt(s); continue
            if s == '' :
                self.emit(''); continue
            if s.startswith('//') or s.startswith('/*') or s.startswith('*'):
                self.emit(s); continue
            if s.startswith('如果'):
                self.parse_if(s); continue
            if s.startswith('假如'):
                self.parse_switch(s); continue
            if s.startswith('容错处理'):
                self.emit('try {'); self.try_open = True; continue
            if s.startswith('结束容错'):
                if self.try_open:
                    self.emit('} catch (Exception e) { }'); self.try_open = False
                continue
            if s.startswith('循环'):
                self.parse_while(s); continue
            if s.startswith('返回'):
                r = s[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s.startswith('变量'):
                self.parse_var(s); continue
            self.emit_stmt(s)
        # 结绳的 容错处理()/结束容错() 可能跨方法（开在方法A末尾、闭在方法B开头），
        # 那样花括号必失衡。方法结束时若 try 仍开着，就地补 catch 收口。
        if self.try_open:
            self.emit('} catch (Exception e) { }')
            self.try_open = False
        self.emit('}')

    def parse_if(self, s):
        cond = s[2:].strip()
        if cond.endswith('则'):
            cond = cond[:-1].strip()          # `如果 cond 则` → cond
        self.emit('if (%s) {' % subst(cond, [], self.tparams))
        while self.i < len(self.lines):
            raw = self.next(); s2 = raw.strip()
            if s2 == '结束 如果' or s2 == '结束如果':
                break
            if s2 == '@code':
                self.consume_code_block(); continue
            if s2.startswith('否则'):
                rest = s2[2:].strip()
                if rest.endswith('则'):
                    rest = rest[:-1].strip()
                if rest:
                    self.emit('} else if (%s) {' % subst(rest, [], self.tparams)); continue
                self.emit('} else {'); continue
            if s2.startswith('如果'):
                self.parse_if(s2); continue
            if s2.startswith('假如'):
                self.parse_switch(s2); continue
            if s2.startswith('容错处理'):
                self.emit('try {'); self.try_open = True; continue
            if s2.startswith('结束容错'):
                if self.try_open:
                    self.emit('} catch (Exception e) { }'); self.try_open = False
                continue
            if s2.startswith('循环'):
                self.parse_while(s2); continue
            if s2.startswith('返回'):
                r = s2[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s2.startswith('变量'):
                self.parse_var(s2); continue
            if s2 == '':
                self.emit(''); continue
            self.emit_stmt(s2)
        self.emit('}')

    def parse_switch(self, s):
        """`假如 表达式` ... `是 值` ... `结束 假如` → Java switch。"""
        expr = s[2:].strip()
        self.emit('switch (%s) {' % subst(expr, [], self.tparams))
        while self.i < len(self.lines):
            raw = self.next(); s2 = raw.strip()
            if s2 in ('结束 假如', '结束假如'):
                break
            if s2 == '':
                self.emit(''); continue
            if s2.startswith('//') or s2.startswith('/*') or s2.startswith('*'):
                self.emit(s2); continue
            if s2.startswith('是 '):
                self.emit('case %s:' % subst(s2[2:].strip(), [], self.tparams)); continue
            if s2.startswith('否则'):
                self.emit('default:'); continue
            if s2 == '@code':
                self.consume_code_block(); continue
            if s2.startswith('如果'):
                self.parse_if(s2); continue
            if s2.startswith('假如'):
                self.parse_switch(s2); continue
            if s2.startswith('循环'):
                self.parse_while(s2); continue
            if s2.startswith('返回'):
                r = s2[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s2.startswith('变量'):
                self.parse_var(s2); continue
            if s2.startswith('容错处理'):
                self.emit('try {'); self.try_open = True; continue
            if s2.startswith('结束容错'):
                if self.try_open:
                    self.emit('} catch (Exception e) { }'); self.try_open = False
                continue
            self.emit_stmt(s2)
        self.emit('}')

    def parse_while(self, s):
        m = re.match(r'^循环\s*\(', s)
        args = split_top(extract_paren(s, m.end() - 1)) if m else []
        if len(args) == 3:
            # 循环(i,0,N) → for (int i = 0; i < N; i++)
            v, a, b = args
            self.emit('for (int %s = %s; %s < %s; %s++) {' % (
                v, subst(a, [], self.tparams), v, subst(b, [], self.tparams), v))
        else:
            cond = args[0] if args else ''
            self.emit('while (%s) {' % subst(cond, [], self.tparams))
        while self.i < len(self.lines):
            raw = self.next(); s2 = raw.strip()
            if s2 == '结束 循环' or s2 == '结束循环':
                break
            if s2 == '@code':
                self.consume_code_block(); continue
            if s2.startswith('如果'):
                self.parse_if(s2); continue
            if s2.startswith('循环'):
                self.parse_while(s2); continue
            if s2.startswith('返回'):
                r = s2[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s2.startswith('变量'):
                self.parse_var(s2); continue
            if s2 == '':
                self.emit(''); continue
            self.emit_stmt(s2)
        self.emit('}')

    def emit_stmt(self, s):
        """结绳语句无分号；补上。已是 Java 的（以 ; { } 结尾）原样输出。"""
        s = self._join(s)      # 结绳允许语句跨行；括号未闭合时续读
        if s.startswith('code '):
            s = s[5:].strip()
            body = subst(s, [], self.tparams)
            if not re.match(r'^(if|for|while|try|synchronized|return|throw|\{|\})', s) and not body.endswith(';'):
                pre = 'return ' if self.cur_ret != 'void' else ''
                self.emit(pre + body + ';')
            else:
                self.emit(body)
            return
        body = subst(s, [], self.tparams)
        if s in ('是', '是;'):
            self.emit('return;'); return
        m = re.match(r'^是\s+(.+?);?$', s)
        if m:
            self.emit('return ' + subst(m.group(1), [], self.tparams) + ';'); return
        bm = self.BARE_ARRAY.match(s)
        if bm:
            jt = map_type(bm.group(1), self.tparams)
            size = subst(bm.group(2), [], self.tparams)
            nm, init = bm.group(3), bm.group(4)
            self.emit('%s[] %s = new %s[%s];' % (jt, nm, jt, size)); return
        if re.match(r'^(//|/\*|\*|\})', s) or body.endswith((';', '{', '}', ':')):
            self.emit(body); return
        self.emit(body + ';')



def _peek_class_name(path):
    for ln in io.open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'^\s*(?:类|公开类|抽象类)\s+([^\s:<]+)', ln)
        if m:
            return m.group(1)
    return ''


def _first_class_name(t):
    for ln in t.out:
        m = re.match(r'public (?:abstract )?class ([^\s<{]+)', ln.strip())
        if m:
            return m.group(1)
    return ''

def translate(path, outdir):
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
            if not re.search(r'(?<![\w.])' + re.escape(simple) + r'(?![\w])', body_all):
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
    # 按顶层 `public (abstract )?class ` 切分，各自成文件。
    NL = chr(10)
    body = '\n'.join(t.out)
    # 构造器名跟随类名：结绳 code 块里写作 `public 中文类名(...)`，
    # 类名英文化后必须同步，否则 Java 认为是缺返回类型的方法声明。
    if LANG != 'zh' and t.cur_class_zh and t.cur_class_zh != t.cur_class_out:
        body = re.sub(r'(?<![\w\u4e00-\u9fff])' + re.escape(t.cur_class_zh) + r'(?![\w\u4e00-\u9fff])',
                      t.cur_class_out, body)
    # 一个 .t 文件含多个顶层类，而 Java 每个 public 类必须独占同名文件。
    # 切分必须**花括号配平感知**：@code 块里可能有嵌套 Java 类（按行首切会
    # 误切），也可能有失衡的类（`网络请求`：两个 @嵌入式代码 异步方法只开
    # 不闭，闭合花括号写在第三个方法里）——朴素「深度 0 切」会把后续完好的
    # 类一起吞进失衡类，产出非法 Java。
    #
    # 算法：从每个顶层 class 行起向前累积到花括号首次 ≤0；到末尾仍未回 0
    # 则该类失衡。此时找**第一个**「起于该行即配平」的后续 class 救尾部
    # （失衡段交给写出阶段跳过），并从该行继续正常切分。
    def _split(pool):
        res = []
        i = 0
        while i < len(pool):
            if not re.match(r'^public (?:abstract )?class ', pool[i].strip()):
                i += 1
                continue
            bal = 0
            j = i
            while j < len(pool):
                bal += _brace_balance(pool[j])
                if bal <= 0:
                    break
                j += 1
            if j < len(pool) and bal == 0:
                res.append(NL.join(pool[i:j + 1]))
                i = j + 1
            else:
                cut = -1
                for k in range(i + 1, len(pool)):
                    if (re.match(r'^public (?:abstract )?class ', pool[k].strip())
                            and _brace_balance(NL.join(pool[k:])) == 0):
                        cut = k; break
                if cut > 0:
                    res.append(NL.join(pool[i:cut]))   # 失衡段（写出时跳过）
                    i = cut
                else:
                    res.append(NL.join(pool[i:]))      # 整段失衡
                    i = len(pool)
        return res

    parts = _split(body.split(NL))
    rel = t.pkg.replace('.', '/') if t.pkg else ''
    d = os.path.join(outdir, rel)
    os.makedirs(d, exist_ok=True)
    n = 0
    for p in parts:
        nm = re.search(r'public (?:abstract )?class ([^\s<{]+)', p)
        if not nm:
            continue
        if _brace_balance(p) != 0:
            sys.stderr.write('WARN 跳过不配平的类 %s（源文件花括号失衡）\n' % nm.group(1))
            continue
        fn = nm.group(1) + '.java'
        io.open(os.path.join(d, fn), 'w', encoding='utf-8').write('\n'.join(head) + '\n' + p)
        n += 1
    return map_cls(_first_class_name(t) or ''), n

if __name__ == '__main__':
    src = sys.argv[1]
    outdir = sys.argv[2]
    _load_lib_classes(src)
    files = glob.glob(os.path.join(src, '**', '*.t'), recursive=True)
    for f in sorted(files):
        try:
            fn, n = translate(f, outdir)
            print('%3d 类  %s' % (n, fn))
        except Exception as e:
            print('FAIL %s: %s' % (os.path.basename(f), e))
