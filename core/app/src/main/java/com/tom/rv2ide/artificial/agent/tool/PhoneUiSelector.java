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

import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellRequest;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 设备 UI 的共享「读取 + 定位」助手。
 *
 * <p><b>为什么单独抽一个类</b>：{@code phone_view_hierarchy}（本包）负责把 UI 树展示给模型，
 * 而 {@code phone_click_view} / {@code phone_wait_for}（phone-act 提供）需要「按 resource-id /
 * text / content-desc 找到节点 → 取其 bounds 中心 → 点击/等待」。二者做的是同一件事
 * （{@code uiautomator dump} → 解析 XML → 匹配节点），只是消费方式不同。把这段逻辑收敛到
 * 这里，避免每个工具各写一份 XML 解析与匹配。
 *
 * <p><b>为什么用 uiautomator dump 而不是无障碍服务</b>：dump 走的是 adb 级 shell
 * （Shizuku，uid 2000），无需用户开启无障碍、也不占用无障碍通道；语义与
 * {@code adb shell uiautomator dump} 完全一致。
 *
 * <p><b>本类还托管 phone_* 工具共用的 shell 执行入口</b>（{@link #exec}）。原因同上：
 * 三个观察类工具（截图 / 节点树 / 前台界面）都要「以 adb 权限执行一条命令，并在后端
 * 没有 adb 权限时给出明确错误」。这份判定只应写一次。由于本任务只允许新增 4 个文件，
 * 它随选择器一起放在这里；phone-act 的工具直接复用即可。
 *
 * <p>本类只做读取，不改变设备状态；副作用由调用方（phone-act）发起。
 */
public final class PhoneUiSelector {

  private static final Logger log = LoggerFactory.getLogger(PhoneUiSelector.class);

  /** uiautomator dump 的默认超时。dump 需要等待窗口 idle，比普通命令慢。 */
  public static final long DEFAULT_DUMP_TIMEOUT_MS = 20_000L;

  /** dump 临时文件的路径前缀。uiautomator 以 shell 身份运行，可写 /sdcard。 */
  private static final String DUMP_PATH_PREFIX = "/sdcard/acs_ui_dump_";

  /** 临时文件名去重序号（同一毫秒内多次调用时避免互相覆盖）。 */
  private static final AtomicInteger SEQ = new AtomicInteger();

  private static final Pattern BOUNDS_PATTERN =
      Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

  /** 从 ActivityRecord / Window 行里抽出 {@code package/activity}。 */
  private static final Pattern COMPONENT_PATTERN =
      Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)");

  private PhoneUiSelector() {}

  // =====================================================================================
  // 共享 shell 执行
  // =====================================================================================

  /** 一次 shell 执行的结果，附带实际使用的后端名，便于错误信息说清「为什么没跑成」。 */
  public static final class Exec {
    /** 命令是否以 exit=0 成功结束。 */
    public final boolean ok;
    public final String stdout;
    public final String stderr;
    /** 失败原因；成功时为 null。 */
    public final String error;
    /** 实际执行该命令的后端展示名；未执行时为空串。 */
    public final String backendName;

    Exec(boolean ok, String stdout, String stderr, String error, String backendName) {
      this.ok = ok;
      this.stdout = stdout == null ? "" : stdout;
      this.stderr = stderr == null ? "" : stderr;
      this.error = error;
      this.backendName = backendName == null ? "" : backendName;
    }

    static Exec failure(String message) {
      return new Exec(false, "", "", message, "");
    }
  }

  /**
   * 以 adb 级权限执行一条命令。
   *
   * <p>这是三个观察类工具与 phone-act 共用的入口。实现委托给全项目唯一的设备命令执行器
   * {@link PhoneShellRunner#exec}，其语义是<b>严格要求 adb 权限、绝不降级本地进程</b>：
   * {@code screencap} / {@code uiautomator} / {@code dumpsys} / {@code input} 在普通应用
   * uid 下必然失败，降级只会得到「看起来成功实则空」的误导性结果。无 adb 权限时返回
   * 可读错误（含当前后端与启用指引），而不是执行一条注定失败的命令让模型误判。
   *
   * <p>本方法签名保持不变，因此调用方（本包观察类工具与 phone-act）无需改动。
   */
  public static Exec exec(ShellBackendRegistry registry, String command, long timeoutMs) {
    if (command == null || command.trim().isEmpty()) {
      return Exec.failure("命令为空");
    }
    long timeout = timeoutMs > 0 ? timeoutMs : ShellRequest.DEFAULT_TIMEOUT_MS;
    PhoneShellRunner.Output output = PhoneShellRunner.exec(registry, command, timeout);
    if (output.ok) {
      return new Exec(true, output.stdout, output.stderr, null, output.channel);
    }
    String error = output.note;
    if (error == null || error.trim().isEmpty()) {
      // 兜底：执行器未给说明时，至少给出退出码与命令。
      error = "命令失败（exit=" + output.exitCode + "）：" + command;
    }
    return new Exec(false, output.stdout, output.stderr, error, output.channel);
  }

  // =====================================================================================
  // 节点模型
  // =====================================================================================

  /** uiautomator 节点。字段直接取自 XML 属性；bounds 已拆成四个整数。 */
  public static final class Node {
    public final int index;
    public final String className;
    public final String text;
    public final String resourceId;
    public final String contentDesc;
    public final String packageName;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final boolean clickable;
    public final boolean longClickable;
    public final boolean scrollable;
    public final boolean enabled;
    public final boolean focused;
    public final boolean selected;
    public final boolean checkable;
    public final boolean checked;
    public final boolean password;
    public final List<Node> children;

    Node(
        int index,
        String className,
        String text,
        String resourceId,
        String contentDesc,
        String packageName,
        int left,
        int top,
        int right,
        int bottom,
        boolean clickable,
        boolean longClickable,
        boolean scrollable,
        boolean enabled,
        boolean focused,
        boolean selected,
        boolean checkable,
        boolean checked,
        boolean password,
        List<Node> children) {
      this.index = index;
      this.className = nz(className);
      this.text = nz(text);
      this.resourceId = nz(resourceId);
      this.contentDesc = nz(contentDesc);
      this.packageName = nz(packageName);
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.clickable = clickable;
      this.longClickable = longClickable;
      this.scrollable = scrollable;
      this.enabled = enabled;
      this.focused = focused;
      this.selected = selected;
      this.checkable = checkable;
      this.checked = checked;
      this.password = password;
      this.children = children == null ? Collections.<Node>emptyList() : children;
    }

    public int width() {
      return Math.max(0, right - left);
    }

    public int height() {
      return Math.max(0, bottom - top);
    }

    /** 节点是否有可见面积（uiautomator 会给不可见节点 bounds=[0,0][0,0]）。 */
    public boolean hasBounds() {
      return width() > 0 && height() > 0;
    }

    public int centerX() {
      return (left + right) / 2;
    }

    public int centerY() {
      return (top + bottom) / 2;
    }

    /** 去掉包名前缀的短类名，如 {@code android.widget.TextView} → {@code TextView}。 */
    public String shortClassName() {
      int dot = className.lastIndexOf('.');
      return dot >= 0 && dot + 1 < className.length() ? className.substring(dot + 1) : className;
    }

    /** 人类可读的一行摘要，用于错误信息与调试。 */
    public String describe() {
      StringBuilder sb = new StringBuilder(shortClassName());
      if (!resourceId.isEmpty()) {
        sb.append(" id=").append(resourceId);
      }
      if (!text.isEmpty()) {
        sb.append(" text=\"").append(text).append('"');
      }
      if (!contentDesc.isEmpty()) {
        sb.append(" desc=\"").append(contentDesc).append('"');
      }
      sb.append(" bounds=[")
          .append(left)
          .append(',')
          .append(top)
          .append("][")
          .append(right)
          .append(',')
          .append(bottom)
          .append(']');
      return sb.toString();
    }
  }

  // =====================================================================================
  // 选择器
  // =====================================================================================

  /**
   * 节点选择条件。字段为空/为 null 表示「不限制该维度」。
   *
   * <p>多个条件之间是 AND。{@link #index} 指定取第几个匹配（DFS 顺序，0 起）。
   */
  public static final class Selector {
    public String resourceId;
    public String text;
    public String contentDesc;
    public String className;
    /** 非 null 时要求节点的 clickable 与之相等。 */
    public Boolean clickable;
    /** 非 null 时要求节点的 enabled 与之相等。 */
    public Boolean enabled;
    /** text 用「包含」而不是「相等」匹配（大小写不敏感）。 */
    public boolean textContains;
    /** content-desc 用「包含」而不是「相等」匹配（大小写不敏感）。 */
    public boolean descContains;
    /** 取第 index 个匹配项。 */
    public int index;

    public Selector resourceId(String value) {
      this.resourceId = value;
      return this;
    }

    public Selector text(String value) {
      this.text = value;
      return this;
    }

    public Selector contentDesc(String value) {
      this.contentDesc = value;
      return this;
    }

    public Selector className(String value) {
      this.className = value;
      return this;
    }

    public Selector clickable(Boolean value) {
      this.clickable = value;
      return this;
    }

    public Selector enabled(Boolean value) {
      this.enabled = value;
      return this;
    }

    public Selector textContains(boolean value) {
      this.textContains = value;
      return this;
    }

    public Selector descContains(boolean value) {
      this.descContains = value;
      return this;
    }

    public Selector index(int value) {
      this.index = Math.max(0, value);
      return this;
    }

    /** 是否至少指定了一个匹配维度。 */
    public boolean isEmpty() {
      return isBlank(resourceId)
          && isBlank(text)
          && isBlank(contentDesc)
          && isBlank(className)
          && clickable == null
          && enabled == null;
    }

    /**
     * 从工具入参构造选择器，方便 phone-act 直接透传 JSON。
     *
     * <p>识别键：{@code resourceId} / {@code text} / {@code contentDesc} / {@code className}
     * / {@code clickable} / {@code enabled} / {@code textContains} / {@code descContains} / {@code index}。
     */
    public static Selector fromJson(JSONObject input) {
      Selector selector = new Selector();
      if (input == null) {
        return selector;
      }
      selector.resourceId = input.optString("resourceId", "");
      selector.text = input.optString("text", "");
      selector.contentDesc = input.optString("contentDesc", "");
      selector.className = input.optString("className", "");
      if (input.has("clickable")) {
        selector.clickable = input.optBoolean("clickable");
      }
      if (input.has("enabled")) {
        selector.enabled = input.optBoolean("enabled");
      }
      selector.textContains = input.optBoolean("textContains", false);
      selector.descContains = input.optBoolean("descContains", false);
      selector.index = input.optInt("index", 0);
      return selector;
    }

    /** 给模型看的条件描述，用于「未找到匹配节点」的错误信息。 */
    public String describe() {
      List<String> parts = new ArrayList<>();
      if (!isBlank(resourceId)) parts.add("resourceId=" + resourceId);
      if (!isBlank(text)) parts.add((textContains ? "text~=" : "text=") + text);
      if (!isBlank(contentDesc)) parts.add((descContains ? "desc~=" : "desc=") + contentDesc);
      if (!isBlank(className)) parts.add("class=" + className);
      if (clickable != null) parts.add("clickable=" + clickable);
      if (enabled != null) parts.add("enabled=" + enabled);
      if (index > 0) parts.add("index=" + index);
      return parts.isEmpty() ? "(空条件)" : String.join(" ", parts);
    }
  }

  // =====================================================================================
  // dump / parse
  // =====================================================================================

  /** 一次 UI dump 的结果。 */
  public static final class Dump {
    public final Node root;
    public final String xml;
    public final int nodeCount;
    /** 失败原因；成功时为 null。 */
    public final String error;

    private Dump(Node root, String xml, int nodeCount, String error) {
      this.root = root;
      this.xml = xml;
      this.nodeCount = nodeCount;
      this.error = error;
    }

    static Dump ok(Node root, String xml, int nodeCount) {
      return new Dump(root, xml, nodeCount, null);
    }

    static Dump failure(String error) {
      return new Dump(null, "", 0, error);
    }

    public boolean isOk() {
      return root != null;
    }
  }

  /** 用默认超时 dump 当前界面。 */
  public static Dump dump(ShellBackendRegistry registry, boolean compressed) {
    return dump(registry, compressed, DEFAULT_DUMP_TIMEOUT_MS);
  }

  /**
   * dump 当前界面并解析成节点树。
   *
   * <p>实现：{@code uiautomator dump <file> && cat <file>; rm -f <file>}。dump 把 XML 写到
   * /sdcard 下的临时文件，再 cat 回 stdout（纯文本，可安全经过 shell 后端的 UTF-8 通道），
   * 最后清理临时文件。
   *
   * @param compressed 传给 uiautomator 的 {@code --compressed}，会省略无文本/无 id 的节点，
   *     输出更小但可能丢失结构性节点
   */
  public static Dump dump(ShellBackendRegistry registry, boolean compressed, long timeoutMs) {
    String path =
        DUMP_PATH_PREFIX + System.currentTimeMillis() + "_" + SEQ.incrementAndGet() + ".xml";
    // 保留 dump/cat 的退出码：若 dump 失败，&& 短路，rc 即 dump 的失败码；
    // 最后的 rm 只负责清理，不得覆盖这个码（否则失败会被当成成功，模型误判）。
    String command =
        "uiautomator dump "
            + (compressed ? "--compressed " : "")
            + quote(path)
            + " && cat "
            + quote(path)
            + "; rc=$?; rm -f "
            + quote(path)
            + "; exit $rc";

    Exec exec = exec(registry, command, timeoutMs);
    if (!exec.ok) {
      return Dump.failure(exec.error);
    }

    String xml = extractXml(exec.stdout);
    if (xml == null) {
      StringBuilder sb = new StringBuilder("uiautomator dump 没有返回 XML。");
      String out = trim(exec.stdout, 500);
      String err = trim(exec.stderr, 500);
      if (!out.isEmpty()) {
        sb.append("\n原始输出：\n").append(out);
      }
      if (!err.isEmpty()) {
        sb.append("\nstderr：\n").append(err);
      }
      sb.append("\n提示：界面可能正处于动画/加载中，稍后重试；");
      sb.append("或该窗口设置了 FLAG_SECURE（如支付、密码界面），uiautomator 无法读取。");
      return Dump.failure(sb.toString());
    }

    Node root = parse(xml);
    if (root == null) {
      return Dump.failure("解析 UI XML 失败（格式异常）。原始内容前 500 字符：\n" + trim(xml, 500));
    }
    return Dump.ok(root, xml, countNodes(root));
  }

  /**
   * 解析 {@code uiautomator dump} 产出的 XML，返回根节点。
   *
   * <p>文档根是 {@code <hierarchy>}，其下第一个 {@code <node>} 才是真正的根；也兼容
   * 文档根直接是 {@code <node>} 的情况。
   *
   * @return 根节点；解析失败返回 null
   */
  public static Node parse(String xml) {
    if (xml == null || xml.trim().isEmpty()) {
      return null;
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // 关闭外部实体解析：dump 内容来自设备自身，但仍按最小权限解析，避免 XXE 面。
      trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
      trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
      trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(new InputSource(new StringReader(xml)));
      Element rootElement = document.getDocumentElement();
      if (rootElement == null) {
        return null;
      }
      Element nodeElement;
      if ("node".equals(rootElement.getTagName())) {
        nodeElement = rootElement;
      } else {
        nodeElement = firstChildElement(rootElement, "node");
      }
      return nodeElement == null ? null : parseNode(nodeElement);
    } catch (Exception e) {
      log.warn("解析 UI XML 失败", e);
      return null;
    }
  }

  private static Node parseNode(Element element) {
    List<Node> children = new ArrayList<>();
    NodeList childNodes = element.getChildNodes();
    for (int i = 0; i < childNodes.getLength(); i++) {
      org.w3c.dom.Node child = childNodes.item(i);
      if (child instanceof Element && "node".equals(((Element) child).getTagName())) {
        children.add(parseNode((Element) child));
      }
    }

    int[] bounds = parseBounds(attr(element, "bounds"));
    return new Node(
        parseInt(attr(element, "index"), 0),
        attr(element, "class"),
        attr(element, "text"),
        attr(element, "resource-id"),
        attr(element, "content-desc"),
        attr(element, "package"),
        bounds[0],
        bounds[1],
        bounds[2],
        bounds[3],
        parseBool(attr(element, "clickable")),
        parseBool(attr(element, "long-clickable")),
        parseBool(attr(element, "scrollable")),
        parseBoolDefaultTrue(attr(element, "enabled")),
        parseBool(attr(element, "focused")),
        parseBool(attr(element, "selected")),
        parseBool(attr(element, "checkable")),
        parseBool(attr(element, "checked")),
        parseBool(attr(element, "password")),
        children);
  }

  // =====================================================================================
  // 查找 / 定位
  // =====================================================================================

  /** 返回第一个匹配的节点；无匹配返回 null。 */
  public static Node find(Node root, Selector selector) {
    if (root == null || selector == null || selector.isEmpty()) {
      return null;
    }
    List<Node> matches = new ArrayList<>();
    collectMatches(root, selector, matches, selector.index + 1);
    return matches.size() > selector.index ? matches.get(selector.index) : null;
  }

  /** 返回全部匹配节点（DFS 顺序）。 */
  public static List<Node> findAll(Node root, Selector selector) {
    List<Node> matches = new ArrayList<>();
    if (root == null || selector == null || selector.isEmpty()) {
      return matches;
    }
    collectMatches(root, selector, matches, Integer.MAX_VALUE);
    return matches;
  }

  private static void collectMatches(Node node, Selector selector, List<Node> out, int limit) {
    if (out.size() >= limit) {
      return;
    }
    if (matches(node, selector)) {
      out.add(node);
    }
    for (Node child : node.children) {
      collectMatches(child, selector, out, limit);
    }
  }

  /** 节点是否满足全部条件。 */
  public static boolean matches(Node node, Selector selector) {
    if (node == null || selector == null) {
      return false;
    }
    if (!isBlank(selector.resourceId) && !matchResourceId(node.resourceId, selector.resourceId)) {
      return false;
    }
    if (!isBlank(selector.text)) {
      if (selector.textContains) {
        if (!containsIgnoreCase(node.text, selector.text)) return false;
      } else if (!node.text.equals(selector.text)) {
        return false;
      }
    }
    if (!isBlank(selector.contentDesc)) {
      if (selector.descContains) {
        if (!containsIgnoreCase(node.contentDesc, selector.contentDesc)) return false;
      } else if (!node.contentDesc.equals(selector.contentDesc)) {
        return false;
      }
    }
    if (!isBlank(selector.className) && !matchClassName(node.className, selector.className)) {
      return false;
    }
    if (selector.clickable != null && node.clickable != selector.clickable) {
      return false;
    }
    return selector.enabled == null || node.enabled == selector.enabled;
  }

  /** 定位结果：匹配到的节点与其 bounds 中心。 */
  public static final class Locate {
    public final Node node;
    /** {centerX, centerY}；失败时为 null。 */
    public final int[] center;
    public final String error;

    private Locate(Node node, int[] center, String error) {
      this.node = node;
      this.center = center;
      this.error = error;
    }

    public boolean isOk() {
      return center != null;
    }
  }

  /**
   * dump → 查找 → 计算 bounds 中心。phone-act 的 {@code phone_click_view} 直接用其返回值点击。
   *
   * <p>失败（后端无 adb 权限 / 未找到 / 节点无可见区域）时 {@link Locate#error} 非空，
   * 且错误信息里带上实际使用的选择条件与（若 dump 成功）界面上的可点击节点提示，
   * 让模型能据此改条件重试，而不是盲目重试同一条。
   */
  public static Locate locate(
      ShellBackendRegistry registry, Selector selector, boolean compressed, long timeoutMs) {
    if (selector == null || selector.isEmpty()) {
      return new Locate(null, null, "未提供定位条件（resourceId / text / contentDesc 至少给一个）。");
    }
    Dump dump = dump(registry, compressed, timeoutMs);
    if (!dump.isOk()) {
      return new Locate(null, null, dump.error);
    }
    Node node = find(dump.root, selector);
    if (node == null) {
      return new Locate(
          null,
          null,
          "未找到匹配节点：" + selector.describe() + "。\n" + clickableHint(dump.root));
    }
    if (!node.hasBounds()) {
      return new Locate(
          node, null, "匹配到节点但没有可见区域（bounds 为空）：" + node.describe() + "。可能已不可见。");
    }
    return new Locate(node, new int[] {node.centerX(), node.centerY()}, null);
  }

  /** 找不到节点时，附上当前界面上可点击节点的摘要，供模型改条件。 */
  private static String clickableHint(Node root) {
    Selector clickable = new Selector().clickable(Boolean.TRUE);
    List<Node> nodes = findAll(root, clickable);
    if (nodes.isEmpty()) {
      return "当前界面没有可点击节点（界面可能仍在加载，可稍后重试）。";
    }
    StringBuilder sb = new StringBuilder("当前界面上可点击的节点（最多 10 个）：");
    int shown = 0;
    for (Node node : nodes) {
      if (shown >= 10) break;
      String label = !node.text.isEmpty() ? node.text : node.contentDesc;
      if (label.isEmpty() && node.resourceId.isEmpty()) {
        continue;
      }
      sb.append("\n  - ").append(node.describe());
      shown++;
    }
    if (shown == 0) {
      return "当前界面没有带文本/id 的可点击节点（界面可能仍在加载，可稍后重试）。";
    }
    return sb.toString();
  }

  // =====================================================================================
  // 渲染（供 phone_view_hierarchy 使用）
  // =====================================================================================

  /** 把节点树渲染成缩进文本。{@code maxNodes} 限制输出节点数，超出时在末尾标注。 */
  public static String render(Node root, int maxNodes) {
    StringBuilder sb = new StringBuilder();
    int limit = maxNodes > 0 ? maxNodes : Integer.MAX_VALUE;
    int[] count = {0};
    renderNode(root, 0, limit, count, sb);
    int total = countNodes(root);
    if (count[0] < total) {
      sb.append("... (共 ")
          .append(total)
          .append(" 个节点，已显示前 ")
          .append(count[0])
          .append(" 个；可提高 maxNodes，或用 packageName 过滤)\n");
    }
    return sb.toString();
  }

  private static void renderNode(Node node, int depth, int limit, int[] count, StringBuilder sb) {
    if (node == null || count[0] >= limit) {
      return;
    }
    count[0]++;
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
    sb.append(node.shortClassName());
    if (!node.resourceId.isEmpty()) {
      sb.append(" id=").append(node.resourceId);
    }
    if (!node.text.isEmpty()) {
      sb.append(" text=\"").append(escape(node.text)).append('"');
    }
    if (!node.contentDesc.isEmpty()) {
      sb.append(" desc=\"").append(escape(node.contentDesc)).append('"');
    }
    sb.append(" [")
        .append(node.left)
        .append(',')
        .append(node.top)
        .append("][")
        .append(node.right)
        .append(',')
        .append(node.bottom)
        .append(']');
    if (node.clickable) {
      sb.append(" clickable");
    }
    if (node.longClickable) {
      sb.append(" longClick");
    }
    if (node.scrollable) {
      sb.append(" scrollable");
    }
    if (node.checkable) {
      sb.append(node.checked ? " checked" : " checkable");
    }
    if (node.selected) {
      sb.append(" selected");
    }
    if (node.focused) {
      sb.append(" focused");
    }
    if (!node.enabled) {
      sb.append(" disabled");
    }
    if (node.password) {
      sb.append(" password");
    }
    sb.append('\n');
    for (Node child : node.children) {
      renderNode(child, depth + 1, limit, count, sb);
    }
  }

  /** 统计节点总数。 */
  public static int countNodes(Node node) {
    if (node == null) {
      return 0;
    }
    int total = 1;
    for (Node child : node.children) {
      total += countNodes(child);
    }
    return total;
  }

  // =====================================================================================
  // 内部工具
  // =====================================================================================

  /**
   * 匹配 resource-id。支持三种写法：
   * <ul>
   *   <li>完整 id：{@code com.example:id/title} —— 精确相等
   *   <li>短 id：{@code :id/title} —— 后缀匹配
   *   <li>裸名：{@code title} —— 匹配 {@code /title} 或 {@code :id/title} 结尾
   * </ul>
   */
  static boolean matchResourceId(String nodeId, String wanted) {
    if (isBlank(nodeId) || isBlank(wanted)) {
      return false;
    }
    if (nodeId.equals(wanted)) {
      return true;
    }
    if (wanted.startsWith(":id/")) {
      return nodeId.endsWith(wanted) || nodeId.endsWith("/" + wanted.substring(1));
    }
    if (wanted.contains(":id/")) {
      return nodeId.endsWith(wanted);
    }
    return nodeId.endsWith("/" + wanted) || nodeId.endsWith(":id/" + wanted);
  }

  private static boolean matchClassName(String nodeClass, String wanted) {
    if (isBlank(nodeClass) || isBlank(wanted)) {
      return false;
    }
    return nodeClass.equals(wanted) || nodeClass.endsWith("." + wanted);
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    if (haystack == null || needle == null) {
      return false;
    }
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static int[] parseBounds(String bounds) {
    if (bounds != null) {
      Matcher matcher = BOUNDS_PATTERN.matcher(bounds);
      if (matcher.find()) {
        return new int[] {
          parseInt(matcher.group(1), 0),
          parseInt(matcher.group(2), 0),
          parseInt(matcher.group(3), 0),
          parseInt(matcher.group(4), 0)
        };
      }
    }
    return new int[] {0, 0, 0, 0};
  }

  private static String extractXml(String output) {
    if (output == null) {
      return null;
    }
    int start = output.indexOf('<');
    int end = output.lastIndexOf('>');
    if (start < 0 || end <= start) {
      return null;
    }
    return output.substring(start, end + 1);
  }

  private static Element firstChildElement(Element parent, String tagName) {
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      org.w3c.dom.Node child = nodes.item(i);
      if (child instanceof Element && tagName.equals(((Element) child).getTagName())) {
        return (Element) child;
      }
    }
    return null;
  }

  private static String attr(Element element, String name) {
    if (element == null || !element.hasAttribute(name)) {
      return "";
    }
    return nz(element.getAttribute(name));
  }

  private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
    try {
      factory.setFeature(feature, value);
    } catch (Exception ignored) {
      // Android 的解析器不一定支持全部 SAX 特性；不支持时跳过，不影响正常解析。
    }
  }

  private static boolean parseBool(String value) {
    return "true".equalsIgnoreCase(value);
  }

  private static boolean parseBoolDefaultTrue(String value) {
    // enabled 缺省视为 true（uiautomator 一定会输出，这里只是兜底）
    return value == null || value.isEmpty() || "true".equalsIgnoreCase(value);
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static String trim(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  /** 从 ActivityRecord / Window 行里抽出组件名。返回 {package, activity} 或 null。 */
  static String[] extractComponent(String line) {
    if (line == null) {
      return null;
    }
    Matcher matcher = COMPONENT_PATTERN.matcher(line);
    if (!matcher.find()) {
      return null;
    }
    String pkg = matcher.group(1);
    String activity = matcher.group(2);
    if (activity.startsWith(".")) {
      activity = pkg + activity;
    } else if (!activity.contains(".")) {
      activity = pkg + "." + activity;
    }
    return new String[] {pkg, activity};
  }
}
