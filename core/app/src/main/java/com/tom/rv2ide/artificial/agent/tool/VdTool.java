/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Base64;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.OcrPort;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.artificial.agent.tool.vd.ScreenActionSemantics;
import com.tom.rv2ide.artificial.agent.tool.vd.VdController;
import java.io.ByteArrayOutputStream;
import java.io.File;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子屏工具（vscreen）：在宿主上操作一块**不在设备屏幕显示**的虚拟显示屏。
 *
 * <p>移植自 Aharou 的 {@code VdTool}。典型用法：{@code start} 建屏 →
 * {@code launch} 把某个 App 开进去 → {@code shot} 看画面 → {@code tap}/{@code swipe}/
 * {@code key}/{@code type} 操作 → {@code stop} 收掉。全程不占用用户屏幕（需 Shizuku 就绪）。
 *
 * <p><b>与 phone_* 工具的分工</b>：{@code phone_screenshot}/{@code phone_click} 等操作的是
 * **用户当前屏幕**——跑一轮自动化测试用户就没法用手机。本工具把 App 开到虚拟屏上操作，
 * 用户主屏零打扰。要动第三方 App 做自动化，优先用它。
 *
 * <p><b>与 Aharou 的差异</b>：去掉 {@code task}/{@code trace} 两个 action——它们依赖
 * Aharou 的 MobileOperator 子代理子系统（多步「看屏→决策→动作」循环），超出本次移植范围；
 * 需要的多步循环可由模型自己用 shot + tap 组合完成。
 */
public final class VdTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(VdTool.class);

  private static final int MAX_IMAGE_EDGE = 1440;
  private static final int JPEG_QUALITY = 75;
  private static final long SHELL_TIMEOUT_MS = 30_000L;

  private final Context appContext;
  private final VdController vdController;

  public VdTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.vdController = new VdController(appContext, shellBackends);
  }

  @Override
  public String getName() {
    return ToolNames.VSCREEN;
  }

  @Override
  public String getDescription() {
    return "影子屏：宿主上的无头虚拟显示屏（不在设备屏幕显示）。可在其中启动 App、截图、点按、滑动、输入文字、按键，"
        + "全程不影响用户主屏。操作宿主上第三方 App 做自动化测试的标准手段——"
        + "phone_* 工具操作的是用户当前屏幕（会打扰用户），本工具把 App 开到虚拟屏上。"
        + "action=start 创建（可选 width/height/dpi）；status 查询；stop 停止；"
        + "launch 启动应用（component=\"包名/Activity\" 或仅包名）；"
        + "shot 截图（图片随结果返回，图上带坐标网格；传 ocr=true 则改回文字、传 grid=false 关闭网格）；"
        + "tap / swipe / key 触控；type 往焦点输入框输文字（text=内容，可选 submit=true 回车，支持中文）。"
        + "需 Shizuku 就绪；未就绪时返回明确错误。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 与 phone_click 同级：经 Shizuku（adb shell 身份）操作宿主。若免确认，
    // 用户配了「shell 需确认」后仍能靠本工具静默驱动宿主，等于绕过确认门。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject props = new JSONObject();
    props.put(
        "action",
        new JSONObject()
            .put("type", "string")
            .put("description", "操作：start / status / stop / launch / shot / tap / swipe / key / type")
            .put(
                "enum",
                new org.json.JSONArray()
                    .put("start")
                    .put("status")
                    .put("stop")
                    .put("launch")
                    .put("shot")
                    .put("tap")
                    .put("swipe")
                    .put("key")
                    .put("type")));
    props.put(
        "width",
        new JSONObject().put("type", "integer").put("description", "start 专用：宽（默认 1080）"));
    props.put(
        "height",
        new JSONObject().put("type", "integer").put("description", "start 专用：高（默认 1920）"));
    props.put(
        "dpi", new JSONObject().put("type", "integer").put("description", "start 专用：dpi（默认 440）"));
    props.put(
        "component",
        new JSONObject()
            .put("type", "string")
            .put("description", "launch 专用：组件（\"pkg/act\" 或 \"pkg\"）"));
    props.put("x", new JSONObject().put("type", "number").put("description", "tap/swipe 起点 X（像素）"));
    props.put("y", new JSONObject().put("type", "number").put("description", "tap/swipe 起点 Y（像素）"));
    props.put("x2", new JSONObject().put("type", "number").put("description", "swipe 终点 X"));
    props.put("y2", new JSONObject().put("type", "number").put("description", "swipe 终点 Y"));
    props.put(
        "duration",
        new JSONObject().put("type", "integer").put("description", "swipe 持续时间（毫秒，默认 300）"));
    props.put(
        "keycode",
        new JSONObject()
            .put("type", "string")
            .put("description", "key 专用：按键（KEYCODE_BACK / 4 等）"));
    props.put(
        "ocr",
        new JSONObject()
            .put("type", "boolean")
            .put("description", "shot 专用：true 时用本机 OCR 把屏幕画面转成文字返回（不返回图片）。省 token。"));
    props.put(
        "grid",
        new JSONObject()
            .put("type", "boolean")
            .put("description", "shot 专用：是否在图上叠加坐标网格（默认 true）。网格线标注的数字就是 tap 用的坐标，可直接照读。"));
    props.put(
        "text",
        new JSONObject()
            .put("type", "string")
            .put("description", "type 专用：要输入到焦点输入框的文字。支持中文等非 ASCII（内部走剪贴板粘贴）。"));
    props.put(
        "submit",
        new JSONObject().put("type", "boolean").put("description", "type 专用：输完后是否回车（默认 false）。"));
    props.put(
        "label",
        new JSONObject()
            .put("type", "string")
            .put(
                "description",
                "tap / type 专用：手指下或目标元素的文字（按钮、链接、输入框的标签），如「确认支付」。"
                    + "命中敏感词会把本次调用提级为敏感操作、逐次确认；tap/type 不传会收到一条告警。"));

    return new JSONObject()
        .put("type", "object")
        .put("properties", props)
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase(java.util.Locale.ROOT);
    if (action.isEmpty()) {
      return error("缺少 action（start / status / stop / launch / shot / tap / swipe / key / type）");
    }
    try {
      switch (action) {
        case "start":
          return doStart(input);
        case "status":
          return doStatus();
        case "stop":
          vdController.stop();
          return ok("影子屏已停止");
        case "launch":
          return doLaunch(input);
        case "shot":
          return doShot(input, context);
        case "tap":
          return doTap(input);
        case "swipe":
          return doSwipe(input);
        case "key":
          return doKey(input);
        case "type":
          return doType(input);
        default:
          return error("不支持的 action：" + action);
      }
    } catch (Exception e) {
      log.warn("vscreen 执行失败：{}", action, e);
      return error("影子屏操作失败：" + e.getMessage());
    }
  }

  private ToolResult doStart(JSONObject input) {
    int w = input.optInt("width", VdController.DEFAULT_WIDTH);
    int h = input.optInt("height", VdController.DEFAULT_HEIGHT);
    int dpi = input.optInt("dpi", VdController.DEFAULT_DPI);
    VdController.VdInfo info = vdController.start(w, h, dpi);
    return ok("影子屏已就绪\n" + infoJson(info));
  }

  private ToolResult doStatus() {
    VdController.VdInfo info = vdController.refresh();
    if (info == null) {
      return ok("影子屏未运行（可用 action=start 创建）");
    }
    return ok("运行中\n" + infoJson(info));
  }

  private ToolResult doLaunch(JSONObject input) {
    String component = input.optString("component", "").trim();
    if (component.isEmpty()) {
      return error("launch 需要 component（\"pkg/act\" 或 \"pkg\"）");
    }
    VdController.VdInfo info = vdController.launch(component);
    return ok("已在影子屏启动 " + component + "\n" + infoJson(info));
  }

  private ToolResult doShot(JSONObject input, ToolContext context) {
    VdController.VdInfo info = vdController.refresh();
    if (info == null) {
      return error("影子屏未运行，请先 action=start");
    }
    File file = vdController.screenshot(info);
    boolean wantOcr = input.optBoolean("ocr", false);
    boolean wantGrid = input.optBoolean("grid", true);
    // OCR 直接吃画面，网格线会干扰识别，故 OCR 时不叠网格。
    ShotImage shot = encodeScreenshot(file, wantGrid && !wantOcr);
    String caption = buildCaption(info, shot);
    StringBuilder note = new StringBuilder();
    note.append("已截图");
    if (!caption.isEmpty()) {
      note.append("（").append(caption).append("）");
    }
    if (wantOcr) {
      OcrPort port = context == null ? null : context.getOcr();
      if (port == null) {
        return error("OCR 端口未接入，无法识别截图文字（可改传 ocr=false 直接看图）。");
      }
      note.append("\n");
      OcrPort.Result result = port.recognize(fileBytes(file), 20_000L);
      if (!result.isOk()) {
        return error("OCR 失败：" + result.getError());
      }
      if (result.getLines().isEmpty()) {
        note.append("（未识别到文字）");
      } else {
        for (OcrPort.Line line : result.getLines()) {
          note.append("\n").append(line.getText());
        }
      }
      note.append("\n\n").append(coordinateHint(shot));
      return ok(note.toString());
    }
    note.append("\n").append(coordinateHint(shot));
    if (shot.base64.isEmpty()) {
      return ok(note.toString());
    }
    return ToolResult.withImage(getName(), note.toString(), "image/jpeg", shot.base64);
  }

  private ToolResult doTap(JSONObject input) {
    VdController.VdInfo info = requireRunning();
    if (!input.has("x") || !input.has("y")) {
      return error("tap 需要 x / y");
    }
    int x = (int) Math.round(input.optDouble("x", Double.NaN));
    int y = (int) Math.round(input.optDouble("y", Double.NaN));
    if (Double.isNaN(x) || Double.isNaN(y)) {
      return error("x / y 必须是数字。");
    }
    vdController.tap(info, x, y);
    return ok("已点击 (" + x + ", " + y + ")");
  }

  private ToolResult doSwipe(JSONObject input) {
    VdController.VdInfo info = requireRunning();
    if (!input.has("x") || !input.has("y") || !input.has("x2") || !input.has("y2")) {
      return error("swipe 需要 x / y / x2 / y2");
    }
    int x = (int) Math.round(input.optDouble("x", 0));
    int y = (int) Math.round(input.optDouble("y", 0));
    int x2 = (int) Math.round(input.optDouble("x2", 0));
    int y2 = (int) Math.round(input.optDouble("y2", 0));
    int duration = input.optInt("duration", 300);
    vdController.swipe(info, x, y, x2, y2, duration);
    return ok("已滑动 (" + x + "," + y + ") → (" + x2 + "," + y2 + ")");
  }

  private ToolResult doKey(JSONObject input) {
    VdController.VdInfo info = requireRunning();
    String code = input.optString("keycode", "").trim();
    if (code.isEmpty()) {
      return error("key 需要 keycode（如 KEYCODE_BACK / 4）");
    }
    vdController.keyEvent(info, code);
    return ok("已发送按键 " + code);
  }

  private ToolResult doType(JSONObject input) {
    VdController.VdInfo info = requireRunning();
    if (!input.has("text")) {
      return error("type 需要 text");
    }
    String text = input.optString("text", "");
    boolean submit = input.optBoolean("submit", false);
    vdController.type(info, text, submit);
    return ok(submit ? "已输入文字并回车" : "已输入文字");
  }

  private VdController.VdInfo requireRunning() {
    VdController.VdInfo info = vdController.refresh();
    if (info == null) {
      throw new IllegalStateException("影子屏未运行，请先 action=start");
    }
    return info;
  }

  private static String infoJson(VdController.VdInfo info) {
    return "displayId="
        + info.displayId
        + " size="
        + info.width
        + "x"
        + info.height
        + " dpi="
        + info.dpi
        + (info.sfDisplayId.isEmpty() ? "" : " sfDisplayId=" + info.sfDisplayId);
  }

  /** 截图 caption：影子屏上的前台 App/Activity、画面像素尺寸、键盘是否在（拿不到的字段略去）。 */
  private String buildCaption(VdController.VdInfo info, ShotImage shot) {
    String[] fg = shadowForeground(info.displayId);
    Boolean keyboard = shadowKeyboardShown();
    return ScreenActionSemantics.caption(
        null,
        fg[0],
        fg[1],
        shot.inputWidth > 0 ? shot.inputWidth : info.width,
        shot.inputHeight > 0 ? shot.inputHeight : info.height,
        keyboard);
  }

  /** 影子屏上的前台包名与 Activity（best-effort：拿不到返回 null，不让截图因此失败）。 */
  private String[] shadowForeground(int displayId) {
    try {
      String out =
          PhoneUiSelector.exec(
                  shellBackendsForCaption(),
                  "dumpsys activity activities | awk '/Display #" + displayId
                      + " \\(activities from top to bottom\\)/{f=1;next}"
                      + " f&&/Display #/{exit} f&&/topResumedActivity/{print;exit}'",
                  15_000L)
              .stdout;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(out);
      if (m.find()) {
        return new String[] {m.group(1), m.group(2)};
      }
    } catch (RuntimeException ignored) {
      // best-effort
    }
    return new String[] {null, null};
  }

  /** 键盘是否弹出（best-effort：dumpsys 报得不明就返回 null，caption 里略去）。 */
  private Boolean shadowKeyboardShown() {
    try {
      String out =
          PhoneUiSelector.exec(
                  shellBackendsForCaption(),
                  "dumpsys input_method | grep -m3 -E 'mInputShown|mShowRequested'",
                  10_000L)
              .stdout;
      if (out.contains("mInputShown=true")) {
        return Boolean.TRUE;
      }
      if (out.contains("mInputShown=false")) {
        return Boolean.FALSE;
      }
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private ShellBackendRegistry shellBackendsForCaption() {
    return vdController.shellBackends();
  }

  /** 一次截图的编码结果：base64 与图上像素/缩放信息，供模型换算坐标。 */
  private static final class ShotImage {
    final String base64;
    final int imageWidth;
    final int imageHeight;
    final int inputWidth;
    final int inputHeight;
    final float scale;

    ShotImage(String base64, int imageWidth, int imageHeight, int inputWidth, int inputHeight, float scale) {
      this.base64 = base64;
      this.imageWidth = imageWidth;
      this.imageHeight = imageHeight;
      this.inputWidth = inputWidth;
      this.inputHeight = inputHeight;
      this.scale = scale;
    }
  }

  /**
   * 截图转 JPEG base64：长边压到 ≤1440 控制给模型的体积；{@code withGrid} 为真时在图上
   * 叠加坐标网格，网格标注的是 **input（未缩放）坐标**，让模型照读即可点击。
   */
  private ShotImage encodeScreenshot(File file, boolean withGrid) {
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
    if (bitmap == null) {
      return new ShotImage("", 0, 0, 0, 0, 1f);
    }
    // 原图就是设备真实像素，也是 tap 使用的坐标系（不依赖 dumpsys 报的尺寸）。
    int inputW = bitmap.getWidth();
    int inputH = bitmap.getHeight();
    int longest = Math.max(inputW, inputH);
    float ratio = longest > MAX_IMAGE_EDGE ? (float) MAX_IMAGE_EDGE / longest : 1f;
    Bitmap scaled =
        ratio < 1f
            ? Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, (int) (inputW * ratio)),
                Math.max(1, (int) (inputH * ratio)),
                true)
            : bitmap;
    float scale = (float) scaled.getWidth() / inputW;
    Bitmap annotated = withGrid && inputW > 0 && inputH > 0 ? drawGrid(scaled, inputW, inputH, scale) : scaled;
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    annotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
    return new ShotImage(
        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP),
        annotated.getWidth(),
        annotated.getHeight(),
        inputW,
        inputH,
        scale);
  }

  /** 在缩略图上叠坐标网格：细线半透明不盖内容，顶部信息条给出缩放比与尺寸。 */
  private Bitmap drawGrid(Bitmap src, int inputW, int inputH, float scale) {
    Bitmap bmp = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
    if (bmp == null) {
      return src;
    }
    Canvas canvas = new Canvas(bmp);
    float textSize = Math.max(18f, Math.min(40f, bmp.getWidth() / 32f));
    Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    linePaint.setColor(0x5533B5FF);
    linePaint.setStrokeWidth(1f);
    Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    labelText.setColor(Color.WHITE);
    labelText.setTextSize(textSize);
    labelText.setTypeface(Typeface.DEFAULT_BOLD);
    Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    labelBg.setColor(0xAA000000);
    float barH = textSize + 8f;
    int step = gridStep(Math.min(inputW, inputH));
    for (int x = step; x < inputW; x += step) {
      float px = x * scale;
      canvas.drawLine(px, 0f, px, bmp.getHeight(), linePaint);
      drawLabel(canvas, String.valueOf(x), px + 2f, barH + textSize, labelText, labelBg);
    }
    for (int y = step; y < inputH; y += step) {
      float py = y * scale;
      canvas.drawLine(0f, py, bmp.getWidth(), py, linePaint);
      drawLabel(canvas, String.valueOf(y), 3f, py - 3f, labelText, labelBg);
    }
    String caption =
        "input " + inputW + "x" + inputH + "   image " + bmp.getWidth() + "x" + bmp.getHeight()
            + "   tap = image / " + (((int) (scale * 1000)) / 1000f);
    canvas.drawRect(0f, 0f, bmp.getWidth(), barH, labelBg);
    canvas.drawText(caption, 4f, textSize + 3f, labelText);
    return bmp;
  }

  /** 网格步长：让短边约分 6 段，取 1/2/5×10^k 的整值，不小于 50。 */
  private static int gridStep(int shortSide) {
    int target = Math.max(1, shortSide / 6);
    int pow = 1;
    while (pow * 10 < target) {
      pow *= 10;
    }
    int step = pow * 10;
    for (int mult : new int[] {1, 2, 5, 10}) {
      if (mult * pow >= target) {
        step = mult * pow;
        break;
      }
    }
    return Math.max(50, step);
  }

  /** 画一个带底色的小标签（网格坐标值），底色避免文字被画面吃掉。 */
  private static void drawLabel(Canvas canvas, String text, float x, float baseline, Paint tp, Paint bg) {
    float w = tp.measureText(text);
    float h = tp.getTextSize();
    canvas.drawRoundRect(x - 2f, baseline - h, x + w + 2f, baseline + h * 0.3f, 4f, 4f, bg);
    canvas.drawText(text, x, baseline, tp);
  }

  private String coordinateHint(ShotImage shot) {
    if (shot.imageWidth <= 0) {
      return "";
    }
    return "返回图片已缩放：图上量到的像素 ×"
        + String.format(java.util.Locale.ROOT, "%.3f", 1f / shot.scale)
        + "（=1/scale）才是 tap 用的坐标；"
        + "图中网格线标注的数字已是 input 坐标，可直接照读（宽 "
        + shot.inputWidth
        + " 高 "
        + shot.inputHeight
        + "）。";
  }

  private static byte[] fileBytes(File file) {
    try {
      return java.nio.file.Files.readAllBytes(file.toPath());
    } catch (Exception e) {
      throw new IllegalStateException("读取截图文件失败: " + e.getMessage(), e);
    }
  }
}
