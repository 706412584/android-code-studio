package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link RefusalDetector} 的行为验证:强/弱短语两级判定与传输失败豁免。 */
final class RefusalDetectorTest {

  private static AgentRunResult ok(String text) {
    return new AgentRunResult(text, 0, 1, false);
  }

  @Test
  void strongSelfReferenceIsRefused() {
    RefusalDetector detector = new RefusalDetector();
    assertTrue(
        detector.detect(ok("作为 AI 语言模型,我无法为你提供这类内容。")).isRefused());
  }

  @Test
  void englishStrongRefusalIsRefused() {
    RefusalDetector detector = new RefusalDetector();
    assertTrue(detector.detect(ok("I cannot assist with that request.")).isRefused());
  }

  @Test
  void twoWeakPhrasesIsRefused() {
    RefusalDetector detector = new RefusalDetector();
    // 仅弱短语命中两条(避开全部强短语),验证「弱 >= 2 即判拒绝」
    assertTrue(detector.detect(ok("这个请求涉及敏感内容,很抱歉帮不了你。")).isRefused());
  }

  @Test
  void singleWeakPhraseInShortTextIsRefused() {
    RefusalDetector detector = new RefusalDetector();
    // 短(<300 字符)+ 单条弱短语(不命中任何强短语)→ 判拒绝
    assertTrue(detector.detect(ok("很抱歉,这次请求帮不上忙。")).isRefused());
  }

  @Test
  void singleWeakPhraseInLongTextIsAccepted() {
    RefusalDetector detector = new RefusalDetector();
    StringBuilder longText = new StringBuilder();
    longText.append("下面是一段很长的正常技术说明,其中礼貌地提到「很抱歉」但并非拒绝:");
    for (int i = 0; i < 40; i++) {
      longText.append("这是第 ").append(i).append(" 段实质内容,讲解实现细节与示例代码。");
    }
    assertFalse(detector.detect(ok(longText.toString())).isRefused());
  }

  @Test
  void ordinaryTechnicalAnswerContainingWufaIsAccepted() {
    RefusalDetector detector = new RefusalDetector();
    // 裸「无法/不能」不在短语表里,正常技术回答不受影响
    assertFalse(
        detector
            .detect(
                ok("这个方案无法在本机直接运行,因为缺少 GPU 驱动,建议改走云端推理。"))
            .isRefused());
  }

  @Test
  void transportFailureIsNotJudged() {
    RefusalDetector detector = new RefusalDetector();
    AgentRunResult failed = new AgentRunResult("模型请求失败: 429", 0, 0, true);
    assertFalse(detector.detect(failed).isRefused());
  }

  @Test
  void emptyOutputIsNotRefused() {
    RefusalDetector detector = new RefusalDetector();
    assertFalse(detector.detect(ok("  ")).isRefused());
  }

  @Test
  void disabledDetectorAlwaysPasses() {
    RefusalDetector disabled = RefusalDetector.disabled();
    assertFalse(disabled.isEnabled());
    assertFalse(disabled.detect(ok("作为 AI,我无法协助。")).isRefused());
  }
}
