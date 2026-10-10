/*
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * AI 运行完成的状态栏通知。
 *
 * <p>语义照 cc-haha 的 {@code useNotifyAfterTimeout}：任务结束且用户**短时间内无交互**
 * 才提醒——用户正在看结果时弹通知纯属打扰。ACS 侧的「无交互」用两个条件复合判断：
 *
 * <ul>
 *   <li>助手面板**当前可见**（编辑器侧栏展开/悬浮展开）→ 不通知：用户就在屏幕前；
 *   <li>用户 6 秒内在面板上有触摸 → 不通知。
 * </ul>
 *
 * <p>targetSdk 28（O 以下渠道模型），无需运行时通知权限。
 */
public final class RunCompletionNotifier {

  /** 用户交互后多久内算「还在看」，期间不发通知。 */
  private static final long INTERACTION_GRACE_MS = 6_000;

  private static final String CHANNEL_ID = "ai_run_completion";
  private static final int NOTIFICATION_ID = 4201;

  private RunCompletionNotifier() {}

  /** 面板收到任何触摸时调用（Main 线程）。 */
  public static void noteInteraction() {
    lastInteractionMs = android.os.SystemClock.elapsedRealtime();
  }

  private static volatile long lastInteractionMs;

  /**
   * 尝试发完成通知。
   *
   * @param hostVisible 助手面板当前是否可见（悬浮展开或侧栏展开）
   * @param success 运行是否成功结束
   * @param durationMs 本轮耗时（0 表示未知，文案里省略）
   * @param conversationId 用于点击通知回到对应会话
   */
  public static void maybeNotify(
      Context context,
      boolean hostVisible,
      boolean success,
      long durationMs,
      String conversationId) {
    if (hostVisible) {
      return;
    }
    long since = android.os.SystemClock.elapsedRealtime() - lastInteractionMs;
    if (lastInteractionMs != 0 && since < INTERACTION_GRACE_MS) {
      return;
    }

    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(
              CHANNEL_ID,
              context.getString(com.tom.rv2ide.resources.R.string.ai_agent_notify_channel),
              NotificationManager.IMPORTANCE_DEFAULT);
      channel.setDescription(
          context.getString(com.tom.rv2ide.resources.R.string.ai_agent_notify_channel_desc));
      manager.createNotificationChannel(channel);
    }

    String title =
        context.getString(
            success
                ? com.tom.rv2ide.resources.R.string.ai_agent_notify_done_title
                : com.tom.rv2ide.resources.R.string.ai_agent_notify_failed_title);
    String text =
        durationMs > 0
            ? context.getString(
                com.tom.rv2ide.resources.R.string.ai_agent_notify_text_with_duration,
                formatDuration(durationMs))
            : context.getString(com.tom.rv2ide.resources.R.string.ai_agent_notify_text);

    // 点击回到主界面（编辑器），面板恢复逻辑交给宿主既有路径。
    Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if (intent == null) {
      return;
    }
    intent.putExtra("ai_conversation_id", conversationId);
    PendingIntent contentIntent =
        PendingIntent.getActivity(
            context,
            conversationId == null ? 0 : conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    android.app.Notification notification =
        new android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build();
    manager.notify(NOTIFICATION_ID, notification);
  }

  private static String formatDuration(long ms) {
    long seconds = ms / 1000;
    if (seconds < 60) {
      return seconds + "s";
    }
    return (seconds / 60) + "m" + (seconds % 60) + "s";
  }
}
