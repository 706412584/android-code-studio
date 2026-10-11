/*
 * This file is part of AndroidCodeStudio.
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置浏览器：用 Android WebView 加载网页并把内容/截图回传给模型。
 *
 * <p><b>为什么是 WebView 而不是 Playwright/Chromium</b>（2026-10-11 实测结论）：
 * <ul>
 *   <li>Playwright 官方 chromium 是 **glibc 构建**——TLS 段对齐（32 vs Bionic 要求的 64）
 *       可以修，但 glibc 解释器与 20+ glibc 库（libdl.so.2 / libnss3.so…）在 Android 上不存在
 *   <li>tur-repo 的 chromium 是 Bionic 构建，但 deb **硬编码安装路径
 *       /data/data/com.termux**（真 Termux 应用），装不进 ACS 自己的 prefix
 *   <li>系统 WebView 是 Android 应用**一定可用**的浏览器引擎，零外部依赖
 * </ul>
 * 能力面因此收窄为「读网页」：navigate / get_text / execute_js / screenshot。
 * 复杂交互（点击、表单、多标签）留给未来的浏览器池移植（参考 Aharou BrowserTabPool）。
 *
 * <p><b>线程模型</b>：WebView 有线程亲和性，创建与全部操作都必须在主线程。
 * 工具执行在后台线程，因此每个操作走 [runOnMainAndWait] 切主线程 + latch 等待。
 * 页面加载用 [WebViewClient.onPageFinished] 回调用 [AtomicReference] 交接。
 *
 * <p><b>单实例</b>：一个 WebView 跨多次调用复用（cookie/会话连续，且创建开销大）。
 * 工具实例随运行创建、运行结束由 orchestrator 释放（见 [close]）。
 */
public final class BrowserTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

  /** 页面加载超时：超过即按「部分加载」返回（很多页面有永不完成的资源）。 */
  private static final long NAVIGATE_TIMEOUT_MS = 30_000L;

  /** JS 执行超时（回调可能永不返回，见 Aharou 的同类防护）。 */
  private static final long JS_TIMEOUT_MS = 15_000L;

  /** 截图最大像素数，防 OOM（与 Aharou 的 MAX_CAPTURE_PIXELS 同量级）。 */
  private static final long MAX_CAPTURE_PIXELS = 4_000_000L;

  /** 返回正文的最大字符数（避免把整页塞进上下文）。 */
  private static final int MAX_TEXT_CHARS = 12_000;

  private static final String ACTION_NAVIGATE = "navigate";
  private static final String ACTION_GET_TEXT = "get_text";
  private static final String ACTION_EXECUTE_JS = "execute_js";
  private static final String ACTION_SCREENSHOT = "screenshot";
  private static final String ACTION_GET_PAGE_INFO = "get_page_info";

  /** 桌面 UA：多数站点在移动 UA 下会返回精简版，模型读桌面版更完整。 */
  private static final String DESKTOP_UA =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";

  private final Context appContext;

  /**
   * 复用的 WebView（懒创建，主线程）。
   *
   * <p>volatile：创建发生在主线程，而 [ensureWebView] 的空值快路径与 [close] 的
   * 置空都在工具线程上读——不加会看到过期引用（重复创建或对已销毁实例调 loadUrl）。
   */
  private volatile WebView webView;

  /** 最近一次导航的目标 URL（get_page_info / 结果里展示）。 */
  private volatile String lastUrl = "";

  /** 最近一次页面的标题。 */
  private volatile String lastTitle = "";

  public BrowserTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return com.tom.rv2ide.ai.tool.api.ToolNames.BROWSER;
  }

  @Override
  public String getDescription() {
    return "内置浏览器：加载网页并读取内容（基于系统 WebView，无需外部依赖）。"
        + "action 取值："
        + "navigate（打开 URL，返回标题与正文文本）、"
        + "get_text（取当前页面正文）、"
        + "execute_js（执行 JS 并返回结果）、"
        + "get_page_info（当前 URL/标题）、"
        + "screenshot（截取当前视口，作为图片返回）。"
        + "典型流程：navigate → get_text 读内容；需要看视觉时 screenshot。"
        + "注意：这是**只读浏览**，不支持点击/表单交互。";
  }

  @Override
  public ToolCategory getCategory() {
    // 读网页 + 截图，无副作用（不改本地文件）。
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 单 WebView 实例，并发调用会互相踩（导航打断读取）。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "浏览器动作")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put(ACTION_NAVIGATE)
                                .put(ACTION_GET_TEXT)
                                .put(ACTION_EXECUTE_JS)
                                .put(ACTION_GET_PAGE_INFO)
                                .put(ACTION_SCREENSHOT)))
                .put(
                    "url",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "navigate 的目标 URL（无协议时补 https://）"))
                .put(
                    "script",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "execute_js 要执行的 JS 表达式（返回值 JSON 序列化后回传）")))
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase();
    if (action.isEmpty()) {
      return error("action 不能为空。可选：" + ACTION_NAVIGATE + " / " + ACTION_GET_TEXT + " / "
          + ACTION_EXECUTE_JS + " / " + ACTION_GET_PAGE_INFO + " / " + ACTION_SCREENSHOT);
    }
    if (context != null) {
      context.reportProgress("浏览器: " + action);
    }
    switch (action) {
      case ACTION_NAVIGATE:
        return doNavigate(input, context);
      case ACTION_GET_TEXT:
        return doGetText();
      case ACTION_EXECUTE_JS:
        return doExecuteJs(input);
      case ACTION_GET_PAGE_INFO:
        return ok("URL: " + (lastUrl.isEmpty() ? "(未导航)" : lastUrl)
            + "\n标题: " + (lastTitle.isEmpty() ? "(无)" : lastTitle));
      case ACTION_SCREENSHOT:
        return doScreenshot(context);
      default:
        return error("未知 action: " + action);
    }
  }

  // ---------------------------------------------------------------------------
  // 动作实现
  // ---------------------------------------------------------------------------

  private ToolResult doNavigate(JSONObject input, ToolContext context) {
    String url = input.optString("url", "").trim();
    if (url.isEmpty()) {
      return error("navigate 需要 url 参数。");
    }
    if (!url.contains("://")) {
      url = "https://" + url;
    }
    // 只放行 http/https：file:// 可读应用私有目录、javascript: 可在当前页注入、
    // intent:// 会尝试跳出到别的应用——对一个后台无交互的浏览工具，这些都无正当用途。
    // 网页内容可能诱导模型去访问它们（提示注入），因此在入口处收窄。
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return error("只支持 http/https URL（收到: " + abbreviate(url, 60) + "）");
    }
    final String target = url;

    String failure = ensureWebView();
    if (failure != null) {
      return error(failure);
    }

    // 导航等待：onPageFinished 或超时。
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> finalUrl = new AtomicReference<>(target);
    AtomicReference<String> title = new AtomicReference<>("");
    AtomicReference<String> errorText = new AtomicReference<>(null);

    runOnMainAndWait(
        () -> {
          webView.setWebViewClient(
              new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(
                    WebView view, android.webkit.WebResourceRequest request) {
                  // 页面自身发起的跳转：非 http(s)（intent://、market://、tel:…）一律拦截。
                  // 本工具后台运行、无用户交互，任何跳到别的应用的行为都不可能是用户意图；
                  // 返回 true 表示「WebView 不处理」，即静默丢弃。
                  android.net.Uri uri = request == null ? null : request.getUrl();
                  String scheme = uri == null ? null : uri.getScheme();
                  if (scheme != null
                      && !scheme.equalsIgnoreCase("http")
                      && !scheme.equalsIgnoreCase("https")) {
                    return true;
                  }
                  return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                  finalUrl.set(url == null ? target : url);
                  title.set(view.getTitle() == null ? "" : view.getTitle());
                  latch.countDown();
                }

                @Override
                public void onReceivedError(
                    WebView view, android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                  // 只报主文档失败；子资源失败不阻塞（大量页面有失败的 CDN 资源）。
                  if (request != null && request.isForMainFrame()) {
                    errorText.set(
                        error == null ? "页面加载失败" : error.getDescription().toString());
                    latch.countDown();
                  }
                }
              });
          webView.loadUrl(target);
        });

    boolean finished;
    try {
      finished = latch.await(NAVIGATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("导航被中断。");
    }

    String err = errorText.get();
    if (err != null) {
      return error("页面加载失败：" + err + "（" + target + "）");
    }

    lastUrl = finalUrl.get();
    lastTitle = title.get();

    // 页面就绪后抓正文（比标题更有信息量）。
    String text = extractText();
    StringBuilder sb = new StringBuilder();
    sb.append("已打开: ").append(lastUrl).append('\n');
    sb.append("标题: ").append(lastTitle.isEmpty() ? "(无)" : lastTitle).append('\n');
    if (!finished) {
      sb.append("说明: 页面在 ").append(NAVIGATE_TIMEOUT_MS / 1000)
          .append("s 内未完全加载，以下为已加载部分。\n");
    }
    if (text.isEmpty()) {
      sb.append("(页面没有可提取的文本——可能是纯前端应用，可尝试 execute_js 或 screenshot)");
    } else {
      sb.append("\n正文:\n").append(text);
    }
    return ok(sb.toString());
  }

  private ToolResult doGetText() {
    String failure = ensureWebView();
    if (failure != null) {
      return error(failure);
    }
    if (lastUrl.isEmpty()) {
      return error("尚未导航到任何页面，请先 action=navigate。");
    }
    String text = extractText();
    if (text.isEmpty()) {
      return ok("(页面没有可提取的文本——可能是纯前端应用，可尝试 execute_js 或 screenshot)");
    }
    return ok("URL: " + lastUrl + "\n\n正文:\n" + text);
  }

  private ToolResult doExecuteJs(JSONObject input) {
    String script = input.optString("script", "").trim();
    if (script.isEmpty()) {
      return error("execute_js 需要 script 参数。");
    }
    String failure = ensureWebView();
    if (failure != null) {
      return error(failure);
    }
    String result = evalJs(script);
    if (result == null) {
      return error("JS 执行超时或失败（页面可能已变化/被导航）。");
    }
    return ok("JS 结果:\n" + result);
  }

  private ToolResult doScreenshot(ToolContext context) {
    String failure = ensureWebView();
    if (failure != null) {
      return error(failure);
    }
    if (lastUrl.isEmpty()) {
      return error("尚未导航到任何页面，请先 action=navigate。");
    }

    BitmapHolder holder = new BitmapHolder();
    StringHolder failure2 = new StringHolder();
    runOnMainAndWait(
        () -> {
          try {
            holder.bitmap = captureViewport();
          } catch (Throwable t) {
            failure2.value = "截图失败：" + t.getMessage();
          }
        });

    if (failure2.value != null) {
      return error(failure2.value);
    }
    Bitmap bitmap = holder.bitmap;
    if (bitmap == null) {
      return error("截图未产出图像（页面可能尚未渲染或尺寸异常）。");
    }

    byte[] jpeg;
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
      jpeg = out.toByteArray();
    } finally {
      if (!bitmap.isRecycled()) {
        bitmap.recycle();
      }
    }

    String note =
        "页面截图。\nURL: " + lastUrl + "\n标题: " + lastTitle + "\n"
            + "尺寸: " + width + "x" + height + "\n";
    return ToolResult.withImage(
        getName(), note, "image/jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP));
  }

  // ---------------------------------------------------------------------------
  // WebView 生命周期与操作
  // ---------------------------------------------------------------------------

  /** 确保 WebView 已创建（主线程）。返回错误信息或 null。 */
  private String ensureWebView() {
    if (webView != null) {
      return null;
    }
    StringHolder failure = new StringHolder();
    runOnMainAndWait(
        () -> {
          try {
            createWebView();
          } catch (Throwable t) {
            failure.value = "WebView 创建失败：" + t.getMessage();
          }
        });
    return failure.value;
  }

  /** 只在主线程调用。 */
  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  private void createWebView() {
    WebView view = new WebView(appContext);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setBuiltInZoomControls(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    settings.setUserAgentString(DESKTOP_UA);
    // 关闭 file/content 访问：本工具只浏览 http(s)，而 file:// 在 WebView 里能读
    // 应用私有目录（凭据/会话文件）。入口处的 scheme 校验是第一道，这里是兜底——
    // 防止页面内嵌 iframe 或重定向绕过入口校验。
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);

    // 第三方 cookie：部分站点（登录态、验证码）依赖。
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

    // 弹窗/新窗口：本工具无交互界面，静默吞掉（否则 WebView 可能因无 handler 卡住）。
    view.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public boolean onCreateWindow(
              WebView parent, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            return false;
          }

          @Override
          public boolean onJsAlert(
              WebView v, String url, String message, android.webkit.JsResult result) {
            result.confirm();
            return true;
          }

          @Override
          public boolean onJsConfirm(
              WebView v, String url, String message, android.webkit.JsResult result) {
            result.confirm();
            return true;
          }
        });

    // 视口尺寸：1080x1920 物理像素（常见手机），保证截图与布局有合理默认值。
    view.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1080, 1920);
    webView = view;
  }

  /** 抓正文文本（读 body innerText，截断到上限）。任意线程可调。 */
  private String extractText() {
    String js =
        "(function(){try{var t=(document.body&&document.body.innerText)||'';"
            + "return t.replace(/\\n{3,}/g,'\\n\\n').trim().slice(0,"
            + MAX_TEXT_CHARS
            + ");}catch(e){return '';}})()";
    String result = evalJs(js);
    return result == null ? "" : result;
  }

  /**
   * 执行 JS 并等结果。任意线程可调；内部切主线程 + 超时。
   *
   * @return 结果字符串；超时/失败返回 null
   */
  private String evalJs(String script) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> out = new AtomicReference<>(null);
    runOnMainAndWait(
        () -> {
          try {
            webView.evaluateJavascript(
                script,
                value -> {
                  out.set(unquoteJson(value));
                  latch.countDown();
                });
          } catch (Throwable t) {
            latch.countDown();
          }
        });
    try {
      if (!latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        log.warn("JS 执行超时: {}", abbreviate(script, 80));
        return null;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    return out.get();
  }

  /** WebView 返回 JSON 编码的字符串（外层带引号），解一层。 */
  private static String unquoteJson(String value) {
    if (value == null) {
      return "null";
    }
    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
      try {
        return new JSONObject("{\"v\":" + value + "}").getString("v");
      } catch (Exception e) {
        return value;
      }
    }
    return value;
  }

  /** 截取当前视口。只在主线程调用。 */
  private Bitmap captureViewport() {
    int w = webView.getWidth();
    int h = webView.getHeight();
    if (w <= 0 || h <= 0) {
      // WebView 从未 attach 到窗口（本工具无界面），宽高可能是 0——此时直接 draw
      // 会画出一张空白图。补一次 measure+layout 给它的布局盒（与 Aharou 同法），
      // 而不是只把画布设成 1080x1920：那样位图有尺寸、WebView 没有，仍是白图。
      w = 1080;
      h = 1920;
      webView.measure(
          View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      webView.layout(0, 0, w, h);
    }
    long pixels = (long) w * h;
    if (pixels > MAX_CAPTURE_PIXELS) {
      // 等比缩小到上限内，而不是拒绝（用户/模型要的是看到页面）。
      double scale = Math.sqrt((double) MAX_CAPTURE_PIXELS / pixels);
      w = Math.max(1, (int) (w * scale));
      h = Math.max(1, (int) (h * scale));
    }
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(0xFFFFFFFF);
    webView.draw(canvas);
    return bitmap;
  }

  /** 释放 WebView（运行结束时由 orchestrator 调用；重复调用幂等）。 */
  public void close() {
    WebView view = webView;
    webView = null;
    if (view != null) {
      runOnMainAndWait(
          () -> {
            try {
              view.loadUrl("about:blank");
              view.destroy();
            } catch (Throwable t) {
              log.warn("WebView 释放失败", t);
            }
          });
    }
  }

  // ---------------------------------------------------------------------------
  // 工具
  // ---------------------------------------------------------------------------

  /** 在任意线程上跑到主线程执行并等待完成。 */
  private static void runOnMainAndWait(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action.run();
      return;
    }
    CountDownLatch latch = new CountDownLatch(1);
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                action.run();
              } finally {
                latch.countDown();
              }
            });
    try {
      if (!latch.await(NAVIGATE_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)) {
        log.warn("主线程操作超时");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }

  /** 简单可变容器：跨越 lambda 传回位图。 */
  private static final class BitmapHolder {
    Bitmap bitmap;
  }

  private static final class StringHolder {
    String value;
  }
}
