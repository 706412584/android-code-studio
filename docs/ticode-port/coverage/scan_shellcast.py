# -*- coding: utf-8 -*-
"""判定 ticode 壳类强转哪些是真风险。

壳类 = `class 壳 extends 框架类型`（如 可绘制对象 extends Drawable）。
`(壳) expr` 只有在 expr 的**静态类型**不是壳时才会 CCE。分三种：
  A. expr = this.X()，X 声明返回「壳」        → 安全（返回 this）
  B. expr = this.X()，X 声明返回「框架父类型」 → 危险（返回的是框架实例）
  C. expr = 变量/字段                         → 看变量声明类型（本脚本不判）
输出 B 类，即"跑到了也会崩"的真风险。
"""
import io, glob, json, re, sys

REPO = 'D:/android/projecet_iade/android-code-studio'
SRC = REPO + '/core/ticode/src/zh/java'
MAN = 'D:/android/tmp/manifest.json'

man = json.load(io.open(MAN, encoding='utf-8'))
by = {c['class']: c for c in man}
FW = ('android.', 'androidx.', 'java.', 'javax.', 'dalvik.', 'org.json')

# 壳类名 -> 父类
shells = {}
for c in man:
    s = c.get('super', '')
    if any(s.startswith(p) for p in FW):
        shells[c['class'].split('.')[-1]] = c['class']

# 收集每个类的「方法名 -> 返回类型」，含父类（用于判断 this.X() 的静态类型）
def ret_of(fqn, mname):
    cur = fqn
    seen = set()
    while cur and cur not in seen:
        seen.add(cur)
        c = by.get(cur)
        if c:
            for m in c['methods']:
                if m['name'] == mname:
                    return m['ret']
        # 父类不在 manifest 里 → 未知
        cur = by.get(cur, {}).get('super')
    return None

cast_re = re.compile(r'\(\s*([\u4e00-\u9fff\w]+)\s*\)\s*this\s*\.\s*([\u4e00-\u9fff\w]+)\s*\(')
var_cast_re = re.compile(r'\(\s*([\u4e00-\u9fff\w]+)\s*\)\s*([\u4e00-\u9fff\w]+)\s*[;,)]')

risky, safe, unknown = [], [], []
for f in glob.glob(SRC + '/**/*.java', recursive=True):
    s = io.open(f, encoding='utf-8', errors='replace').read()
    fqn = None
    m = re.search(r'^package ([\w.]+);', s, re.M)
    cm = re.search(r'public (?:abstract )?class ([\u4e00-\u9fff\w]+)', s)
    if m and cm:
        fqn = m.group(1) + '.' + cm.group(1)
    rel = f[len(SRC) + 1:]
    for mm in cast_re.finditer(s):
        cls, meth = mm.group(1), mm.group(2)
        if cls not in shells:
            continue
        r = ret_of(fqn, meth) if fqn else None
        line = s[:mm.start()].count('\n') + 1
        rec = (rel, line, cls, meth, r)
        if r is None:
            unknown.append(rec)
        elif r.split('<')[0].strip() == cls:
            safe.append(rec)
        else:
            risky.append(rec)

out = io.open('D:/android/tmp/shellcast-scan.txt', 'w', encoding='utf-8')
def w(x): out.write(x + '\n')
w('壳类强转 (壳)this.X() 判定：')
w('  安全(返回壳自身): %d' % len(safe))
w('  危险(返回框架类型): %d' % len(risky))
w('  未知(父类不在清单): %d' % len(unknown))
w('')
w('=== 危险明细 ===')
for rel, ln, cls, meth, r in sorted(risky):
    w('%-46s:%-4d  (%s) this.%s()  → 声明返回 %s' % (rel, ln, cls, meth, r))
w('')
w('=== 未知明细（需人工看）===')
for rel, ln, cls, meth, r in sorted(unknown):
    w('%-46s:%-4d  (%s) this.%s()' % (rel, ln, cls, meth))
out.close()
print('safe=%d risky=%d unknown=%d -> shellcast-scan.txt' % (len(safe), len(risky), len(unknown)))
