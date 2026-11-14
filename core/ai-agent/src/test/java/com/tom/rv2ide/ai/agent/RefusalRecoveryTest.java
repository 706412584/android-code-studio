package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link RefusalRecovery} 的策略阶梯验证:脚本化 {@link RefusalRecovery.AttemptRunner}
 * 模拟「首跑被拒 → 换策略放行 / 仍拒绝」的序列,不联网。
 */
final class RefusalRecoveryTest {

  private static final ModelConfig MAIN =
      ModelConfig.builder(
              "main", "main", ModelProtocolType.OPENAI_COMPATIBLE,
              "provider", "http://localhost", "k", "main-model")
          .build();

  private static final ModelConfig FALLBACK =
      ModelConfig.builder(
              "backup", "backup", ModelProtocolType.OPENAI_COMPATIBLE,
              "provider", "http://localhost", "k", "backup-model")
          .build();

  /** 记录每档的 (config, reinforced),按脚本返回预设结果。 */
  private static final class ScriptedRunner implements RefusalRecovery.AttemptRunner {
    final List<ModelConfig> configs = new ArrayList<ModelConfig>();
    final List<Boolean> reinforced = new ArrayList<Boolean>();
    private final List<String> outcomes;
    private int call = 0;

    ScriptedRunner(List<String> outcomes) {
      this.outcomes = outcomes;
    }

    @Override
    public AgentRunResult run(ModelConfig config, boolean isReinforced, AgentEvent.Listener listener) {
      configs.add(config);
      reinforced.add(isReinforced);
      String outcome = outcomes.get(Math.min(call, outcomes.size() - 1));
      call++;
      return new AgentRunResult(outcome, 0, 1, false);
    }
  }

  private static RefusalRecovery.Policy policy(int maxAttempts) {
    RefusalRecovery.Policy p = new RefusalRecovery.Policy();
    p.maxAttempts = maxAttempts;
    return p;
  }

  @Test
  void firstAttemptRefusedThenRecovered() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("作为 AI 我无法协助。", "好的,下面给出完整步骤......"));
    RefusalRecovery recovery =
        new RefusalRecovery(new RefusalDetector(), runner, policy(3));

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals("好的,下面给出完整步骤......", result.getOutput());
    assertEquals(2, runner.configs.size(), "第二档仍用主配置");
    assertEquals(MAIN, runner.configs.get(0));
    assertEquals(MAIN, runner.configs.get(1));
    assertFalse(runner.reinforced.get(0), "首跑不强化");
    assertTrue(runner.reinforced.get(1), "重试档强化执行约束");
  }

  @Test
  void fallbackConfigUsedOnThirdAttempt() {
    ScriptedRunner runner =
        new ScriptedRunner(
            Arrays.asList("我无法提供。", "很抱歉帮不了。", "已完成,步骤如下......"));
    RefusalRecovery.Policy p = policy(3);
    p.fallbackConfig = () -> FALLBACK;
    RefusalRecovery recovery = new RefusalRecovery(new RefusalDetector(), runner, p);

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals("已完成,步骤如下......", result.getOutput());
    assertEquals(3, runner.configs.size());
    assertEquals(MAIN, runner.configs.get(0));
    assertEquals(MAIN, runner.configs.get(1), "第二档不换模型");
    assertEquals(FALLBACK, runner.configs.get(2), "第三档切换备用模型");
    assertTrue(runner.reinforced.get(2), "备用模型档同样强化");
  }

  @Test
  void exhaustionReturnsLastResult() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("我无法提供。", "I cannot assist.", "抱歉帮不了。"));
    RefusalRecovery recovery =
        new RefusalRecovery(new RefusalDetector(), runner, policy(3));

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals(3, runner.configs.size(), "跑满预算上限");
    assertEquals("抱歉帮不了。", result.getOutput(), "预算耗尽后如实返回最后一次结果");
  }

  @Test
  void nonRefusalStopsImmediately() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("好的,直接给结论:......"));
    RefusalRecovery recovery =
        new RefusalRecovery(new RefusalDetector(), runner, policy(3));

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals(1, runner.configs.size(), "非拒绝只跑一次");
    assertEquals("好的,直接给结论:......", result.getOutput());
  }

  @Test
  void listenerReceivesProgressAnnouncements() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("我无法提供。", "好的......"));
    final List<AgentEvent> events = new ArrayList<AgentEvent>();
    RefusalRecovery recovery =
        new RefusalRecovery(new RefusalDetector(), runner, policy(3));

    AgentRunResult result =
        recovery.execute(
            MAIN,
            new AgentEvent.Listener() {
              @Override
              public void onEvent(AgentEvent event) {
                events.add(event);
              }
            });

    assertEquals("好的......", result.getOutput());
    int progressCount = 0;
    for (AgentEvent event : events) {
      if (event.getType() == AgentEvent.Type.PROGRESS) {
        progressCount++;
      }
    }
    assertEquals(1, progressCount, "重试前播报一次进度(成功后不再播报)");
  }

  @Test
  void transportFailureIsNotJudgedAsRefusal() {
    // 传输失败(isFailed=true)走既有失败路径:detect 层直接豁免,不进重试循环
    RefusalDetector detector = new RefusalDetector();
    AgentRunResult failed = new AgentRunResult("模型请求失败: 429", 0, 0, true);
    assertFalse(detector.detect(failed).isRefused(), "传输失败不判为拒绝");
  }

  @Test
  void maxAttemptsOneMeansNoRetries() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("我无法提供。", "好的......"));
    RefusalRecovery recovery =
        new RefusalRecovery(new RefusalDetector(), runner, policy(1));

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals(1, runner.configs.size(), "预算 1 = 只首跑");
    assertEquals("我无法提供。", result.getOutput());
  }

  @Test
  void disabledRecoveryRunsOnce() {
    ScriptedRunner runner =
        new ScriptedRunner(Arrays.asList("作为 AI 我无法协助。"));
    RefusalRecovery recovery = RefusalRecovery.disabled(runner);

    AgentRunResult result = recovery.execute(MAIN, null);

    assertEquals(1, runner.configs.size());
    assertEquals("作为 AI 我无法协助。", result.getOutput(), "关闭时首跑即返回");
  }
}
