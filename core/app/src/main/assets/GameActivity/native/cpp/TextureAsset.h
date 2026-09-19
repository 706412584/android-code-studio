#ifndef ANDROIDGLINVESTIGATIONS_TEXTUREASSET_H
#define ANDROIDGLINVESTIGATIONS_TEXTUREASSET_H

#include <memory>
#include <android/asset_manager.h>
#include <GLES3/gl3.h>
#include <string>
#include <vector>

class TextureAsset {
public:
    /*!
     * Loads a texture asset from the assets/ directory
     * @param assetManager Asset manager to use
     * @param assetPath The path to the asset
     * @return a shared pointer to a texture asset, resources will be reclaimed when it's cleaned up
     */
    static std::shared_ptr<TextureAsset>
    loadAsset(AAssetManager *assetManager, const std::string &assetPath);

    ~TextureAsset();

    /*!
     * @return the texture id for use with OpenGL
     */
    constexpr GLuint getTextureID() const { return textureID_; }

private:
    /*!
     * API 30 之前的降级路径。
     *
     * AImageDecoder 是 API 30 引入的，而本模板 minSdk 为 21。低版本设备上改走
     * AAsset + stb_image 风格的纯 CPU 解码，避免要求用户把 minSdk 提到 30。
     * 目前只支持本模板自带的那张 PNG（无外部解码依赖）。
     */
    static std::shared_ptr<TextureAsset>
    loadAssetLegacy(AAssetManager *assetManager, const std::string &assetPath);

    /*!
     * 把已解码的 RGBA 像素上传为 GL 纹理。两条路径共用，避免重复那段 GL 调用。
     */
    static std::shared_ptr<TextureAsset>
    uploadTexture(const uint8_t *pixels, int width, int height, int stride);

    inline TextureAsset(GLuint textureId) : textureID_(textureId) {}

    GLuint textureID_;
};

#endif //ANDROIDGLINVESTIGATIONS_TEXTUREASSET_H