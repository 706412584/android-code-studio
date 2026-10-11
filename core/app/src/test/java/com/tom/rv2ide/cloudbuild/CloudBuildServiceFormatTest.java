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

package com.tom.rv2ide.cloudbuild;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * {@link CloudBuildService#formatLatestRun} 的回归测试。
 *
 * <p>这个文案是用户与 AI 看到的状态查询结果本身（AI 工具原样回灌给模型）。
 * 与网络调用分离成纯函数后可在 JVM 上直接钉住：状态必须翻译成中文（模型据此
 * 判断「还要不要等」）、job 列表只在已加载时输出（避免空段）、链接必须带上
 * （用户/模型点开看详情）。
 */
public final class CloudBuildServiceFormatTest {

  @Test
  public void emptyRunHistoryExplainsInsteadOfReturningBlank() {
    String text = CloudBuildService.formatLatestRun("o", "r", null, null);
    assertTrue(text, text.contains("o/r"));
    assertTrue(text, text.contains("没有运行记录"));
  }

  @Test
  public void finishedRunSummaryContainsStatusJobsAndLink() {
    WorkflowRun run = new WorkflowRun(
        42L, "Build APK", "dev", "abc123", "completed", "success",
        "https://github.com/o/r/actions/runs/42", "2026-10-12T00:00:00Z",
        "2026-10-12T00:05:00Z");
    WorkflowJob job = new WorkflowJob(1L, "assembleDebug", "completed", "success", "");

    String text = CloudBuildService.formatLatestRun("o", "r", run, Collections.singletonList(job));
    assertTrue(text, text.contains("Build APK"));
    assertTrue(text, text.contains("成功"));
    assertTrue(text, text.contains("dev"));
    assertTrue(text, text.contains("assembleDebug"));
    assertTrue(text, text.contains("https://github.com/o/r/actions/runs/42"));
  }

  @Test
  public void failedRunSummaryTranslatesConclusionToChinese() {
    WorkflowRun run = new WorkflowRun(
        43L, "Build APK", "main", "def456", "completed", "failure",
        "https://github.com/o/r/actions/runs/43", "", "");
    WorkflowJob job = new WorkflowJob(2L, "assembleRelease", "completed", "failure", "");

    String text = CloudBuildService.formatLatestRun("o", "r", run, Collections.singletonList(job));
    assertTrue(text, text.contains("失败"));
    assertTrue(text, text.contains("assembleRelease"));
  }

  @Test
  public void runningRunSummaryOmitsJobSectionWhenJobsNotLoaded() {
    // 未到终态时调用方传 jobs=null（不额外发请求）；格式化不应输出空的 Jobs 段。
    WorkflowRun run = new WorkflowRun(
        44L, "Build APK", "dev", "aaa", "in_progress", "",
        "https://github.com/o/r/actions/runs/44", "", "");

    String text = CloudBuildService.formatLatestRun("o", "r", run, null);
    assertTrue(text, text.contains("运行中"));
    assertFalse(text, text.contains("Jobs"));
  }

  @Test
  public void unnamedRunFallsBackToPlaceholder() {
    WorkflowRun run = new WorkflowRun(
        45L, "", "dev", "bbb", "completed", "success", "url", "", "");
    String text = CloudBuildService.formatLatestRun("o", "r", run, Arrays.asList());
    assertTrue(text, text.contains("(未命名)"));
  }
}
