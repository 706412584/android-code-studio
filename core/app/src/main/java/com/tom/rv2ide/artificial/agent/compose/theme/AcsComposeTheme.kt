/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
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

package com.tom.rv2ide.artificial.agent.compose.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 间距刻度。
 *
 * 组件只从这里取值，不写字面量 dp —— 否则后续想统一收紧/放宽密度时，
 * 得逐个组件去翻，且很容易漏掉某几处导致节奏不齐。
 */
object Spacing {
  val xs = 4.dp
  val sm = 8.dp
  val md = 12.dp
  val lg = 16.dp
  val xl = 24.dp
  val xxl = 32.dp
}

/** 圆角刻度，理由同 [Spacing]。[pill] 用于胶囊形按钮/标签，取足够大的值等价于半圆。 */
object Radius {
  val xs = 4.dp
  val sm = 8.dp
  val md = 10.dp
  val mdLarge = 12.dp
  val lg = 14.dp
  val xl = 16.dp
  val pill = 999.dp
}

/** 品牌色。不参与明暗推导，用于需要固定识别度的位置（品牌标识、链接等）。 */
object Brand {
  val Blue = Color(0xFF2563EB)
  val Sky = Color(0xFF38BDF8)
  val IconGray = Color(0xFF64748B)
  val Orange = Color(0xFFF57C00)
}

/** 全局统一语义色彩，解决业务代码私自 hardcode 颜色问题。 */
data class AppSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val diffAdd: Color,
    val diffAddBg: Color,
    val diffRemove: Color,
    val diffRemoveBg: Color,
    val subtleText: Color,
    val subtleBorder: Color,
    val cardSurface: Color,
    val pageBackground: Color,
    val mutedSurface: Color,
    val capsuleSurface: Color,
    val buttonMutedBg: Color
)

val LightSemanticColors = AppSemanticColors(
    success = Color(0xFF22C55E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF15803D),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF92400E),
    info = Color(0xFF0284C7),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF075985),
    diffAdd = Color(0xFF22C55E),
    diffAddBg = Color(0x2622C55E),
    diffRemove = Color(0xFFEF4444),
    diffRemoveBg = Color(0x26EF4444),
    subtleText = Color(0xFF8E8E93),
    subtleBorder = Color(0xFFE5E5EA),
    cardSurface = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFF8F8F8),
    mutedSurface = Color(0xFFF2F2F7),
    capsuleSurface = Color(0xFFE9E9EB),
    buttonMutedBg = Color(0xFFF0F2F5)
)

val DarkSemanticColors = AppSemanticColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0x59052E16),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF78350F),
    warningContainer = Color(0x5978350F),
    onWarningContainer = Color(0xFFFDE68A),
    info = Color(0xFF38BDF8),
    onInfo = Color(0xFF0C4A6E),
    infoContainer = Color(0x590C4A6E),
    onInfoContainer = Color(0xFFBAE6FD),
    diffAdd = Color(0xFF4ADE80),
    diffAddBg = Color(0x334ADE80),
    diffRemove = Color(0xFFF87171),
    diffRemoveBg = Color(0x33F87171),
    subtleText = Color(0xFF94A3B8),
    subtleBorder = Color(0xFF2A3F56),
    cardSurface = Color(0xFF0D1B2E),
    pageBackground = Color(0xFF07111F),
    mutedSurface = Color(0xFF13273F),
    capsuleSurface = Color(0xFF1E293B),
    buttonMutedBg = Color(0xFF1E293B)
)

/**
 * 用 static 而非普通 compositionLocal：主题色在一次组合里不会变，
 * 变化时整棵子树重组本来就是期望行为，静态版本可以省掉逐读取点的订阅开销。
 */
val LocalAppSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * 语义色入口。
 *
 * 挂成 [MaterialTheme] 的扩展属性而不是独立顶层对象，是为了让调用点统一写作
 * `MaterialTheme.semanticColors.success`，与 `MaterialTheme.colorScheme.primary` 并列，
 * 使用者不需要额外记一个入口名。
 */
val MaterialTheme.semanticColors: AppSemanticColors
  @Composable
  @ReadOnlyComposable
  get() = LocalAppSemanticColors.current

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0F3A63),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = Color(0xFF22C55E),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFBBF7D0),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0D1B2E),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF13273F),
    onSurfaceVariant = Color(0xFFB8C7DA),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF050C17),
    surfaceContainerLow = Color(0xFF07111F),
    surfaceContainer = Color(0xFF0D1B2E),
    surfaceContainerHigh = Color(0xFF13273F),
    surfaceContainerHighest = Color(0xFF1B3350),
    surfaceBright = Color(0xFF24405F),
    surfaceDim = Color(0xFF07111F),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA)
)

internal val LightColorScheme = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B3B76),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFF16A34A),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF15803D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF4FF),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFF),
    surfaceContainer = Color(0xFFF4F9FF),
    surfaceContainerHigh = Color(0xFFEAF4FF),
    surfaceContainerHighest = Color(0xFFE0EDFA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8EEF6),
    outline = Color(0xFFD1D1D6),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

/**
 * 收敛字重与行高：默认 Typography 的中文行高偏紧，消息正文读起来发挤；
 * 标题统一 SemiBold、字距归零，避免各组件自己调字号字重导致层级不一致。
 */
private val AppTypography = Typography().run {
  copy(
      headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
      bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
      bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
      labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
  )
}

/**
 * AI 助手渲染层的 Compose 主题包装。
 *
 * 消息渲染组件全部经 [MaterialTheme.semanticColors] 取色，而它读的是
 * [LocalAppSemanticColors]，因此这一层必须包在渲染树最外层——只包局部会让
 * 未覆盖到的子树静默回落到浅色语义色，在深色模式下表现为局部白底。
 *
 * 函数名沿用 Aharou 的 `AIEditorTheme`（未改为 Acs 前缀），
 * 这样后续从 Aharou 移植渲染组件时调用点可以原样搬过来。
 *
 * @param darkTheme 是否使用深色配色，默认跟随系统。
 * @param dynamicColor 是否启用系统莫奈取色，低于 Android 12 自动回退到内置配色。
 *   注意：语义色不跟随莫奈推导（Aharou 的推导器未移植），开启后 Material 槽位随壁纸变、
 *   语义色仍是内置的那套，两者会有轻微色温差异。默认关闭。
 */
@Composable
fun AIEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    /**
     * 字号缩放倍率（1.0 = 默认）。来自 AI 设置的「聊天字号」——
     * 用户选 13sp（默认值）时为 1.0，选 20sp 时为 20/13 ≈ 1.54。
     *
     * <p>为什么在这里统一缩放而不是逐组件改字号：渲染层的字号全部取自
     * [MaterialTheme.typography]，在主题这一层按倍率重算一次即可覆盖所有组件
     * （正文/工具卡/思考块/小字），与 XML 路径 `AssistantMessageAdapter.scaledSp`
     * 的「基准值 × 用户缩放」语义一致。逐组件改会漏，且新增组件时容易忘。
     */
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
  val context = LocalContext.current
  val colorScheme = remember(darkTheme, dynamicColor, context) {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val system = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      // M3 组件默认按 surfaceTint 做高度染色，会与自有配色叠加出预期外的色调，这里关掉
      system.copy(surfaceTint = if (darkTheme) Color.Transparent else system.surface)
    } else if (darkTheme) {
      DarkColorScheme
    } else {
      LightColorScheme
    }
  }

  // 用 ACS 主题的实际颜色覆盖 Compose 色板。
  //
  // <p><b>为什么必须覆盖</b>：内置的 LightColorScheme/DarkColorScheme 是**硬编码**的
  // （浅色的 background/surface 都是纯白 #FFFFFF），完全不跟随用户在 ACS 里选的主题。
  // 后果是「顶栏和输入框一块死白，与侧栏/编辑器色调不统一，看起来突兀」——
  // 用户实测反馈过。而 XML 侧的控件读的是 ?attr/colorSurface 等主题属性，
  // 两者因此对不上。
  //
  // <p>只覆盖消息区实际用到的槽位：面板底色（surface 系）、文字色（onSurface 系）、
  // 主色与描边。不整套搬运——M3 有几十个槽位，逐个搬容易搬错且无收益，
  // 而未被覆盖的槽位会继续用内置值（与覆盖值同色系，不会突兀）。
  val hostColors = remember(context) { resolveHostColors(context) }
  val finalScheme = remember(colorScheme, hostColors) { colorScheme.overlayHost(hostColors) }

  // 缩放后 typography：倍率不变时复用同一实例，避免每次重组都重建 Typography。
  val typography =
      remember(textScale) {
        if (textScale == 1f) AppTypography else AppTypography.scaledBy(textScale)
      }

  CompositionLocalProvider(
      LocalAppSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors
  ) {
    MaterialTheme(colorScheme = finalScheme, typography = typography, content = content)
  }
}

/** 从 ACS 主题解析出的颜色。null 表示该属性在主题里没定义（保留 Compose 内置值）。 */
private class HostColors(
    val surface: Color?,
    val surfaceContainerLow: Color?,
    val surfaceContainer: Color?,
    val surfaceContainerHigh: Color?,
    val surfaceContainerHighest: Color?,
    val onSurface: Color?,
    val onSurfaceVariant: Color?,
    val primary: Color?,
    val outline: Color?,
    val outlineVariant: Color?,
    val background: Color?,
    val onBackground: Color?,
)

/**
 * 解析宿主（ACS）主题里的颜色属性。
 *
 * <p>逐个 `resolveAttribute` 而不是只取一两个：消息区用到的槽位分散在
 * 气泡底（surfaceContainerLow）、输入栏（surface）、工具卡（surfaceContainer）等处，
 * 少搬一个那处就还是死白。
 *
 * <p>取不到（主题没定义该 attr）时留 null，调用方保留 Compose 内置值——
 * 比塞一个猜的颜色安全：宁可某处不跟随，也不要把文字画成与底色同色。
 */
private fun resolveHostColors(context: android.content.Context): HostColors {
  val typed = android.util.TypedValue()
  fun attr(id: Int): Color? {
    val resolved = context.theme.resolveAttribute(id, typed, true)
    if (!resolved) {
      return null
    }
    return if (typed.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
        typed.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
      Color(typed.data)
    } else {
      // 解出来是资源引用（@color/xxx）而不是字面值：再解析一层。
      runCatching { Color(androidx.core.content.ContextCompat.getColor(context, typed.resourceId)) }
          .getOrNull()
    }
  }
  return HostColors(
      surface = attr(com.tom.rv2ide.R.attr.colorSurface),
      surfaceContainerLow = attr(com.tom.rv2ide.R.attr.colorSurfaceContainerLow),
      surfaceContainer = attr(com.tom.rv2ide.R.attr.colorSurfaceContainer),
      surfaceContainerHigh = attr(com.tom.rv2ide.R.attr.colorSurfaceContainerHigh),
      surfaceContainerHighest =
          attr(com.tom.rv2ide.R.attr.colorSurfaceContainerHighest),
      onSurface = attr(com.tom.rv2ide.R.attr.colorOnSurface),
      onSurfaceVariant = attr(com.tom.rv2ide.R.attr.colorOnSurfaceVariant),
      primary = attr(com.tom.rv2ide.R.attr.colorPrimary),
      outline = attr(com.tom.rv2ide.R.attr.colorOutline),
      outlineVariant = attr(com.tom.rv2ide.R.attr.colorOutlineVariant),
      // 顶栏/输入栏用 colorSurfaceDim，与**侧栏背景同色**
      // （activity_editor.xml:9 的 android:background="?attr/colorSurfaceDim"）。
      //
      // 不用 colorBackground：它在多数浅色主题下是纯白（sunny_glow #FFFBFF、
      // vscode #FFFFFF），而侧栏是 SurfaceDim（sunny_glow #E6E2E6）——
      // 于是「顶栏/输入栏一块死白、侧栏偏灰」，正是用户反馈的突兀感。
      // 同一个界面里的「页面框架」应当同色，这是层次设计不是巧合。
      background = attr(com.tom.rv2ide.R.attr.colorSurfaceDim),
      onBackground = attr(com.tom.rv2ide.R.attr.colorOnBackground),
  )
}

/** 把宿主颜色盖到 Compose 色板上；未取到的槽位保留原值。 */
private fun ColorScheme.overlayHost(host: HostColors): ColorScheme =
    copy(
        surface = host.surface ?: surface,
        surfaceContainerLow = host.surfaceContainerLow ?: surfaceContainerLow,
        surfaceContainer = host.surfaceContainer ?: surfaceContainer,
        surfaceContainerHigh = host.surfaceContainerHigh ?: surfaceContainerHigh,
        surfaceContainerHighest = host.surfaceContainerHighest ?: surfaceContainerHighest,
        onSurface = host.onSurface ?: onSurface,
        onSurfaceVariant = host.onSurfaceVariant ?: onSurfaceVariant,
        primary = host.primary ?: primary,
        outline = host.outline ?: outline,
        outlineVariant = host.outlineVariant ?: outlineVariant,
        // background 跟随 ACS 的 android:colorBackground（顶栏与输入栏用的就是它）。
        background = host.background ?: background,
        onBackground = host.onBackground ?: onBackground,
    )

/**
 * 按倍率缩放整套 typography。
 *
 * <p>只缩放消息渲染实际用到的槽位（body/label 系）——标题类槽位属于面板框架，
 * 跟随「聊天字号」一起变会让顶栏与设置项的字号失控（用户调的是**消息**字号）。
 * lineHeight 同步缩放，否则字号变大而行高不变，中文正文会挤在一起。
 */
private fun Typography.scaledBy(scale: Float): Typography =
    copy(
        bodyLarge = bodyLarge.scaledText(scale),
        bodyMedium = bodyMedium.scaledText(scale),
        bodySmall = bodySmall.scaledText(scale),
        labelLarge = labelLarge.scaledText(scale),
        labelMedium = labelMedium.scaledText(scale),
        labelSmall = labelSmall.scaledText(scale),
    )

/** 缩放单个 TextStyle 的字号与行高（行高为 Unspecified 时保持不变）。 */
private fun androidx.compose.ui.text.TextStyle.scaledText(
    scale: Float
): androidx.compose.ui.text.TextStyle {
  // isSpecified 是 TextUnit 的扩展属性（androidx.compose.ui.unit），
  // 行高为 Unspecified 时不能参与乘法（会得到非法值）。
  val newLineHeight =
      if (lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight * scale
      else lineHeight
  return copy(fontSize = fontSize * scale, lineHeight = newLineHeight)
}
