# -*- coding: utf-8 -*-
"""全量扫描 ticode 壳类强转 (壳) EXPR，按表达式形式分类，找漏网的运行时 CCE。

壳类 S extends 框架类型 N。`(S) expr` 只有在 expr 的运行时类型是 S（或其子类）时才安全。
分类：
  SAFE-newS      (S) new S(...)                      安全
  SAFE-thisM     (S) this.M(...)  且 M 声明返回 S      安全（返回 this）
  SAFE-upcast    (S) 壳实例变量   变量声明类型是 S      安全
  DANGER-newN    (S) new N(...)                     必崩（N 不是 S）
  DANGER-thisM   (S) this.M(...)  且 M 返回非 S        必崩
  DANGER-var     (S) 变量/字段     声明类型是 N/Object   必崩
  UNKNOWN        其它形式（方法链、数组元素、三元…）    需人工看
"""
import io, glob, json, re
from collections import Counter, defaultdict

REPO = 'D:/android/projecet_iade/android-code-studio'
SRC = REPO + '/core/ticode/src/zh/java'
MAN = 'D:/android/tmp/manifest.json'

man = json.load(io.open(MAN, encoding='utf-8'))
by = {c['class']: c for c in man}
FW = ('android.', 'androidx.', 'java.', 'javax.', 'dalvik.', 'org.json')

# 壳类名 -> (fqn, 原生父类型)
shells = {}
for c in man:
    s = c.get('super', '')
    if s and any(s.startswith(p) for p in FW):
        shells[c['class'].split('.')[-1]] = (c['class'], s)

def ret_of(fqn, mname):
    cur, seen = fqn, set()
    while cur and cur not in seen:
        seen.add(cur)
        c = by.get(cur)
        if c:
            for m in c['methods']:
                if m['name'] == mname:
                    return m['ret']
        cur = by.get(cur, {}).get('super')
    return None

def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s

# 匹配 (壳) EXPR —— EXPR 取到 逗号/分号/右括号 前的片段
cast_re = re.compile(r'\(\s*([\u4e00-\u9fff\w]+)\s*\)\s*([^;,\n]{1,80})')

results = defaultdict(list)
for f in sorted(glob.glob(SRC + '/**/*.java', recursive=True)):
    raw = io.open(f, encoding='utf-8', errors='replace').read()
    s = strip_comments(raw)
    pkg = re.search(r'^package ([\w.]+);', raw, re.M)
    cls = re.search(r'public (?:abstract )?class ([\u4e00-\u9fff\w]+)', raw)
    fqn = (pkg.group(1) + '.' + cls.group(1)) if (pkg and cls) else None
    rel = f[len(SRC) + 1:]
    # 局部变量声明：类型 名 = ...   （用于 DANGER-var 判定）
    for mm in cast_re.finditer(s):
        shell, expr = mm.group(1), mm.group(2).strip()
        if shell not in shells:
            continue
        line = s[:mm.start()].count('\n') + 1
        rec = (rel, line, shell, expr[:60])
        if re.match(r'new\s+' + re.escape(shell) + r'\b', expr):
            results['SAFE-newS'].append(rec); continue
        m2 = re.match(r'this\s*\.\s*([\u4e00-\u9fff\w]+)\s*\(', expr)
        if m2:
            r = ret_of(fqn, m2.group(1)) if fqn else None
            if r and r.split('<')[0].strip() == shell:
                results['SAFE-thisM'].append(rec)
            elif r:
                results['DANGER-thisM'].append(rec + (r,))
            else:
                results['UNKNOWN'].append(rec + ('thisM:' + m2.group(1),))
            continue
        m3 = re.match(r'new\s+([\w.]+)', expr)
        if m3:
            results['DANGER-newN'].append(rec + (m3.group(1),)); continue
        if re.fullmatch(r'[\u4e00-\u9fff\w]+', expr):
            # 简单变量：查声明类型
            decl = re.search(r'\b([\w.\[\]<>]+)\s+' + re.escape(expr) + r'\s*[=;)]', s)
            dt = decl.group(1).split('<')[0].strip() if decl else None
            if dt and dt.split('.')[-1] == shell:
                results['SAFE-upcast'].append(rec + (dt,))
            elif dt and (any(dt.startswith(p) for p in FW) or dt == 'Object'):
                results['DANGER-var'].append(rec + (dt,))
            else:
                results['UNKNOWN'].append(rec + ('var:' + str(dt),))
            continue
        results['UNKNOWN'].append(rec + (expr[:40],))

out = io.open('D:/android/tmp/allcasts-scan.txt', 'w', encoding='utf-8')
def w(x): out.write(x + '\n')
w('壳类强转全量分类：')
for k in sorted(results):
    w('  %-14s %d' % (k, len(results[k])))
for k in ['DANGER-newN', 'DANGER-thisM', 'DANGER-var', 'UNKNOWN']:
    w('')
    w('=== %s (%d) ===' % (k, len(results[k])))
    for rec in sorted(results[k]):
        w('  ' + '  '.join(str(x) for x in rec))
out.close()
for k in sorted(results):
    print('%-14s %d' % (k, len(results[k])))
print('-> allcasts-scan.txt')
