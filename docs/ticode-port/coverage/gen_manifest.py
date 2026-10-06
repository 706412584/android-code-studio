# -*- coding: utf-8 -*-
"""从 ticode 源码生成「全类全方法清单」，供设备端反射覆盖驱动使用。

输出 JSON：[{ "class": FQN, "abstract": bool, "super": 父类, "ctors": [[paramTypes]],
             "methods": [{"name":..., "static":bool, "params":[types], "ret":...}] }]
"""
import io, glob, re, json, sys

SRC = sys.argv[1] if len(sys.argv) > 1 else 'D:/android/projecet_iade/android-code-studio/core/ticode/src/zh/java'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'D:/android/tmp/manifest.json'

PRIM = {'int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'void'}


def split_params(s):
    s = s.strip()
    if not s:
        return []
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur.strip()); cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def ptype(p):
    """从 `Type name` 取 Type"""
    toks = p.split()
    return ' '.join(toks[:-1]) if len(toks) >= 2 else toks[0]


classes = []
for f in sorted(glob.glob(SRC + '/**/*.java', recursive=True)):
    s = io.open(f, encoding='utf-8', errors='replace').read()
    m = re.search(r'^package ([\w.]+);', s, re.M)
    pkg = m.group(1) if m else ''
    cm = re.search(r'public (abstract )?class ([\u4e00-\u9fff\w]+)(?:<[^>]*>)?\s*(?:extends\s+([\w.]+))?', s)
    if not cm:
        continue
    is_abs, cls, sup = bool(cm.group(1)), cm.group(2), cm.group(3) or 'java.lang.Object'
    fqn = (pkg + '.' + cls) if pkg else cls

    ctors = []
    for mm in re.finditer(r'public\s+' + re.escape(cls) + r'\s*\(([^)]*)\)', s):
        ctors.append([ptype(p) for p in split_params(mm.group(1))])

    methods = []
    for mm in re.finditer(
            r'public\s+(static\s+)?(?:final\s+)?([\w\[\]<>.]+)\s+([\u4e00-\u9fff\w]+)\s*\(([^)]*)\)', s):
        is_static = bool(mm.group(1))
        ret, name, params = mm.group(2), mm.group(3), mm.group(4)
        methods.append({
            'name': name, 'static': is_static,
            'ret': ret, 'params': [ptype(p) for p in split_params(params)],
        })

    classes.append({'class': fqn, 'abstract': is_abs, 'super': sup,
                    'ctors': ctors, 'methods': methods})

io.open(OUT, 'w', encoding='utf-8').write(json.dumps(classes, ensure_ascii=False, indent=1))
n_m = sum(len(c['methods']) for c in classes)
print('类 %d，方法 %d → %s' % (len(classes), n_m, OUT))
