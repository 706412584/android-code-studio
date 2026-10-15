#!/usr/bin/env python3
"""从已发布的 APK 提取资源 ID 映射，生成 aapt2 的 --stable-ids 文件。

背景（为什么需要）：
    R.string.foo 编译后是**内联进调用点的字面量**（const v0, 0x7f0f0123）。
    热更新补丁只带差分的 dex——未变更的类仍在设备上跑，它们内联的是旧 ID。
    若新版本新增了资源，AAPT2 会把后面的 ID 整体后移，旧类请求的 ID 就指向了
    别的资源——界面出现大量无关文字（实测：主页「打开项目」显示成
    「找不到未解析的类名」）。

    稳定 ID 让 AAPT2 复用旧 ID：映射里已有的资源沿用原 ID，新资源分配
    新 ID（且映射中所有 ID 都被 reserve，不会被新资源抢占）。这样旧类内联的
    ID 永远指向同一个资源，补丁才能安全携带资源变更。

参考：腾讯 Tinker 的 TinkerResourceIdTask 做的是同一件事（读旧 APK 的 R.txt
→ 固定已有 ID、新资源追加 → 注入 aapt2 --stable-ids）。

用法：
    python3 tools/hotupdate-extract-stable-ids.py <base.apk> <output-stable-ids.txt>

    然后构建时注入：
        ./gradlew assembleRelease -PstableIdsFile=<output-stable-ids.txt>

注意：**首次 bootstrap 无历史 APK 时不要注入**——没有映射就等于现状（ID 漂移），
但至少不会因为空映射而打乱正常分配。
"""

import re
import subprocess
import sys
from pathlib import Path

# aapt2 dump resources 的行格式：
#   resource 0x7f010000 anim/abc_fade_in
RESOURCE_RE = re.compile(r"^\s+resource (0x[0-9a-fA-F]{8}) (\S+)\s*$")

# 需要跳过的类型：
#   styleable —— aapt2 会为它合成 ID，但 --stable-ids 不接受（Tinker 同样过滤）
#   attr       —— 部分 attr 由 aapt2 内部合成（inline attr），ID 不由我们控制
SKIP_TYPES = frozenset({"styleable"})


def find_aapt2() -> str:
    """定位 aapt2。优先 ANDROID_HOME，其次常见路径。"""
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
        build_tools = Path(sdk) / "build-tools"
        if build_tools.is_dir():
            # 精确匹配 aapt2 / aapt2.exe——`aapt2*` 会误配 aapt2-proto 等同名目录。
            for name in ("aapt2", "aapt2.exe"):
                candidates.extend(versions_desc(build_tools.glob("*/" + name)))
    local = Path("D:/android/build-tools")
    if local.is_dir():
        candidates.extend(versions_desc(local.glob("*/aapt2.exe")))
    for c in candidates:
        if c.is_file():
            return str(c)
    raise SystemExit("找不到 aapt2（设置 ANDROID_HOME 或检查 D:/android/build-tools）")


def dump_resources(aapt2: str, apk: str) -> str:
    result = subprocess.run(
        [aapt2, "dump", "resources", apk],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"aapt2 dump resources 失败（退出码 {result.returncode}）：{result.stderr[:400]}")
    return result.stdout


def extract(dump: str) -> tuple:
    """返回 (映射行列表, 统计)。映射行格式：pkg:type/name = 0xPPTTEEEE"""
    lines = []
    pkg = None
    skipped = {}
    for line in dump.splitlines():
        if pkg is None:
            m = re.match(r"Package name=(\S+) id=([0-9a-fA-F]+)", line.strip())
            if m:
                pkg = m.group(1)
            continue
        m = RESOURCE_RE.match(line)
        if not m:
            continue
        res_id, full_name = m.group(1), m.group(2)
        # full_name 形如 "anim/abc_fade_in"（type/name）
        if "/" not in full_name:
            continue
        type_name = full_name.split("/", 1)[0]
        if type_name in SKIP_TYPES:
            skipped[type_name] = skipped.get(type_name, 0) + 1
            continue
        lines.append(f"{pkg}:{full_name} = {res_id}")
    if pkg is None:
        raise SystemExit("解析失败：dump 输出里没有 Package name 行")
    return lines, skipped


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    apk, out_path = sys.argv[1], sys.argv[2]
    if not Path(apk).is_file():
        raise SystemExit(f"找不到 APK：{apk}")

    aapt2 = find_aapt2()
    print(f"aapt2: {aapt2}")
    print(f"base : {apk}")
    dump = dump_resources(aapt2, apk)
    lines, skipped = extract(dump)
    if not lines:
        raise SystemExit("未提取到任何资源 ID——拒绝生成空映射（空映射会打乱正常分配）")

    Path(out_path).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"写出 {len(lines)} 条映射 -> {out_path}")
    if skipped:
        print(f"跳过类型: {skipped}")


if __name__ == "__main__":
    main()
