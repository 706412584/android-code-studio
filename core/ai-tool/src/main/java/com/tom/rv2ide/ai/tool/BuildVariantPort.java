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

package com.tom.rv2ide.ai.tool;

import java.util.Collections;
import java.util.List;

/**
 * 构建变体端口：把 IDE 的项目变体能力（列出 / 切换）暴露给 AI 工具层。
 *
 * <p><b>为什么用中性类型</b>：本模块是纯 JVM、零 Android 依赖的，不能引用
 * {@code com.tom.rv2ide.tooling.api.models.*}（tooling 模型）。这里只定义纯数据的
 * {@link Module} / {@link Variant}，由 app 层的适配器完成转换。
 *
 * <p><b>切换的语义是「触发完整项目重新同步」</b>（不是瞬时操作）：变体选择的真实生效点在
 * tooling 端同步时（AndroidProjectModelBuilder 按 InitializeProjectParams 选变体），
 * 因此 {@link #switchTo} 会阻塞到「新变体在 workspace 中生效」或失败/超时/取消为止。
 * 实现必须保证在有界时间内返回。
 */
public interface BuildVariantPort {

  /**
   * 列出所有 Android 模块的变体信息。
   *
   * <p>返回空列表表示「没有已同步的 Android 模块」——调用方应给出「是否还在同步中」的
   * 说明，而不是当成「项目没有变体」。
   */
  List<Module> listModules();

  /** 本端口当前是否可用（项目是否已打开且工作区已就绪）。 */
  boolean isAvailable();

  /** 不可用原因；可用时返回空串。必须给出可执行说明（「去打开项目」），不能只说失败。 */
  String unavailableReason();

  /**
   * 把指定模块切换到指定变体，并等待其生效。
   *
   * <p>实现职责（按序）：校验模块与变体名 → 若已是当前变体直接返回 ALREADY_SELECTED →
   * 组装**全量**选择（缺失模块会被 tooling 端回退到第一个变体，必须带上所有模块的当前选择）
   * → 触发项目重新同步 → 有界等待新变体在 workspace 中生效。
   *
   * @param modulePath 模块路径，如 {@code ":app"}
   * @param variantName 目标变体名，如 {@code "release"}
   * @param timeoutMs 等待上限（毫秒）；实现必须在有界时间内返回
   * @param cancel 取消信号（用户点「停止」）；可为 null
   * @return 见 {@link Result}
   */
  Result switchTo(
      String modulePath, String variantName, long timeoutMs, ToolContext.CancellationSignal cancel);

  /** 一个 Android 模块及其变体。 */
  final class Module {
    private final String projectPath;   // ":app"；空串表示未知
    private final String name;          // "app"
    private final boolean application;  // true=application，false=library
    private final String namespace;     // 可空，空串表示无
    private final List<Variant> variants;
    private final String selectedVariant; // 当前选中名；空串表示未知/未同步

    public Module(
        String projectPath,
        String name,
        boolean application,
        String namespace,
        List<Variant> variants,
        String selectedVariant) {
      this.projectPath = projectPath == null ? "" : projectPath;
      this.name = name == null ? "" : name;
      this.application = application;
      this.namespace = namespace == null ? "" : namespace;
      this.variants =
          variants == null ? Collections.<Variant>emptyList() : Collections.unmodifiableList(variants);
      this.selectedVariant = selectedVariant == null ? "" : selectedVariant;
    }

    public String getProjectPath() {
      return projectPath;
    }

    public String getName() {
      return name;
    }

    public boolean isApplication() {
      return application;
    }

    public String getNamespace() {
      return namespace;
    }

    public List<Variant> getVariants() {
      return variants;
    }

    public String getSelectedVariant() {
      return selectedVariant;
    }
  }

  /** 一个构建变体。 */
  final class Variant {
    private final String name;              // "release"
    private final String assembleTaskName;  // ":app:assembleRelease"；空串表示未知
    private final String applicationId;     // 可空（library 无），空串表示无

    public Variant(String name, String assembleTaskName, String applicationId) {
      this.name = name == null ? "" : name;
      this.assembleTaskName = assembleTaskName == null ? "" : assembleTaskName;
      this.applicationId = applicationId == null ? "" : applicationId;
    }

    public String getName() {
      return name;
    }

    public String getAssembleTaskName() {
      return assembleTaskName;
    }

    public String getApplicationId() {
      return applicationId;
    }
  }

  /**
   * 一次切换的结果。
   *
   * <p>五态互斥，绝不用「成功」表达「未生效」——切换是长操作，失败与超时必须可区分，
   * 否则模型会把「还在同步」误读成「已经切好了」。
   */
  final class Result {
    public enum Status {
      /** 已切换且已生效。 */
      SWITCHED,
      /** 目标变体已是当前选择，未触发同步。 */
      ALREADY_SELECTED,
      /** 失败（校验不通过 / 同步失败 / 无法启动同步）。reason 给出可执行的下一步。 */
      FAILED,
      /** 等待超时；同步可能仍在后台进行。 */
      TIMED_OUT,
      /** 用户停止；已触发的同步不会因此中止（tooling 端无取消入口）。 */
      CANCELLED
    }

    private final Status status;
    private final String modulePath;
    private final String variantName;
    private final String assembleTaskName;
    private final String reason;

    private Result(
        Status status, String modulePath, String variantName, String assembleTaskName, String reason) {
      this.status = status;
      this.modulePath = modulePath == null ? "" : modulePath;
      this.variantName = variantName == null ? "" : variantName;
      this.assembleTaskName = assembleTaskName == null ? "" : assembleTaskName;
      this.reason = reason == null ? "" : reason;
    }

    /** 切换成功；assembleTaskName 尽力给出（取不到时空串）。 */
    public static Result switched(String modulePath, String variantName, String assembleTaskName) {
      return new Result(Status.SWITCHED, modulePath, variantName, assembleTaskName, "");
    }

    public static Result alreadySelected(String modulePath, String variantName) {
      return new Result(Status.ALREADY_SELECTED, modulePath, variantName, "", "");
    }

    /** 失败；reason 必须是可执行的说明（含可用变体名单、下一步动作等）。 */
    public static Result failed(String reason) {
      return new Result(Status.FAILED, "", "", "", reason);
    }

    public static Result timedOut(String modulePath, String variantName, String reason) {
      return new Result(Status.TIMED_OUT, modulePath, variantName, "", reason);
    }

    public static Result cancelled(String modulePath, String variantName, String reason) {
      return new Result(Status.CANCELLED, modulePath, variantName, "", reason);
    }

    public Status getStatus() {
      return status;
    }

    public String getModulePath() {
      return modulePath;
    }

    public String getVariantName() {
      return variantName;
    }

    public String getAssembleTaskName() {
      return assembleTaskName;
    }

    public String getReason() {
      return reason;
    }

    public boolean isSwitched() {
      return status == Status.SWITCHED;
    }

    public boolean isAlreadySelected() {
      return status == Status.ALREADY_SELECTED;
    }

    public boolean isFailed() {
      return status == Status.FAILED;
    }

    public boolean isTimedOut() {
      return status == Status.TIMED_OUT;
    }

    public boolean isCancelled() {
      return status == Status.CANCELLED;
    }
  }
}
