#!/usr/bin/env python3
"""规范化热更新补丁包里的 dex 文件名，并拒绝含资源变更的补丁。

背景一（dex 跳号，必须理解否则补丁静默失效）：
    patch-cli 生成补丁时，会沿用 base APK 里的 dex 名（例如 classes8.dex、classes12.dex）。
    而 Android 的 DexPathList 只按 **连续** 的 classes.dex → classes2.dex → classes3.dex …
    顺序查找 dex；一旦跳号，从缺口开始的所有 dex 都不可见。

    对 ACS 这种 12+ dex 的应用，patch-cli 产出的是 classes8.dex/classes12.dex ——
    没有 classes.dex，于是 ART 找不到补丁里的任何类。表现是：
        补丁「应用成功」、DEX 注入 ✓、日志无异常，但代码毫无变化。
    这是静默失败，比报错危险得多，所以生成侧与 App 侧都做规范化（双保险）。

背景二（含资源变更的补丁会打乱资源 ID，必须拒绝）：
    补丁里的 resources.arsc 是**整体替换**，而资源 ID 在不同版本间会漂移
    （r11→r12 实测：10308 个共有资源里 981 个 ID 变了）。补丁只带差分 dex，
    未被覆盖的那些 dex 仍引用旧 ID，在新表里指向别的资源——界面出现大量
    无关文字（实测：主页「打开项目」显示成「找不到未解析的类名」）。

    资源 ID 漂移对**整包更新**无害（代码与资源一起换），只对补丁致命。
    因此：补丁内出现 resources.arsc 或 res/ 条目即判定为不安全，直接拒绝生成。
    回退路径是整包更新（始终可用），不会让用户卡住。

本脚本把补丁 zip 内的 *.dex 按原文件名排序，重命名为连续的 classes.dex / classes2.dex …，
其余条目（patch.json、so）原样保留。dex 内容不改（dex 不引用自身文件名，改名安全）。

用法：
    python3 tools/hotupdate-normalize-patch.py in.zip out.zip
"""

import sys
import zipfile


def assert_no_resources(names) -> None:
    """补丁含资源条目即拒绝——见模块文档「背景二」。"""
    arsc = [n for n in names if n == "resources.arsc"]
    res = [n for n in names if n.startswith("res/")]
    if arsc or res:
        raise SystemExit(
            "拒绝生成：补丁包含资源变更（"
            f"resources.arsc={len(arsc)} 个, res/ 下 {len(res)} 个文件）。\n"
            "  原因：补丁会整体替换 resources.arsc，而资源 ID 跨版本会漂移；\n"
            "  未被差分 dex 覆盖的代码仍引用旧 ID，界面会显示错乱的文字。\n"
            "  处理：本次不发布热更新补丁，用户走整包更新（updater.json 的 patch\n"
            "  块会被 CI 移除，应用端自动回退到整包分支）。"
        )


def normalize(src_path: str, dst_path: str) -> int:
    with zipfile.ZipFile(src_path) as zin:
        names = zin.namelist()
        assert_no_resources(names)
        dex_names = sorted(n for n in names if n.endswith(".dex"))
        other_names = [n for n in names if not n.endswith(".dex")]

        if not dex_names:
            raise SystemExit(f"错误：{src_path} 里没有 .dex 条目，不是有效的补丁包")

        # patch.json 放最前，便于人工排查；其余非 dex 条目保持原顺序。
        ordered_other = sorted(other_names, key=lambda n: 0 if n == "patch.json" else 1)

        with zipfile.ZipFile(dst_path, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in ordered_other:
                zout.writestr(n, zin.read(n))

            for i, n in enumerate(dex_names):
                out_name = "classes.dex" if i == 0 else f"classes{i + 1}.dex"
                zout.writestr(out_name, zin.read(n))

        print(f"  dex: {dex_names} -> classes.dex..classes{len(dex_names)}.dex")
        return len(dex_names)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    count = normalize(src, dst)
    print(f"规范化完成：{dst}（{count} 个 dex）")


if __name__ == "__main__":
    main()
