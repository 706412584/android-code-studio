#!/usr/bin/env python3
"""规范化热更新补丁包：dex 改名、噪音资产剔除、资源变更准入判定。

背景一（dex 跳号，必须理解否则补丁静默失效）：
    patch-cli 生成补丁时，会沿用 base APK 里的 dex 名（例如 classes8.dex、classes12.dex）。
    而 Android 的 DexPathList 只按 **连续** 的 classes.dex → classes2.dex → classes3.dex …
    顺序查找 dex；一旦跳号，从缺口开始的所有 dex 都不可见。

    对 ACS 这种 12+ dex 的应用，patch-cli 产出的是 classes8.dex/classes12.dex ——
    没有 classes.dex，于是 ART 找不到补丁里的任何类。表现是：
        补丁「应用成功」、DEX 注入 ✓、日志无异常，但代码毫无变化。
    这是静默失败，比报错危险得多，所以生成侧与 App 侧都做规范化（双保险）。

背景二（含资源变更的补丁会打乱资源 ID，须校验后才放行）：
    补丁里的 resources.arsc 是**整体替换**，而资源 ID 在不同版本间会漂移
    （r11→r12 实测：10308 个共有资源里 981 个 ID 变了）。补丁只带差分 dex，
    未被覆盖的那些 dex 仍引用旧 ID，在新表里指向别的资源——界面出现大量
    无关文字（实测：主页「打开项目」显示成「找不到未解析的类名」），且完全静默。

    资源 ID 漂移对**整包更新**无害（代码与资源一起换），只对补丁致命。
    解决路径是构建时注入 aapt2 --stable-ids（见 hotupdate-extract-stable-ids.py）：
    旧资源沿用原 ID、新资源分配新 ID，实测 0 漂移（不注入时 1147 个漂移）。

    但**不能因为「配置应该生效」就放行**——参数可能没透传、映射文件可能丢失。
    因此默认仍拒绝含资源的补丁；只有 CI 用 hotupdate-check-resource-alignment.py
    独立校验过两个 APK 的 ID 确实对齐，才用 --allow-resources 放行。

用法：
    python3 tools/hotupdate-normalize-patch.py in.zip out.zip [--allow-resources]
"""

import json
import sys
import zipfile

# 每次构建都会变、但对用户无意义的噪音资产（APK 根相对路径）。
#
# 实测：r11→r12 补丁 20.5MB，其中 16.9MB（79%）是
# `assets/data/common/tooling-api-all.jar`。解开它后 11740 个条目里**只有
# `com/tom/rv2ide/buildinfo/BuildInfo.class`（内嵌版本号）一个文件不同**，
# 其余完全相同——纯粹因为版本号变了就被 diff 判定为「资源变更」而进补丁。
#
# 剔除是安全的：BuildInfo 在 app 自己的 dex 里也有一份（通过 jar 副本读版本
# 是次要路径），而版本号本来就随整包更新。剔除后同一对 APK 的补丁
# 从 20.5MB 降到约 4.4MB。
NOISE_ASSETS = frozenset({
    "assets/data/common/tooling-api-all.jar",
})


def count_resource_changes(zin) -> int:
    """补丁里资源变更的条数（两个判据取较大者）。

    两个判据都查：包内的实际文件，以及 patch.json 里 changes.resources 的声明。
    后者是 patch-cli 自己的记录（实测 r12 为 modified: 98），不依赖打包细节。
    """
    names = zin.namelist()
    arsc = [n for n in names if n == "resources.arsc"]
    res = [n for n in names if n.startswith("res/")]

    declared = 0
    if "patch.json" in names:
        try:
            meta = json.loads(zin.read("patch.json").decode("utf-8"))
            resources = (meta.get("changes") or {}).get("resources") or {}
            for kind in ("modified", "added", "deleted"):
                declared += len(resources.get(kind) or [])
        except Exception:
            # 清单读不动时不阻断——文件判据仍会兜底。
            pass

    return max(len(arsc) + len(res), declared)


def assert_resources_safe(zin, allow_resources: bool) -> None:
    """补丁含资源变更时的准入判定——见模块文档「背景二」。

    **前提已变**：构建启用 aapt2 --stable-ids 后，旧资源 ID 在版本间保持不变
    （实测 0 漂移 vs 不启用时 1147 个漂移），带资源的补丁因此可以安全应用。
    但配置可能失效（参数没透传、映射文件丢失、AGP 行为变化），所以**不能**
    因为「配置应该生效」就放行——CI 用 hotupdate-check-resource-alignment.py
    独立校验过两个 APK 的 ID 是否真的对齐，把结论经 allow_resources 传进来。

    allow_resources=False（未校验 / 校验失败 / 未启用稳定化）时仍拒绝：
    宁可少发一次补丁（回退整包更新），也不要静默损坏用户界面。
    """
    count = count_resource_changes(zin)
    if count == 0:
        return
    if allow_resources:
        print(f"  补丁含 {count} 项资源变更；ID 对齐校验已通过，放行")
        return
    raise SystemExit(
        f"拒绝生成：补丁包含 {count} 项资源变更，且未通过 ID 对齐校验。\n"
        "  原因：补丁会整体替换 resources.arsc。若版本间资源 ID 发生漂移，\n"
        "  未被差分 dex 覆盖的代码会引用到错误的资源，界面文字错乱且完全静默。\n"
        "  处理：本次不发布热更新补丁，用户走整包更新（updater.json 的 patch\n"
        "  块会被 CI 移除，应用端自动回退到整包分支）。\n"
        "  若要允许资源变更，需构建时注入 aapt2 --stable-ids（见\n"
        "  tools/hotupdate-extract-stable-ids.py）并用\n"
        "  tools/hotupdate-check-resource-alignment.py 校验通过后加 --allow-resources。"
    )


def stamp_alignment(zin, meta_bytes: bytes) -> bytes:
    """在 patch.json 里标记「资源 ID 已校验对齐」。

    <p>为什么需要这个标记：App 端**无法**自己判断补丁的资源 ID 是否与当前安装
    版本对齐（要解析 arsc，代价与风险都高）。但它必须做出放行/拒绝的决定。
    因此由生成侧把校验结论写进补丁——App 读这个字段即可。

    标记的可信度：补丁本身经 updater.json 的 sha256 校验，标记随补丁一起被保护，
    所以「有标记」等价于「生成时校验通过」。
    """
    try:
        meta = json.loads(meta_bytes.decode("utf-8"))
    except Exception:
        return meta_bytes
    meta["resourceIdsStable"] = True
    return json.dumps(meta, ensure_ascii=False).encode("utf-8")


def normalize(src_path: str, dst_path: str, allow_resources: bool = False) -> int:
    with zipfile.ZipFile(src_path) as zin:
        names = zin.namelist()
        has_resources = count_resource_changes(zin) > 0
        assert_resources_safe(zin, allow_resources)
        dex_names = sorted(n for n in names if n.endswith(".dex"))
        other_names = [n for n in names if not n.endswith(".dex")]

        # 剔除「每次构建都会变」的噪音资产（见 NOISE_ASSETS 的说明）。
        stripped = [n for n in other_names if n in NOISE_ASSETS]
        other_names = [n for n in other_names if n not in NOISE_ASSETS]
        if stripped:
            print(f"  剔除噪音资产 {len(stripped)} 个: {stripped}")

        if not dex_names:
            raise SystemExit(f"错误：{src_path} 里没有 .dex 条目，不是有效的补丁包")

        # patch.json 放最前，便于人工排查；其余非 dex 条目保持原顺序。
        ordered_other = sorted(other_names, key=lambda n: 0 if n == "patch.json" else 1)

        # 校验通过且补丁确实带资源时，打上标记供 App 端放行（见 stamp_alignment）。
        stamp = allow_resources and has_resources

        with zipfile.ZipFile(dst_path, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in ordered_other:
                data = zin.read(n)
                if stamp and n == "patch.json":
                    data = stamp_alignment(zin, data)
                zout.writestr(n, data)

            for i, n in enumerate(dex_names):
                out_name = "classes.dex" if i == 0 else f"classes{i + 1}.dex"
                zout.writestr(out_name, zin.read(n))

        print(f"  dex: {dex_names} -> classes.dex..classes{len(dex_names)}.dex")
        if stamp:
            print("  已在 patch.json 标记 resourceIdsStable=true（App 端据此放行资源变更）")
        return len(dex_names)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    allow_resources = "--allow-resources" in sys.argv
    if len(args) != 2:
        raise SystemExit(__doc__)
    src, dst = args
    count = normalize(src, dst, allow_resources)
    print(f"规范化完成：{dst}（{count} 个 dex）")


if __name__ == "__main__":
    main()
