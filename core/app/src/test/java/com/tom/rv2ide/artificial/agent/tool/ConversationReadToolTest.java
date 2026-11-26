package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.*;

import com.tom.rv2ide.ai.agent.conversation.FileConversationStore;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.protocol.ReferenceMeta;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConversationReadToolTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test public void authorizationComesOnlyFromExplicitInputAndSurvivesContextCopies() {
    String id = UUID.randomUUID().toString();
    Set<String> ids = ConversationReadTool.referencedIds("请读取 " + id,
        List.of(new ReferenceMeta("file", UUID.randomUUID().toString(), "文件", "", 0, 0)));
    assertEquals(Set.of(id), ids);
    ToolContext context = ToolContext.builder().readableConversationIds(ids).build();
    ids.clear();
    assertTrue(context.withToolCallId("call").withProgressListener(message -> {})
        .getReadableConversationIds().contains(id));
  }

  @Test public void quotedIdsDoNotAuthorizeButExplicitTextStillDoes() {
    String quoted = UUID.randomUUID().toString();
    String explicit = UUID.randomUUID().toString();
    String text = "> 引用自 助手回复：\n> 历史 ID=" + quoted + "\n>\n\n请读取 " + explicit;
    assertEquals(Set.of(explicit), ConversationReadTool.referencedIds(text, Collections.emptyList()));
  }

  @Test public void quotedIdsStayUnauthorizedAfterInjection() throws Exception {
    FileConversationStore store = new FileConversationStore(tmp.newFolder());
    String id = store.create("未被引用", "/private", "m", "confirm").getId();
    ConversationReadTool tool = new ConversationReadTool(store);
    tool.authorizeUserMessage(new com.tom.rv2ide.ai.protocol.UserModelMessage(
        "> 引用自 助手回复：\n> " + id, "", "> 引用自 助手回复：\n> " + id, false));
    ToolContext context = ToolContext.builder().conversationId(UUID.randomUUID().toString()).build();
    assertTrue(tool.execute(new JSONObject().put("conversation_id", id), context).isError());
    tool.authorizeUserMessage(new com.tom.rv2ide.ai.protocol.UserModelMessage(
        "读取 " + id, "", "读取 " + id, false));
    assertTrue(tool.execute(new JSONObject().put("conversation_id", id), context).isError());
    tool.authorizeUserMessage(new com.tom.rv2ide.ai.protocol.UserModelMessage("读取 " + id));
    assertFalse(tool.execute(new JSONObject().put("conversation_id", id), context).isError());
  }

  @Test public void rejectsPathsUnauthorizedMissingAndForeignAnchor() throws Exception {
    FileConversationStore store = new FileConversationStore(tmp.newFolder());
    String id = store.create("旧会话", "/private", "m", "confirm").getId();
    ConversationReadTool tool = new ConversationReadTool(store);
    ToolContext unauthorized = ToolContext.builder().conversationId(UUID.randomUUID().toString()).build();
    assertTrue(tool.execute(new JSONObject().put("conversation_id", "../x"), unauthorized).isError());
    assertTrue(tool.execute(new JSONObject().put("conversation_id", id), unauthorized).isError());
    ToolContext context = ToolContext.builder().conversationId(id).build();
    assertTrue(tool.execute(new JSONObject().put("conversation_id", id)
        .put("before_uuid", UUID.randomUUID().toString()), context).isError());
    String missing = UUID.randomUUID().toString();
    assertTrue(tool.execute(new JSONObject().put("conversation_id", missing),
        ToolContext.builder().readableConversationIds(Set.of(missing)).build()).isError());
  }

  @Test public void limitsOutputAndPagesWithoutMutatingTheTarget() throws Exception {
    FileConversationStore store = new FileConversationStore(tmp.newFolder());
    String id = store.create("历史", "/private/workspace", "m", "confirm").getId();
    List<String> uuids = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      UserMessageEntry user = UserMessageEntry.create(null, i, "文本\"\n".repeat(6000));
      store.append(id, user);
      uuids.add(user.getUuid());
    }
    int countBefore = store.read(id).size();
    ConversationReadTool tool = new ConversationReadTool(store);
    ToolContext context = ToolContext.builder().readableConversationIds(Set.of(id)).build();
    String before = "";
    for (int i = 0; i < 5; i++) {
      ToolResult result = tool.execute(new JSONObject().put("conversation_id", id).put("before_uuid", before), context);
      assertFalse(result.getContent(), result.isError());
      assertTrue(result.getContent().length() <= 24000);
      assertFalse(result.getContent().contains("/private/workspace"));
      JSONObject json = new JSONObject(result.getContent());
      before = json.getString("next_before_uuid");
      assertEquals(uuids.get(7 - i), before);
      assertTrue(json.getJSONArray("entries").getJSONObject(0).getBoolean("truncated"));
    }
    assertTrue(tool.execute(new JSONObject().put("conversation_id", id).put("before_uuid", before), context).isError());
    assertEquals(countBefore, store.read(id).size());
  }
}
