# -*- coding: utf-8 -*-
"""第三轮去 abstract：修正「注释里出现 abstract 被误判」的问题。
先去注释再判断类体是否真有 abstract 方法；父类去 abstract 后子类才可能跟上，
故多轮迭代直到不再有变化。每轮编译，失败文件回退。

用法: python deabstract3.py
"""
import io, os, re, json, subprocess, shutil

REPO = 'D:/android/projecet_iade/android-code-studio'
SRC  = REPO + '/core/ticode/src/zh/java'
MAN  = 'D:/android/tmp/manifest.json'
BAK  = 'D:/android/tmp/abstract-bak3'

man = json.load(io.open(MAN, encoding='utf-8'))

def src_of(fqn):
    return SRC + '/' + '/'.join(fqn.split('.')) + '.java'

def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)   # 块注释
    s = re.sub(r'//[^\n]*', '', s)                # 行注释
    return s

def body_has_abstract(s):
    body = re.sub(r'public\s+abstract\s+class', 'public class', s)
    return re.search(r'\babstract\b', strip_comments(body)) is not None

def compile_all():
    env = dict(os.environ,
               JAVA_HOME='C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2',
               ANDROID_JAR='D:/android/platforms/android-36/android.jar')
    subprocess.run(['bash', REPO + '/tools/ticode-compile.sh', 'zh', '500'],
                   capture_output=True, cwd=REPO, env=env)
    t = io.open('D:/android/tmp/err-zh-utf8.txt', encoding='utf-8').read()
    bad = set()
    for m in re.finditer(r'^(D:[^:]+\.java):\d+: 错误:', t, re.M):
        bad.add(m.group(1).replace(chr(92), '/'))
    return bad

shutil.rmtree(BAK, ignore_errors=True)
os.makedirs(BAK, exist_ok=True)
总改 = 0
for rnd in range(1, 5):
    changed = []
    for c in man:
        if not c.get('abstract'):
            continue
        f = src_of(c['class'])
        if not os.path.exists(f):
            continue
        s = io.open(f, encoding='utf-8', errors='replace').read()
        if 'public abstract class' not in s:
            continue
        # jvm/Java* 实现的是 JDK 反射接口（ParameterizedType 等），抽象方法多；
        # 去了 abstract 就得补一堆桩，无收益 —— 直接排除。
        if '/jvm/Java' in f:
            continue
        if body_has_abstract(s):
            continue
        n = re.sub(r'public\s+abstract\s+class', 'public class', s, count=1)
        rel = f[len(SRC) + 1:].replace('/', '__')
        io.open(BAK + '/' + rel, 'w', encoding='utf-8').write(s)
        io.open(f, 'w', encoding='utf-8').write(n)
        c['abstract'] = False     # 供下一轮子类判断
        changed.append(f)
    if not changed:
        print('第 %d 轮: 无候选，收敛' % rnd)
        break
    bad = compile_all()
    print('第 %d 轮: 改 %d，编译错 %d' % (rnd, len(changed), len(bad)))
    总改 += len(changed) - len(bad)
    for b in sorted(bad):
        rel = b[len(SRC) + 1:].replace('/', '__') if SRC in b else None
        if rel and os.path.exists(BAK + '/' + rel):
            io.open(b, 'w', encoding='utf-8').write(io.open(BAK + '/' + rel, encoding='utf-8').read())
            # 回退后标记回 abstract，避免子类误判
            fqn = b[len(SRC) + 1:].replace('/', '.').replace('.java', '')
            for c in man:
                if c['class'] == fqn:
                    c['abstract'] = True
print('累计净去 abstract: %d' % 总改)

env = dict(os.environ,
           JAVA_HOME='C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2',
           ANDROID_JAR='D:/android/platforms/android-36/android.jar')
subprocess.run(['bash', REPO + '/tools/ticode-compile.sh', 'zh', '500'],
               capture_output=True, cwd=REPO, env=env)
t = io.open('D:/android/tmp/err-zh-utf8.txt', encoding='utf-8').read()
print('最终编译错(SDK36): %d' % len(re.findall(r'错误:', t)))
