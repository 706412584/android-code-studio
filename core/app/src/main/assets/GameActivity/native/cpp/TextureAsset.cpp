#include <android/api-level.h>
#include "TextureAsset.h"
#include "AndroidOut.h"
#include "Utility.h"

// AImageDecoder 自 API 30 起可用。
//
// **必须在编译期分支，运行时 if 不够**：头文件用 `__INTRODUCED_IN(30)` 声明这些函数，
// 而 `__INTRODUCED_IN` 依据编译时的 `__ANDROID_API__`（即 minSdk）判定可用性。
// minSdk 21 时，即使调用点被 `if (api >= 30)` 包住，编译器仍会报
//   error: 'AImageDecoder_delete' is unavailable: introduced in Android 30
// 因为那条语句本身就不该存在于 minSdk<30 的编译单元里。
//
// 本模板 minSdk 是 21，所以低版本走 loadAssetLegacy 的占位路径；
// 把 minSdk 提到 30 时自动启用真实的 AImageDecoder 解码。
#if __ANDROID_API__ >= 30
#include <android/imagedecoder.h>
#define ACS_HAS_IMAGE_DECODER 1
#else
#define ACS_HAS_IMAGE_DECODER 0
#endif

std::shared_ptr<TextureAsset>
TextureAsset::loadAsset(AAssetManager *assetManager, const std::string &assetPath) {
#if ACS_HAS_IMAGE_DECODER
    // Get the image from asset manager
    auto pAndroidRobotPng = AAssetManager_open(
            assetManager,
            assetPath.c_str(),
            AASSET_MODE_BUFFER);

    // Make a decoder to turn it into a texture
    AImageDecoder *pAndroidDecoder = nullptr;
    auto result = AImageDecoder_createFromAAsset(pAndroidRobotPng, &pAndroidDecoder);
    assert(result == ANDROID_IMAGE_DECODER_SUCCESS);

    // make sure we get 8 bits per channel out. RGBA order.
    AImageDecoder_setAndroidBitmapFormat(pAndroidDecoder, ANDROID_BITMAP_FORMAT_RGBA_8888);

    // Get the image header, to help set everything up
    const AImageDecoderHeaderInfo *pAndroidHeader = nullptr;
    pAndroidHeader = AImageDecoder_getHeaderInfo(pAndroidDecoder);

    // important metrics for sending to GL
    auto width = AImageDecoderHeaderInfo_getWidth(pAndroidHeader);
    auto height = AImageDecoderHeaderInfo_getHeight(pAndroidHeader);
    auto stride = AImageDecoder_getMinimumStride(pAndroidDecoder);

    // Get the bitmap data of the image
    auto upAndroidImageData = std::make_unique<std::vector<uint8_t>>(height * stride);
    auto decodeResult = AImageDecoder_decodeImage(
            pAndroidDecoder,
            upAndroidImageData->data(),
            stride,
            upAndroidImageData->size());
    assert(decodeResult == ANDROID_IMAGE_DECODER_SUCCESS);

    auto pTexture = uploadTexture(
            upAndroidImageData->data(),
            static_cast<int>(width),
            static_cast<int>(height),
            static_cast<int>(stride));

    // cleanup helpers
    AImageDecoder_delete(pAndroidDecoder);
    AAsset_close(pAndroidRobotPng);

    return pTexture;
#else
    // minSdk < 30：AImageDecoder 在此编译配置下不可用。
    return loadAssetLegacy(assetManager, assetPath);
#endif
}

std::shared_ptr<TextureAsset>
TextureAsset::loadAssetLegacy(AAssetManager *assetManager, const std::string &assetPath) {
    // 确认资源确实存在，然后给一张占位纹理。
    //
    // 为什么不在这里解码 PNG：解码需要第三方库（libpng / stb_image），而模板刻意
    // 不带任何第三方依赖——为一个占位路径引入一个 C 库不划算。
    // 低版本设备本来也不是这套 GL 模板的目标平台；把 minSdk 提到 30 即自动走
    // AImageDecoder 的真实解码路径（见 loadAsset 的编译期分支）。
    auto pAsset = AAssetManager_open(assetManager, assetPath.c_str(), AASSET_MODE_BUFFER);
    if (pAsset != nullptr) {
        aout << "Texture asset '" << assetPath
             << "' present; minSdk < 30 so a placeholder texture is used. "
             << "Raise minSdk to 30 to decode it." << std::endl;
        AAsset_close(pAsset);
    } else {
        aout << "Texture asset '" << assetPath << "' not found" << std::endl;
    }

    // 2x2 的不透明浅灰：够小，且不会因为全透明而在画面上完全看不见。
    const int kPlaceholderSize = 2;
    const uint8_t kPlaceholderPixels[kPlaceholderSize * kPlaceholderSize * 4] = {
            200, 200, 200, 255, 200, 200, 200, 255,
            200, 200, 200, 255, 200, 200, 200, 255,
    };

    return uploadTexture(kPlaceholderPixels, kPlaceholderSize, kPlaceholderSize,
                         kPlaceholderSize * 4);
}

std::shared_ptr<TextureAsset>
TextureAsset::uploadTexture(const uint8_t *pixels, int width, int height, int stride) {
    // Get an opengl texture
    GLuint textureId;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D, textureId);

    // Clamp to the edge, you'll get odd results alpha blending if you don't
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    // 每行像素的对齐。默认是 4，而 RGBA 每像素正好 4 字节，所以无需改动；
    // 显式设一次是因为 GL_UNPACK_ALIGNMENT 是全局状态，别的代码可能改过它，
    // 那时会出现纹理整体错位这种极难定位的现象。
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    // Load the texture into VRAM
    glTexImage2D(
            GL_TEXTURE_2D, // target
            0, // mip level
            GL_RGBA, // internal format, often advisable to use BGR
            width, // width of the texture
            height, // height of the texture
            0, // border (always 0)
            GL_RGBA, // format
            GL_UNSIGNED_BYTE, // type
            pixels // Data to upload
    );

    // generate mip levels. Not really needed for 2D, but good to do
    glGenerateMipmap(GL_TEXTURE_2D);

    return std::shared_ptr<TextureAsset>(new TextureAsset(textureId));
}

TextureAsset::~TextureAsset() {
    // return texture resources
    glDeleteTextures(1, &textureID_);
    textureID_ = 0;
}