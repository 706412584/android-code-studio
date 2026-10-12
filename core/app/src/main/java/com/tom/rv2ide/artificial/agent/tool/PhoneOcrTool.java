/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.OcrPort;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.ToolInvokerAware;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * phone_ocr：对设备屏幕截图做 OCR，返回文字与屏幕像素包围盒。
 *
 * <p>用于 uiautomator 取不到文本的场景（游戏/Canvas/自绘 UI、WebView 渲染文字、图片文字）。
 * 引擎经 {@link OcrPort} 注入（ML Kit bundled 中文）；截图复用
 * {@link PhoneScreenshotSource}（走 phone_screenshot 的既有链路与权限判定）。
 */
public final class PhoneOcrTool extends BaseTool implements ToolInvokerAware {

  /** OCR 超时（首次识别要加载模型，给足裕量）。 */
  private static final long OCR_TIMEOUT_MS = 20_000L;
  /** 默认返回行数上限。 */
  private static final int DEFAULT_MAX_LINES = 200;
  private static final int MAX_LINES_LIMIT = 500;

  private ToolInvoker toolInvoker;

  @Override
  public void setToolInvoker(ToolInvoker invoker) {
    this.toolInvoker = invoker;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_OCR;
  }

  @Override
  public String getDescription() {
    return "读取设备屏幕上的文字（OCR），返回文本行与屏幕像素包围盒。"
        + "用于 uiautomator 取不到文本的场景：游戏/Canvas/自绘 UI、WebView 里渲染的文字、图片上的文字。"
        + "返回的 box 中心点可直接传给 phone_click。"
        + "支持 region（只识别屏幕某区域，与 phone_screenshot 同语义）与 scale（小字放大）。"
        + "需要 adb 级权限（Shizuku 后端）；无权限时会明确报错。"
        + "注意：普通 Android 控件的文字优先用 phone_view_hierarchy（更快更准）；本工具只在节点树没有文字时用。";
  }

  @Override
  public ToolCategory getCategory() {
    // 只读屏幕，不改设备（与 phone_screenshot 对齐）。
    return ToolCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("region", new JSONObject()
                    .put("type", "object")
                    .put("description", "只识别屏幕的某个矩形区域（物理像素，原点为屏幕左上角，与 phone_screenshot 的 region 同语义）。省略时识别全屏。")
                    .put("properties", new JSONObject()
                        .put("x", new JSONObject().put("type", "number").put("description", "左边界 x"))
                        .put("y", new JSONObject().put("type", "number").put("description", "上边界 y"))
                        .put("width", new JSONObject().put("type", "number").put("description", "宽度"))
                        .put("height", new JSONObject().put("type", "number").put("description", "高度")))
                    .put("required", new JSONArray().put("x").put("y").put("width").put("height")))
                .put("scale", new JSONObject()
                    .put("type", "number")
                    .put("description", "OCR 前对图像缩放倍数（0.25-4，默认 1）。不传时：识别区域长边 < 1200px 自动放大 2 倍（小字场景），否则保持 1 倍。放大后的长边上限 2560px。"))
                .put("maxLines", new JSONObject()
                    .put("type", "number")
                    .put("description", "最多返回的行数，默认 200，上限 500。"))
                .put("includeBoxes", new JSONObject()
                    .put("type", "boolean")
                    .put("description", "是否附带每行的屏幕像素包围盒 [左,上,右,下]（默认 true）。true 时可直接取 box 中心点传给 phone_click。")))
        // required 必须是顶层键（与 properties 平级）——嵌套会生成非法 schema，
        // 被严格校验的服务商 400 拒绝整个请求（CodeActionsTool 事故）。
        .put("required", new JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    OcrPort port = context == null ? null : context.getOcr();
    if (port == null) {
      return error("OCR 端口未接入（MlKitOcrPort 未注入）。");
    }
    if (toolInvoker == null) {
      return error("工具调用入口不可用（编排类工具需由装配方注入 ToolInvoker）。");
    }
    try {
      return runOcr(input, context, port);
    } catch (Throwable e) {
      // 工具约定：失败返回 error 变体，不抛异常。
      return error("OCR 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private ToolResult runOcr(JSONObject input, ToolContext context, OcrPort port)
      throws org.json.JSONException {
    // 1) 解析参数
    PhoneOcrText.Region regionScreen = PhoneOcrText.parseRegion(input);
    boolean explicitScale = input.has("scale");
    double requestedScale = input.optDouble("scale", 1.0);
    int maxLines = Math.min(Math.max((int) input.optDouble("maxLines", DEFAULT_MAX_LINES), 1),
        MAX_LINES_LIMIT);
    boolean includeBoxes = input.optBoolean("includeBoxes", true);

    context.reportProgress("截取屏幕");
    // 2) 截图（maxDimension:0 = 原分辨率——默认 1568px 缩放会丢小字）
    PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(
        toolInvoker, context, new JSONObject().put("maxDimension", 0));
    if (!shot.ok) {
      // shot.error 多数路径已带「截图失败: 」前缀（PhoneScreenshotSource 契约），
      // 只有少数不带——按需补前缀，避免出现「截图失败。\n截图失败: ...」的重复。
      String reason = shot.error;
      if (reason == null || reason.isEmpty()) {
        reason = "截图失败（未提供原因）。";
      } else if (!reason.startsWith("截图失败")) {
        reason = "截图失败: " + reason;
      }
      return error(reason);
    }
    // 3) 解码
    Bitmap bmp = null;
    Bitmap work = null;
    Bitmap work2 = null;
    try {
      bmp = BitmapFactory.decodeFile(shot.file.getAbsolutePath());
      if (bmp == null) {
        return error("截图解码失败：" + shot.file.getAbsolutePath());
      }
      int screenW = shot.screenWidth > 0 ? shot.screenWidth : bmp.getWidth();
      int screenH = shot.screenHeight > 0 ? shot.screenHeight : bmp.getHeight();
      // 坐标回缩系数 k（通常 1.0；若截图被体积收缩则 >1）
      float k = (float) screenW / bmp.getWidth();
      if (regionScreen == null) {
        regionScreen = new PhoneOcrText.Region(0, 0, screenW, screenH);
      }
      // 4) region → 位图坐标
      PhoneOcrText.Region regionBmp =
          PhoneOcrText.regionToBitmap(regionScreen, k, bmp.getWidth(), bmp.getHeight());
      if (regionBmp == null) {
        return error("region 无效或与屏幕无交集：x=" + regionScreen.x + " y=" + regionScreen.y
            + " w=" + regionScreen.width + " h=" + regionScreen.height
            + "，屏幕尺寸为 " + screenW + "x" + screenH + "。");
      }
      // 5) 裁剪（全图时跳过，避免无谓复制）
      boolean fullImage = regionBmp.x == 0 && regionBmp.y == 0
          && regionBmp.width == bmp.getWidth() && regionBmp.height == bmp.getHeight();
      work = fullImage ? bmp
          : Bitmap.createBitmap(bmp, regionBmp.x, regionBmp.y, regionBmp.width, regionBmp.height);
      // 6) 缩放
      int workLongSide = Math.max(work.getWidth(), work.getHeight());
      float s = PhoneOcrText.effectiveScale(workLongSide, explicitScale, requestedScale);
      if (Math.abs(s - 1.0f) > 0.001f) {
        int nw = Math.max(1, Math.round(work.getWidth() * s));
        int nh = Math.max(1, Math.round(work.getHeight() * s));
        work2 = Bitmap.createScaledBitmap(work, nw, nh, true);
      }
      Bitmap ocrInput = work2 != null ? work2 : work;
      // 7) PNG 编码
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      if (!ocrInput.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
        return error("截图 PNG 编码失败。");
      }
      // 8) OCR
      context.reportProgress("识别文字");
      long t0 = System.nanoTime();
      OcrPort.Result result = port.recognize(bos.toByteArray(), OCR_TIMEOUT_MS);
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
      if (!result.isOk()) {
        return error("OCR 失败。\n" + result.getError());
      }
      // 9) 坐标映射 + 格式化
      // 映射原点用**实际裁剪原点**（regionBmp）而不是请求值（regionScreen）：
      // regionToBitmap 以 (int) 截断得裁剪原点，k≠1 时两者差 ≤k 像素；
      // 用实际原点保证「屏幕坐标 = 裁剪原点 + work 坐标/k」的往返自洽。
      int originX = Math.round(regionBmp.x * k);
      int originY = Math.round(regionBmp.y * k);
      List<OcrPort.Line> mapped = PhoneOcrText.mapLines(
          result.getLines(), originX, originY, s, k, screenW, screenH);
      String text = PhoneOcrText.format(mapped, screenW, screenH, regionScreen,
          s, elapsedMs, result.getEngine(), maxLines, includeBoxes);
      // 纵向比例校验（计划 §4.3 step7）：k 只按宽度算；若落盘图高宽比与屏幕
      // 差异 >2%，说明发生了非等比收缩（内联体积收缩不该出现，但防御性告知），
      // 此时纵向坐标是近似的——必须明说，否则模型会当作精确值使用。
      if (bmp.getHeight() > 0 && screenH > 0) {
        float verticalK = (float) screenH / bmp.getHeight();
        if (Math.abs(verticalK - k) / k > 0.02f) {
          text = text + "\n（注意：截图纵向比例与屏幕不一致，坐标按宽度等比映射，纵向为近似值。）";
        }
      }
      return ok(ToolResult.truncateContent(text));
    } finally {
      // 逐层回收（注意别重复回收同一对象）：
      // - fullImage 时 work == bmp；
      // - createScaledBitmap 在目标尺寸与源相同时**返回同一实例**（如 s≈1.002
      //   且宽度小），此时 work2 == work。
      if (work2 != null && work2 != work) {
        work2.recycle();
      }
      if (work != null && work != bmp) {
        work.recycle();
      }
      if (bmp != null) {
        bmp.recycle();
      }
    }
  }
}
