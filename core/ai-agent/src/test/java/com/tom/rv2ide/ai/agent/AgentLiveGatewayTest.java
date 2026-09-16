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

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * 对真实 OpenAI 兼容网关的端到端验证。
 *
 * <p><b>为什么需要它</b>：其余测试都用脚本化的假协议，验证的是循环的控制流。
 * 但「工具定义能否被真实模型理解并正确调用」只能在真实端点上验证——
 * 提示词格式、tools 字段结构、tool_calls 响应解析任何一处不匹配，
 * 假协议都发现不了。
 *
 * <p><b>运行方式</b>（默认跳过，避免 CI 联网与泄漏密钥）：
 * <pre>
 * ./gradlew :core:ai-agent:test --tests '*AgentLiveGatewayTest*' \
 *   -Dai.live.baseUrl=https://apihub.agnes-ai.com/v1 \
 *   -Dai.live.apiKey=sk-xxx \
 *   -Dai.live.model=agnes-2.5-flash
 * </pre>
 *
 * <p>验证内容：模型收到工具清单后，是否真的发起 file_write 调用，
 * 文件是否被真实写入，以及循环是否能在无工具调用时正常收敛。
 */
@EnabledIfSystemProperty(named = "ai.live.apiKey", matches = ".+")
final class AgentLiveGatewayTest {

  private static String prop(String key) {
    return System.getProperty(key, "");
  }

  @Test
  void modelActuallyCallsFileWriteTool(@TempDir Path workspace) throws IOException {
    String baseUrl = prop("ai.live.baseUrl");
    String apiKey = prop("ai.live.apiKey");
    String model = prop("ai.live.model");
    assertFalse(baseUrl.isEmpty(), "缺少 -Dai.live.baseUrl");
    assertFalse(model.isEmpty(), "缺少 -Dai.live.model");

    ModelConfig config =
        ModelConfig.builder(
                "custom", "自定义端点", ModelProtocolType.OPENAI_COMPATIBLE,
                "自定义端点", baseUrl, apiKey, model)
            .toolCallLimit(8)
            .build();

    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());

    ToolExecutor executor =
        new ToolExecutor(registry, null, null);

    ToolContext context =
        ToolContext.builder().homePath(workspace.toString()).build();

    AgentPromptBuilder promptBuilder = new AgentPromptBuilder();
    String systemPrompt =
        promptBuilder.build(workspace.toString(), new ArrayList<>(registry.getAll()));

    List<AgentEvent> events = new ArrayList<>();
    AgentSession session = new AgentSession(new ModelClient(), registry, executor);

    AgentRunResult result =
        session.run(
            config,
            systemPrompt,
            "请在当前目录创建文件 hello.txt，内容为两行：第一行 hello，第二行 world。"
                + "创建完成后用一句话确认。",
            context,
            new ModelCancellationToken(),
            events::add);

    assertNotNull(result);
    System.out.println("[live] result=" + result);
    System.out.println("[live] output=" + result.getOutput());
    for (AgentEvent event : events) {
      if (event.getType() == AgentEvent.Type.TOOL_STARTED) {
        System.out.println("[live] tool call: " + event.getToolCall().getName()
            + " " + event.getToolCall().getArguments());
      }
    }

    assertFalse(result.isFailed(), "循环不应失败: " + result.getOutput());
    assertTrue(result.getToolCallCount() > 0, "模型应当调用至少一个工具");

    Path created = workspace.resolve("hello.txt");
    assertTrue(Files.exists(created), "模型应通过 file_write 创建 hello.txt");
    String content = Files.readString(created);
    assertTrue(content.contains("hello"), "文件内容应包含 hello，实际: " + content);
    assertTrue(content.contains("world"), "文件内容应包含 world，实际: " + content);
  }
}
