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
    return TYPE_MAP.get(t, t)

def subst(body, params, tparams):
    """把 #x / #this / #<T> 换成 Java。"""
    def rep_hash(m):
        tok = m.group(1)
        if tok == 'this':
            return 'this'
        if tok.startswith('<') and tok.endswith('>'):
            return tok[1:-1]
        return tok
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
    JAVA_ANN = ('Override', 'Deprecated', 'SuppressWarnings', 'SafeVarargs', 'FunctionalInterface')
    body = re.sub(r'@([一-鿿A-Za-z_][一-鿿A-Za-z0-9_.]*)',
                  lambda m: ('@' + m.group(1)) if m.group(1) in JAVA_ANN else map_type(m.group(1), tparams),
                  body)  # @类型 / Java 注解
    for k, v in (('本对象', 'this'), ('且', '&&'), ('或', '||'), ('非', '!'), ('空', 'null'),
                 ('真', 'true'), ('假', 'false')):
        body = re.sub(B % k, v, body)
    # 结绳数组字面量 {a,b}（Java 里是初始化块语法，作表达式非法）
    body = re.sub(r'\{([^{};]*,[^{};]*)\}', lambda m: 'new Object[]{' + m.group(1) + '}', body)
    body = re.sub(r'new\s+([\w一-鿿<>\[\]]+)\[\]new Object\[\]\{', lambda m: 'new ' + m.group(1) + '[]{', body)
    body = body.replace('new Object[] new Object[]{', 'new Object[]{')
    return body

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

class Translator:
    def __init__(self, path):
        self.path = path
        self.lines = io.open(path, encoding='utf-8', errors='replace').read().split('\n')
        self.i = 0
        self.out = []
        self.pkg = ''
        self.embedded = False
        self.pending_embedded = False
        self.alias = None
        self.used_names = set()
        self.used_class_names = set()
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
            if t.startswith('/*') and '*/' not in t:
                in_c = True
                self.comment_lines.add(idx)
                self.lines[idx] = ''
            elif t.startswith('/*') and t.endswith('*/'):
                self.comment_lines.add(idx)
                self.lines[idx] = ''

    def peek(self, k=0):
        j = self.i + k
        return self.lines[j] if j < len(self.lines) else ''

    def next(self):
        v = self.peek(); self.i += 1; return v

    def _join(self, s):
        while s.count('(') > s.count(')') and self.i < len(self.lines):
            s = s.rstrip() + ' ' + self.next().strip()
        return s

    def next_joined(self):
        """读一行；若括号未闭合，继续拼接后续行（结绳参数表可跨行）。"""
        v = self.next()
        while v.count('(') > v.count(')') and self.i < len(self.lines):
            v = v.rstrip() + ' ' + self.next().strip()
        return v

    def emit(self, s, indent=0):
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
                self.pkg = out_package(s[2:].strip()); continue
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
        if s.startswith('@嵌入式代码'):
            self.embedded = True
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

    def parse_class(self, decl):
        # 类名可能带泛型实参（数组排序器<模板类型1>），先把泛型摘掉再取名字。
        m = re.match(r'^(类|公开类|抽象类)\s+([^\s:<]+)\s*(<[^>]*>)?\s*(?::\s*(.+))?$', decl)
        if not m:
            self.emit('// TODO 无法解析的类声明: ' + decl); return
        kw, name, tp, base = m.group(1), m.group(2), m.group(3), m.group(4)
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
                    extends = ' extends %s<%s>' % (bname, jargs)
                else:
                    extends = ' extends ' + bname
            else:
                extends = ' extends ' + b
        tdecl = ('<' + ', '.join(self.tparams) + '>') if self.tparams else ''
        JAVA_SIMPLE = {'UUID','Date','Timer','File','Thread','String','Integer','Long',
                       'Boolean','Double','Float','Object','Math','System','Runtime'}
        if self.alias and name in JAVA_SIMPLE and name not in self.used_class_names:
            name = name + '扩展'          # 与 java.* 简单名同名会遮蔽，加后缀区分
        self.used_class_names.add(name)
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
        self.emit('public %sclass %s%s%s {' % (
            'abstract ' if kw == '抽象类' else '', name, tdecl, extends))
        # 类体
        while self.i < len(self.lines):
            idx = self.i
            raw = self.next(); s = raw.strip()
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
                self.pending_ann = s
                continue
            if s.startswith('常量'):
                self.parse_const(s); continue
            if s.startswith('变量'):
                self.parse_var(s); continue
            if s.startswith('方法'):
                self.parse_method(self._join(s)); continue
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
            if s == '@code' or s == '@end':
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
        m = re.match(r'^常量\s+([^\s:]+)\s*(?::\s*([^=]+))?\s*(?:=\s*(.+))?$', s)
        name, typ, val = m.group(1), (m.group(2) or '').strip(), (m.group(3) or '').strip()
        if val.startswith('code '):
            val = val[5:].strip()
        jt = map_type(typ, self.tparams) if typ else self.infer_type(val)
        mod = 'public static final'
        self.emit('%s %s %s%s;' % (mod, jt, name, (' = ' + subst(val, [], self.tparams)) if val else ''))

    BARE_ARRAY = re.compile(r'^([一-鿿A-Za-z_][一-鿿A-Za-z0-9_.]*)\[([^\]]+)\]\s+([一-鿿A-Za-z_][一-鿿A-Za-z0-9_]*)\s*(?:=\s*(.+?))?;?$')

    def parse_var(self, s):
        m = re.match(r'^变量\s+([^\s:]+)\s*(?::\s*([^=]+))?\s*(?:=\s*(.+))?$', s)
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

    def parse_method(self, s):
        s = self.next_joined() if False else s
        m = re.match(r'^方法\s+([^\s(]+)\s*\((.*)\)\s*(?:(?::|为)\s*(.+))?$', s)
        if not m:
            self.emit('// TODO 无法解析: ' + s); return
        name, ps, ret = m.group(1), m.group(2), (m.group(3) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'void'
        params, names = self.parse_params(ps)
        # 运算符重载 → 具名方法
        self.embedded = self.pending_embedded; self.pending_embedded = False
        is_op = name in self.OPMAP
        name = self.op_name(name)
        if is_op:
            name = name + '_op'   # Java 无运算符重载；与具名方法并存会重名，统一加后缀
        self.used_names.add(name)
        self.embedded = False
        self.emit('public %s %s(%s) {' % (jret, name, params))
        self.parse_body()

    def parse_prop_read(self, s):
        m = re.match(r'^属性读\s+([^\s(]+)\s*\(\s*\)\s*(?:(?::|为)\s*(.+))?$', s)
        name, ret = m.group(1), (m.group(2) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'Object'
        self.embedded = self.pending_embedded; self.pending_embedded = False
        self.embedded = False
        self.emit('public %s %s() {' % (jret, name))
        self.parse_body()

    def parse_prop_write(self, s):
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
        m = re.match(r'^定义事件\s+([^\s(]+)\s*\(([^)]*)\)\s*(?:(?::|为)\s*(.+))?$', s)
        name, ps, ret = m.group(1), m.group(2), (m.group(3) or '').strip()
        jret = map_type(ret, self.tparams) if ret else 'void'
        params, names = self.parse_params(ps)
        self.emit('public %s %s(%s) { return %s; } // 事件' % (
            jret, name, params, 'false' if jret == 'boolean' else ('0' if jret in ('int','long','double','float') else 'null')))

    def consume_code_block(self):
        """消费 @code ... @end，块内是 Java。"""
        buf = []
        while self.i < len(self.lines):
            r2 = self.next(); s2 = r2.strip()
            if s2 == '@end':
                break
            buf.append(s2)
        if len(buf) == 1 and not re.match(r'^(if|for|while|try|return|throw|\{)', buf[0]) and not buf[0].endswith(';'):
            self.emit('return ' + subst(buf[0], [], self.tparams) + ';')
        else:
            for b in buf:
                self.emit(subst(b, [], self.tparams))

    def parse_body(self):
        """读方法/属性体，直到 结束 方法 / 结束 属性。"""
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
            if s.startswith('循环'):
                self.parse_while(s); continue
            if s.startswith('返回'):
                r = s[2:].strip()
                self.emit('return;' if r in ('', '()') else 'return ' + subst(r, [], self.tparams) + ';'); continue
            if s.startswith('变量'):
                self.parse_var(s); continue
            self.emit_stmt(s)
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
            if s2 == '否则':
                self.emit('} else {'); continue
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
                self.emit('return ' + body + ';')
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

def translate(path, outdir):
    t = Translator(path)
    t.parse()
    # 组装
    head = []
    if t.pkg:
        head.append('package %s;' % t.pkg)
    head.append('')
    for imp in t.imports:
        if imp.endswith('.*'):
            head.append('import %s;' % imp)
        else:
            head.append('import %s;' % imp)
    head.append('')
    # 一个 .t 文件含多个顶层类，而 Java 每个 public 类必须独占同名文件——
    # 按顶层 `public (abstract )?class ` 切分，各自成文件。
    NL = chr(10)
    body = '\n'.join(t.out)
    # 只在**花括号深度 0** 处切分：@code 块里可能有嵌套 Java 类
    # （如 `public class ItemClickListener extends ... {`），按行首切会把它切成独立文件。
    parts, cur, depth = [], [], 0
    for ln in body.split(NL):
        st = ln.strip()
        if depth == 0 and re.match(r'^public (?:abstract )?class ', st):
            if cur:
                parts.append(NL.join(cur))
            cur = [ln]
        else:
            cur.append(ln)
        depth += ln.count('{') - ln.count('}')
    if cur:
        parts.append(NL.join(cur))
    rel = t.pkg.replace('.', '/') if t.pkg else ''
    d = os.path.join(outdir, rel)
    os.makedirs(d, exist_ok=True)
    n = 0
    for p in parts:
        nm = re.search(r'public (?:abstract )?class ([^\s<{]+)', p)
        if not nm:
            continue
        fn = nm.group(1) + '.java'
        io.open(os.path.join(d, fn), 'w', encoding='utf-8').write('\n'.join(head) + '\n' + p)
        n += 1
    return (os.path.basename(path).replace('.t', '')), n

if __name__ == '__main__':
    src = sys.argv[1]
    outdir = sys.argv[2]
    files = glob.glob(os.path.join(src, '**', '*.t'), recursive=True)
    for f in sorted(files):
        try:
            fn, n = translate(f, outdir)
            print('%3d 类  %s' % (n, fn))
        except Exception as e:
            print('FAIL %s: %s' % (os.path.basename(f), e))
