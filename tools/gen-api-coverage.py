# -*- coding: utf-8 -*-
"""生成 ApiCoverage.java：调用 ticode 每个 public static 方法一次。

目的：让 javac 逐个校验**全部 API 签名**（参数类型/重载/可见性）。
编译通过 = 整个 API 面在调用侧是自洽的。不运行（很多方法触 Android 运行时）。

用法: python gen_api_coverage.py <srcRoot> <outFile>
"""
import io, os, re, sys, glob

SRC = sys.argv[1] if len(sys.argv) > 1 else 'D:/android/projecet_iade/android-code-studio/core/ticode/src/zh/java'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'D:/android/tmp/api-coverage/ApiCoverage.java'

# 1) 类名 → 全限定名
cls2fqn = {}
imports = {}  # 短名 → FQN（来自 ticode 源码的 import）
for f in glob.glob(SRC + '/**/*.java', recursive=True):
    s = io.open(f, encoding='utf-8', errors='replace').read(4000)
    m = re.search(r'^package\s+([\w.]+);', s, re.M)
    pkg = m.group(1) if m else ''
    for mm in re.finditer(r'public (?:abstract )?class ([\u4e00-\u9fff\w]+)', s):
        cls2fqn[mm.group(1)] = (pkg + '.' + mm.group(1)) if pkg else mm.group(1)
    # 收集 import（含 android.* / java.* / org.json.* 等）
    for im in re.finditer(r'^import\s+(?:static\s+)?([\w.]+);', s, re.M):
        fq = im.group(1)
        imports[fq.rsplit('.', 1)[-1]] = fq
    # 嵌套类型：`interface X` / `class X` 出现在文件内 → Outer.X
    outer = re.search(r'public (?:abstract )?class ([\u4e00-\u9fff\w]+)', s)
    if outer:
        outer_fqn = (pkg + '.' + outer.group(1)) if pkg else outer.group(1)
        for nm in re.finditer(r'(?:public\s+)?(?:static\s+)?interface\s+([\w]+)', s):
            imports[nm.group(1)] = outer_fqn + '.' + nm.group(1)

PRIM = {'int': '(int)0', 'long': '(long)0', 'short': '(short)0', 'byte': '(byte)0',
        'char': '(char)0', 'boolean': '(boolean)false', 'float': '(float)0', 'double': '(double)0',
        'void': None}


def arg_for(t):
    t = t.strip()
    if not t:
        return None
    base = t.rstrip('[]').strip()
    is_array = t.endswith('[]')
    if base in PRIM:
        # 原始类型数组必须给真数组（`new byte[0]`），给标量会编译失败
        return 'new %s[0]' % base if is_array else PRIM[base]
    # 引用类型：强转 null，强转类型用 FQN 以消歧重载
    if base in cls2fqn:
        fqn = cls2fqn[base]
    elif base in imports:
        fqn = imports[base]
    else:
        fqn = base
    if is_array:
        return '(%s[])null' % fqn
    return '(%s)null' % fqn


calls = []
skipped = 0


def strip_nested_classes(src):
    """把**嵌套类体**挖空，只留外层类体。

    只看顶层类的 public static 方法——嵌套类（如 `static class FileChooseUtil`）
    的成员不能由外层类名调用，扫描时必须排除。
    做法：找到外层类体的起止，在其中找出深度为 1 处的 `class/interface/enum`
    声明并删掉其整个体。
    """
    m = re.search(r'(?:public\s+|abstract\s+)*class\s+[\w\u4e00-\u9fff]+', src)
    if not m:
        return src
    b = src.find('{', m.end())
    if b < 0:
        return src
    # 外层类体起止
    depth = 0; j = b
    while j < len(src):
        if src[j] == '{': depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0: break
        j += 1
    head, body, tail = src[:b + 1], src[b + 1:j], src[j:]

    # 在 body 内挖掉嵌套类
    out = []
    k = 0
    while True:
        nm = re.search(r'\n[^\n{}]*?\b(?:class|interface|enum)\s+[\w\u4e00-\u9fff]+', body[k:])
        if not nm:
            out.append(body[k:]); break
        start = k + nm.start()
        nb = body.find('{', k + nm.end())
        if nb < 0:
            out.append(body[k:]); break
        d = 0; p = nb
        while p < len(body):
            if body[p] == '{': d += 1
            elif body[p] == '}':
                d -= 1
                if d == 0: break
            p += 1
        out.append(body[k:start])
        k = p + 1
    return head + ''.join(out) + tail
# 2) 扫描每个类的 public static 方法
for f in sorted(glob.glob(SRC + '/**/*.java', recursive=True)):
    s = io.open(f, encoding='utf-8', errors='replace').read()
    m = re.search(r'^package\s+([\w.]+);', s, re.M)
    pkg = m.group(1) if m else ''
    m = re.search(r'public (?:abstract )?class ([\u4e00-\u9fff\w]+)', s)
    if not m:
        continue
    cls = m.group(1)
    fqn = (pkg + '.' + cls) if pkg else cls
    # 先剔掉嵌套类体（否则嵌套类的 public static 方法会被误挂到外层类上）
    s = strip_nested_classes(s)
    for mm in re.finditer(
            r'public\s+static\s+(?:final\s+)?([\w\[\]<>.]+)\s+([\u4e00-\u9fff\w]+)\s*\(([^)]*)\)',
            s):
        ret, name, params = mm.group(1), mm.group(2), mm.group(3).strip()
        args = []
        ok = True
        if params:
            # 按顶层逗号切分（忽略泛型内的逗号）
            depth = 0
            cur = ''
            parts = []
            for ch in params:
                if ch in '<([':
                    depth += 1
                elif ch in '>)]':
                    depth -= 1
                if ch == ',' and depth == 0:
                    parts.append(cur); cur = ''
                else:
                    cur += ch
            if cur.strip():
                parts.append(cur)
            for p in parts:
                # `Type 名` 或 `Type`
                toks = p.strip().split()
                ptype = ' '.join(toks[:-1]) if len(toks) >= 2 else toks[0]
                a = arg_for(ptype)
                if a is None:
                    ok = False; break
                args.append(a)
        if not ok:
            skipped += 1
            continue
        calls.append('    %s.%s(%s);' % (fqn, name, ', '.join(args)))

calls = sorted(set(calls))
body = '\n'.join(calls)
out = '''/*
 * 自动生成 —— 不要手改。见 tools/gen-api-coverage.py。
 *
 * 调用 ticode 每个 public static 方法一次，供 javac 校验全部 API 签名。
 * 只编译、不运行（许多方法依赖 Android 运行时）。
 */
public class ApiCoverage {
  // throws Exception：部分 ticode 方法声明了受检异常（IOException 等）
  public static void all() throws Exception {
%s
  }
}
''' % body

os.makedirs(os.path.dirname(OUT), exist_ok=True)
io.open(OUT, 'w', encoding='utf-8').write(out)
print('生成调用点: %d 个（跳过无法解析参数的: %d）' % (len(calls), skipped))
