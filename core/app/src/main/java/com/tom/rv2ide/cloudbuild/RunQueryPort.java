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

package com.tom.rv2ide.cloudbuild;

import java.util.List;

/**
 * 轮询器所需的只读查询面。
 *
 * <p><b>为什么抽出接口</b>：{@link WorkflowRunPoller} 的核心行为是「瞬时网络错误
 * 不中断轮询」——这条逻辑只能在注入会抛错的假实现时才测得到（真实客户端是 final
 * 具体类，无法替换其 HTTP 行为）。项目惯例即此（参见 {@code GradleBuildTool} 的
 * {@code BuildServiceProvider} 注入）。
 *
 * <p>接口刻意只声明轮询真正用到的三个方法，不搬运整个客户端——窄接口才好实现、
 * 好理解。
 */
public interface RunQueryPort {

  /** 查最新 run（可按 workflow / 分支过滤）。 */
  WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception;

  /** 按 id 查 run。 */
  WorkflowRun getRun(long runId) throws Exception;

  /** 查 run 的 job 列表。 */
  List<WorkflowJob> listJobs(long runId) throws Exception;
}
