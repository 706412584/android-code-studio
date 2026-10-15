#!/usr/bin/env python3
"""校验两个 APK 之间的资源 ID 是否「对齐」。

背景（这是热更新能否携带资源变更的判据）：
    R.string.foo 编译后是内联进调用点的字面量。热更新补丁只带差分 dex——
    未变更的类仍在设备上跑，它们内联的是**旧版本**的 ID。若新版本把某个 ID
    分配给了别的资源，旧类就会显示错误的文字（实测：主页「打开项目」显示成
    「找不到未解析的类名」），且完全静默。

    「对齐」的定义：旧版本里每个资源在新版本里**仍是同一个 ID**。
    新版本新增的资源可以占用新 ID（不与旧 ID 冲突即可）。

    启用 aapt2 --stable-ids 后这个条件由构建保证（实测 0 漂移）；本脚本是
    **发布前的独立校验**——构建配置可能失效（参数没透传、映射文件丢失、
    AGP 行为变化），那时必须拦住带资源的补丁，而不是相信配置一定生效。

用法：
    python3 tools/hotupdate-check-resource-alignment.py <base.apk> <new.apk>

退出码：
    0 = 对齐（允许补丁携带资源变更）
    1 = 不对齐（必须拒绝带资源的补丁）
    2 = 无法判定（工具/解析失败——按不对齐处理，宁可回退整包）
"""

import re
import subprocess
import sys
from pathlib import Path

RESOURCE_RE = re.compile(r"^\s+resource (0x[0-9a-fA-F]{8}) (\S+)\s*$")


def find_aapt2() -> str:
    import os

    def versions_desc(paths):
        # 按版本号排序（不是字符串排序：字符串会把 9.0.0 排在 10.0.0 之后）。
        def key(p):
            parts = p.parent.name.split(".")
            return tuple(int(x) if x.isdigit() else 0 for x in parts)
        return sorted(paths, key=key, reverse=True)

    candidates = []
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        bt = Path(sdk) / "build-tools"
        if bt.is_dir():
            # 精确匹配 aapt2 / aapt2.exe——`aapt2*` 会误配 aapt2-proto 等同名目录。
            for name in ("aapt2", "aapt2.exe"):
                candidates.extend(versions_desc(bt.glob("*/" + name)))
    local = Path("D:/android/build-tools")
    if local.is_dir():
        candidates.extend(versions_desc(local.glob("*/aapt2.exe")))
    for c in candidates:
        if c.is_file():
            return str(c)
    return ""


def load_ids(aapt2: str, apk: str) -> dict:
    """返回 {type/name: id}；失败抛异常。"""
    r = subprocess.run(
        [aapt2, "dump", "resources", apk],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if r.returncode != 0:
        raise RuntimeError(f"aapt2 dump 失败（{r.returncode}）：{r.stderr[:200]}")
    out = {}
    for line in r.stdout.splitlines():
        m = RESOURCE_RE.match(line)
        if m:
            out[m.group(2)] = m.group(1).lower()
    if not out:
        raise RuntimeError("未解析到任何资源——输出格式可能已变")
    return out


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base_apk, new_apk = sys.argv[1], sys.argv[2]
    for p in (base_apk, new_apk):
        if not Path(p).is_file():
            print(f"找不到 APK：{p}")
            return 2

    aapt2 = find_aapt2()
    if not aapt2:
        print("找不到 aapt2（设置 ANDROID_HOME）")
        return 2

    try:
        base = load_ids(aapt2, base_apk)
        new = load_ids(aapt2, new_apk)
    except Exception as e:
        print(f"无法判定：{e}")
        return 2

    # 对齐判据：base 里每个资源在新版里仍映射到同一 ID。
    moved = []
    for name, old_id in base.items():
        new_id = new.get(name)
        if new_id is None:
            # 资源被删除——旧代码引用它会解析失败，但比「指向别的资源」安全
            # （缺失会抛 NotFoundException，错位则是静默显示错误内容）。
            # 仍视为不对齐，交由调用方决定。
            moved.append((name, old_id, "(已删除)"))
        elif new_id != old_id:
            moved.append((name, old_id, new_id))

    added = [n for n in new if n not in base]

    if moved:
        print(f"资源 ID 不对齐：{len(moved)} 个资源在版本间换了 ID")
        for name, o, n in moved[:10]:
            print(f"   {name}: {o} -> {n}")
        if len(moved) > 10:
            print(f"   …… 另有 {len(moved) - 10} 个")
        print("\n=> 带资源的补丁会打乱界面文字，必须拒绝（回退整包更新）")
        return 1

    print(f"资源 ID 对齐：{len(base)} 个旧资源 ID 全部保持，新增 {len(added)} 个资源")
    if added:
        for n in added[:5]:
            print(f"   + {n} = {new[n]}")
        if len(added) > 5:
            print(f"   …… 另有 {len(added) - 5} 个")
    print("=> 补丁可安全携带资源变更")
    return 0


if __name__ == "__main__":
    sys.exit(main())
