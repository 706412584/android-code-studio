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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 助手聊天界面的外观配置（字号 / 卡片大小 / 字体颜色 / 头像）。
 *
 * <p>与 {@link AgentToolSettings}、{@link PrefsChatModeStore} 共用同一个偏好文件
 * （{@code ai_agent_tools}）：三者都是「agent 怎么用」的配置，分开存会让「重置设置」
 * 需要清三处。
 *
 * <p><b>非法值一律回退默认</b>：设置可能被外部工具写坏（改偏好文件、降级安装后残留），
 * 而一个越界的字号会让整个聊天界面不可读。每个取值都夹到合法区间。
 */
public final class AssistantUiStyleStore {

  private static final String PREFS_NAME = "ai_agent_tools";

  /** 聊天正文字号（sp）。 */
  public static final String KEY_TEXT_SIZE = "assistant_ui_text_size";

  /** 卡片大小缩放系数。 */
  public static final String KEY_CARD_SCALE = "assistant_ui_card_scale";

  /** 助手正文自定义颜色（ARGB）；{@link #COLOR_FOLLOW_THEME} 表示跟随主题。 */
  public static final String KEY_TEXT_COLOR = "assistant_ui_text_color";

  /** 自定义头像的本地文件路径；未设置时用内置头像（见 {@link #KEY_AVATAR_BUILTIN}）。 */
  public static final String KEY_AVATAR_PATH = "assistant_avatar_path";

  /**
   * 内置头像的标识；{@link #AVATAR_BUILTIN_DEFAULT} 表示用默认（机器人）。
   *
   * <p>与 {@link #KEY_AVATAR_PATH} 二选一：设了自定义图片时优先用图片，
   * 否则用这里选的内置头像。分开存让「清除自定义图片」能回到用户之前选的内置头像，
   * 而不是被重置成默认那个。
   */
  public static final String KEY_AVATAR_BUILTIN = "assistant_avatar_builtin";

  /** 内置头像标识：默认（机器人）。 */
  public static final String AVATAR_BUILTIN_DEFAULT = "default";

  /** 内置头像标识：动漫少女。 */
  public static final String AVATAR_BUILTIN_ANIME = "anime";

  /** 内置头像标识：猫咪。 */
  public static final String AVATAR_BUILTIN_CAT = "cat";

  /** 内置头像标识：狐狸。 */
  public static final String AVATAR_BUILTIN_FOX = "fox";

  /** 内置头像标识：星火。 */
  public static final String AVATAR_BUILTIN_SPARK = "spark";

  /** 全部内置头像标识（顺序即设置页展示顺序）。 */
  public static final String[] AVATAR_BUILTINS = {
    AVATAR_BUILTIN_DEFAULT,
    AVATAR_BUILTIN_ANIME,
    AVATAR_BUILTIN_CAT,
    AVATAR_BUILTIN_FOX,
    AVATAR_BUILTIN_SPARK
  };

  /** {@link #KEY_TEXT_COLOR} 的「跟随主题」哨兵值。 */
  public static final int COLOR_FOLLOW_THEME = 0;

  /** 默认正文字号（sp）。比 Material 的 BodyMedium（14sp）小一档。 */
  public static final float DEFAULT_TEXT_SIZE = 13f;

  /** 字号可调下限（sp）。低于 10 在手机上已难辨认。 */
  public static final float MIN_TEXT_SIZE = 10f;

  /** 字号可调上限（sp）。 */
  public static final float MAX_TEXT_SIZE = 20f;

  /** 卡片缩放默认值（1.0 = 与 XML 里写的一致）。 */
  public static final float DEFAULT_CARD_SCALE = 1.0f;

  /** 卡片缩放下限。低于 0.8 时卡片内边距几乎消失，展开态内容会贴边。 */
  public static final float MIN_CARD_SCALE = 0.8f;

  /** 卡片缩放上限。高于 1.3 时卡片占用过多纵向空间。 */
  public static final float MAX_CARD_SCALE = 1.3f;

  private final SharedPreferences prefs;

  public AssistantUiStyleStore(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 正文字号（sp）；越界或未设置时返回默认值。 */
  public float getTextSize() {
    float value = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
    return clampTextSize(value);
  }

  /** 设置正文字号；越界值夹到区间内。 */
  public void setTextSize(float sp) {
    prefs.edit().putFloat(KEY_TEXT_SIZE, clampTextSize(sp)).apply();
  }

  /** 卡片缩放系数；越界或未设置时返回默认值。 */
  public float getCardScale() {
    float value = prefs.getFloat(KEY_CARD_SCALE, DEFAULT_CARD_SCALE);
    return clampCardScale(value);
  }

  /** 设置卡片缩放；越界值夹到区间内。 */
  public void setCardScale(float scale) {
    prefs.edit().putFloat(KEY_CARD_SCALE, clampCardScale(scale)).apply();
  }

  /** 助手正文自定义颜色；{@link #COLOR_FOLLOW_THEME} 表示跟随主题。 */
  public int getTextColor() {
    return prefs.getInt(KEY_TEXT_COLOR, COLOR_FOLLOW_THEME);
  }

  /** 设置助手正文颜色；传 {@link #COLOR_FOLLOW_THEME} 恢复跟随主题。 */
  public void setTextColor(int color) {
    prefs.edit().putInt(KEY_TEXT_COLOR, color).apply();
  }

  /** 自定义头像文件路径；未设置或文件已不存在时返回 null（调用方用默认头像）。 */
  public String getAvatarPath() {
    String path = prefs.getString(KEY_AVATAR_PATH, null);
    if (path == null || path.isEmpty()) {
      return null;
    }
    // 文件可能已被清理（清缓存、用户手工删）——返回 null 让调用方回退默认头像，
    // 而不是让 Glide 加载一个不存在的路径（会留下空白）。
    return new java.io.File(path).isFile() ? path : null;
  }

  /** 设置头像路径；传 null 表示清除自定义图片（回落到内置头像）。 */
  public void setAvatarPath(String path) {
    if (path == null || path.isEmpty()) {
      prefs.edit().remove(KEY_AVATAR_PATH).apply();
      return;
    }
    prefs.edit().putString(KEY_AVATAR_PATH, path).apply();
  }

  /** 当前选中的内置头像标识；非法值回退默认。 */
  public String getAvatarBuiltin() {
    String id = prefs.getString(KEY_AVATAR_BUILTIN, AVATAR_BUILTIN_DEFAULT);
    if (id == null) {
      return AVATAR_BUILTIN_DEFAULT;
    }
    for (String candidate : AVATAR_BUILTINS) {
      if (candidate.equals(id)) {
        return id;
      }
    }
    return AVATAR_BUILTIN_DEFAULT;
  }

  /** 选择内置头像；非法值被忽略（保持当前值）。 */
  public void setAvatarBuiltin(String id) {
    if (id == null) {
      return;
    }
    for (String candidate : AVATAR_BUILTINS) {
      if (candidate.equals(id)) {
        prefs.edit().putString(KEY_AVATAR_BUILTIN, id).apply();
        return;
      }
    }
  }

  /** 全部恢复默认。 */
  public void reset() {
    prefs.edit()
        .remove(KEY_TEXT_SIZE)
        .remove(KEY_CARD_SCALE)
        .remove(KEY_TEXT_COLOR)
        .remove(KEY_AVATAR_PATH)
        .apply();
  }

  /** 字号是否被自定义过（供设置页显示摘要）。 */
  public boolean isTextSizeCustomized() {
    return Math.abs(getTextSize() - DEFAULT_TEXT_SIZE) > 0.01f;
  }

  /** 卡片大小是否被自定义过。 */
  public boolean isCardScaleCustomized() {
    return Math.abs(getCardScale() - DEFAULT_CARD_SCALE) > 0.01f;
  }

  /** 是否设了自定义正文颜色。 */
  public boolean isTextColorCustomized() {
    return getTextColor() != COLOR_FOLLOW_THEME;
  }

  private static float clampTextSize(float sp) {
    if (Float.isNaN(sp)) {
      return DEFAULT_TEXT_SIZE;
    }
    return Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, sp));
  }

  private static float clampCardScale(float scale) {
    if (Float.isNaN(scale)) {
      return DEFAULT_CARD_SCALE;
    }
    return Math.max(MIN_CARD_SCALE, Math.min(MAX_CARD_SCALE, scale));
  }

  /**
   * Material TextAppearance 的基准字号（sp），用于按用户设定值等比缩放。
   *
   * <p><b>为什么需要这张表</b>：布局里的字号全部来自 XML 的
   * {@code TextAppearance.Material3.*}，代码里没有一处 {@code setTextSize}——
   * 要做运行时缩放，只能在 {@code bind()} 里按「基准值 × 系数」重设。
   *
   * <p>基准值取自 Material3 的 typography 定义（BodyMedium 14 / BodySmall 12 /
   * LabelMedium 12 / LabelSmall 11）。**这些数字随 Material 库版本可能变化**，
   * 因此集中在这里一处维护；若将来升级 Material 后字号明显不对，改这里即可。
   */
  public static final class BaseSp {
    /** BodyMedium：消息正文（{@code item_assistant_text.xml}）。 */
    public static final float BODY_MEDIUM = 14f;

    /** BodySmall：工具卡片的输入/输出、思考正文。 */
    public static final float BODY_SMALL = 12f;

    /** LabelMedium：工具名、思考标题、撤销按钮。 */
    public static final float LABEL_MEDIUM = 12f;

    /** LabelSmall：角色标签、工具状态、语言标签。 */
    public static final float LABEL_SMALL = 11f;

    private BaseSp() {}
  }

  /**
   * 把基准字号换算成用户设定的字号。
   *
   * <p>以正文（BodyMedium）为锚点：用户设 13sp 时，正文正好 13sp，其余层级按
   * **同样的比例**缩放（13/14）。这样既尊重用户对「正文多大」的直觉，又保持
   * 各层级之间原有的视觉层次——直接给每一级都设成同一个值会让标题与正文糊在一起。
   *
   * @param baseSp 该控件的基准字号（见 {@link BaseSp}）
   * @return 缩放后的字号（sp）
   */
  public float scaleSp(float baseSp) {
    return baseSp * (getTextSize() / BaseSp.BODY_MEDIUM);
  }
}
