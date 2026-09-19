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

package com.tom.rv2ide.ai.tool.mcp;

/**
 * MCP 的传输抽象：只负责「把一条 JSON-RPC 报文送出去，把对应的响应报文取回来」。
 *
 * <p><b>为什么要把传输拆出来</b>：MCP 有两套线上语义完全不同的传输——streamable HTTP
 * 把响应直接放在 POST 的响应体里，SSE（旧规范）则把响应从一条独立的长连接上推回来。
 * 把两者塞进一个类，会让「请求 id 怎么配对」「会话 id 从哪来」这类判断到处出现
 * 分支，也让人无法单独测其中一套。{@link McpClient} 只保留 JSON-RPC 语义
 * （initialize / tools/list / tools/call、错误处理），传输差异全部落在这里。
 *
 * <p><b>返回报文原文而不是解析结果</b>：JSON-RPC 的错误语义（{@code error} 字段、
 * 正文根本不是 JSON 时如何提示）属于协议层，两个实现各写一遍必然走样。
 */
public interface McpTransport {

  /**
   * 建立传输通道。
   *
   * <p>streamable HTTP 没有独立的建连步骤（会话 id 由第一次 POST 的响应头下发），
   * 这里为空实现；SSE 必须先建长连接并等到 {@code endpoint} 事件，才知道后续请求该发往哪里。
   *
   * <p>可重复调用：已建立时直接返回。
   */
  void open() throws Exception;

  /**
   * 发送一条 JSON-RPC 请求，返回其响应报文原文。
   *
   * @param jsonRpcBody 完整的 JSON-RPC 报文（含 {@code id}）
   */
  String send(String jsonRpcBody) throws Exception;

  /**
   * 发送一条通知：没有 {@code id}，没有响应，不等待。
   */
  void notify(String jsonRpcBody) throws Exception;

  /** 当前会话 id；未建立会话时为空串。 */
  String getSessionId();

  /** 关闭通道（SSE 的长连接需要显式释放）。可重复调用。 */
  void close();
}
