---
name: android-signing
description: 修改应用的签名配置、包名或 applicationId 前必读——签名不一致会导致必须卸载重装，而设备上有大量数据。
---

# 签名：最容易造成不可逆损失的地方

## 先记住这条

**卸载应用会清掉它的私有数据。** 本机环境里，那可能包括几 GB 的 SDK、Termux rootfs
与用户配置。所以任何会导致「必须卸载重装」的改动，都要先停下来确认。

## 什么改动会导致必须卸载

Android 要求覆盖安装的 APK 与原包的签名**一致**。签名变了就只能卸载重装。会导致签名变化的情况：

- 换了签名密钥（keystore）
- 改了 `applicationId`
- 同一个 `applicationId` 下换了不同的密钥

**改 `applicationId` 一定要提前说明后果**，不要默默改。

## 判断签名是否一致：必须比对证书

```sh
apksigner verify --print-certs <apk>
```

比对输出的 **SHA-256 证书指纹**。

**不要用 `dumpsys package` 里的 `signatures=[xxxxxxxx]` 判断**——那是系统内部的哈希，
与证书指纹无关。照它判断会得出「签名不同、必须卸载」的错误结论，然后真的去卸载。

## 签名配置的读取方式

`build.gradle.kts` 里的签名配置从**环境变量**读取（storeFile / storePassword / keyAlias 等）。
因此本机构建需要显式提供这些变量；不提供会在打包阶段报
`keystore password was incorrect`——这个报错有误导性，真正的原因是变量没传。

## 构建失败时的排查顺序

1. 看是哪个 task 失败（`:app:packageDebug` 之前的失败与签名无关）
2. 签名问题只会在打包/签名阶段出现
3. 报错提到 keystore 时，先确认环境变量是否传了，而不是先怀疑密钥本身

## 改 signingConfig 前必做

- 确认当前密钥的来源与存放位置
- 确认它是否还有备份（密钥丢了就无法再产出能覆盖安装的包）
- 告诉用户这个改动的影响，等他确认
