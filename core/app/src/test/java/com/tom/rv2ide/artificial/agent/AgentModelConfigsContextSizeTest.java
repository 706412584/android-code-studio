/*
 * This file is part of AndroidCodeStudio.
 *
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

import static org.junit.Assert.assertEquals;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import org.junit.Test;

/**
 * 锁定「勾选 1M → 运行时压缩窗口真的变成 1000000」这条链路。
 *
 * <p>这是服务商表单 1M 勾选框的下游：勾选把 {@code [1m]} 写进模型名，但后缀是本地元数据，
 * {@link ProviderConfig#apiModelId} 会把它剥掉再发给 API。若组装 {@link ModelConfig} 时
 * 不把声明的窗口翻译成 {@code contextSize}，运行时就退回默认窗口——勾选框沦为装饰。
 *
 * <p>用 {@link AgentModelConfigs#applyContextSize} 而非 {@code build(...)} 做断言：
 * 后者要经过 {@code recordFor}（依赖 Android Context），JVM 单测跑不起来；
 * 前者是同一段逻辑、可纯 JVM 验证。
 */
public class AgentModelConfigsContextSizeTest {

  private static ProviderConfig config(String... slots) {
    return new ProviderConfig(
        "test",
        "Test",
        ModelProtocolType.OPENAI_COMPATIBLE,
        "https://api.example.com/v1",
        "sk-1",
        slots);
  }

  private static ModelConfig.Builder builder() {
    return ModelConfig.builder(
        "test", "Test", ModelProtocolType.OPENAI_COMPATIBLE, "Test", "https://a/v1", "k", "m");
  }

  @Test
  public void declared1mReachesModelConfigContextSize() {
    // 用户在表单里勾了 1M → 模型名带 [1m] → contextSize 必须为 1000000。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("deepseek-v4.1-flash[1m]"), ProviderConfig.SLOT_MAIN);
    assertEquals(1000000, b.build().getContextSize());
  }

  @Test
  public void declared200kReachesModelConfigContextSize() {
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(b, config("glm-5.2[200k]"), ProviderConfig.SLOT_MAIN);
    assertEquals(200000, b.build().getContextSize());
  }

  @Test
  public void noSuffixLeavesContextSizeUnset() {
    // 未声明窗口时保持不设，走协议层的默认值，而不是被写成 0 之外的东西。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(b, config("deepseek-chat"), ProviderConfig.SLOT_MAIN);
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, b.build().getContextSize());
  }

  @Test
  public void nullRecordLeavesContextSizeUnset() {
    // 预设路径没有用户记录；不能因此抛异常，也不能写入任何值。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(b, null, ProviderConfig.SLOT_MAIN);
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, b.build().getContextSize());
  }

  @Test
  public void emptySlotFallsBackToMainDeclaration() {
    // 空槽位语义是「与主模型相同」，上下文声明也应回退到主模型。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("main[1m]", "", "", ""), ProviderConfig.SLOT_OPUS);
    assertEquals(1000000, b.build().getContextSize());
  }

  @Test
  public void explicitSlotDeclarationWins() {
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("main[1m]", "fast[200k]", "", ""), ProviderConfig.SLOT_HAIKU);
    assertEquals(200000, b.build().getContextSize());
  }

  @Test
  public void nullSlotTreatedAsMain() {
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(b, config("main[1m]"), null);
    assertEquals(1000000, b.build().getContextSize());
  }

  // ---- 声明与生效模型的匹配 ----

  @Test
  public void declarationMatchingStrippedModelStillApplies() {
    // 选择器显示的是去后缀的名字，用户点它拿到的就是 "glm-5.2"。
    // 声明属于 "glm-5.2[1m]"，去后缀后与生效模型同名 → 窗口必须生效，
    // 否则「切回同一个模型反而丢了 1M」。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("glm-5.2[1m]"), ProviderConfig.SLOT_MAIN, "glm-5.2");
    assertEquals(1000000, b.build().getContextSize());
  }

  @Test
  public void declarationOfAnotherModelIsNotApplied() {
    // 主模型声明 1M，但本次要发的是别的模型 → 不能把 1M 套上去。
    // 套上去的后果是压缩阈值按 1M 算，直到服务端报「上下文超限」才失败。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("glm-5.2[1m]"), ProviderConfig.SLOT_MAIN, "deepseek-v4-flash");
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, b.build().getContextSize());
  }

  @Test
  public void blankEffectiveModelSkipsMatching() {
    // 调用方给不出生效模型（旧调用点）时不做匹配，行为与从前一致。
    ModelConfig.Builder b = builder();
    AgentModelConfigs.applyContextSize(
        b, config("glm-5.2[1m]"), ProviderConfig.SLOT_MAIN, "  ");
    assertEquals(1000000, b.build().getContextSize());
  }
}
