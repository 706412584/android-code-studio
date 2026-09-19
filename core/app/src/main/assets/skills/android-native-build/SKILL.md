---
name: android-native-build
description: 在本机编译含 C/C++（CMake/NDK）的 Android 项目时必读——CMake 版本必须显式钉住，否则 AGP 会选中架构不匹配的版本。
---

# native 项目（CMake / NDK）在本机的构建约束

## 必须显式钉住 CMake 版本

生成的 `build.gradle` 里 `externalNativeBuild { cmake { ... } }` **必须写 `version`**：

```gradle
android {
    externalNativeBuild {
        cmake {
            path 'src/main/cpp/CMakeLists.txt'
            version '4.1.2'          // ← 不能省
        }
    }
}
```

**为什么**：不写 `version` 时，AGP 会自己挑「已注册的最高版本」。而本机的 CMake 情况是：

| 版本 | 架构 | 能否在手机上运行 |
|---|---|---|
| 3.22.1 | x86-64 | ✗ |
| 3.31.6 | x86-64 | ✗ |
| 4.1.2 | arm64 | ✓ |

前两个来自 Android SDK 官方仓库——**官方仓库的 CMake 只有 x86-64**（那是给桌面宿主编译用的）。
手机上跑不了，选了它们会在 `ninja` 阶段失败，报错往往指向工具链而不是「架构不对」，很难归因。

arm64 的 4.1.2 来自 ACS 自带的仓库。所以本机上**能用的 CMake 只有一个**。

判断某个版本能不能跑：

```sh
/data/data/com.tom.rv2ide/files/home/android-sdk/cmake/<版本>/bin/cmake --version
```

能正常输出版本号才可用。只看目录名或版本号高低会选错。

## 不要试图装更多 CMake 版本

`sdkmanager` 装出来的（实测 3.22.1、3.31.6）**都是 x86-64**，装更多只会引入更多坏选项。
问题不在「版本不够」，在「架构不对」。

## minSdk 与 API 可用性

模板自带的 `TextureAsset.cpp` 用到了 `AImageDecoder_*`，那是 **API 30** 才有的。
若项目 `minSdk < 30`，需要编译期守卫：

```cpp
#if __ANDROID_API__ >= 30
  // 真实图像解码
#else
  // 降级路径
#endif
```

注意：运行时 `if` 判断**不够**。`AImageDecoder` 的不可用性是编译期判定的
（`__INTRODUCED_IN(30)` 基于 `__ANDROID_API__`），即使加了运行时判断，minSdk 21
编译时仍会报 `error: 'AImageDecoder_delete' is unavailable`。必须用 `#if`。

## 新增源文件后要改 CMakeLists.txt

CMake 不会自动发现新文件。加了 `.cpp` 却没加进 `CMakeLists.txt` 的源文件列表，
链接时会报 `undefined symbol`——报错指向符号缺失，而真正的原因在构建脚本里。
