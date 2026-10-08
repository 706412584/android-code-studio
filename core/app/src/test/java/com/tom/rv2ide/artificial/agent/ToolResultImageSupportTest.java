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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import org.junit.Test;

/**
 * {@link ToolResultImageSupport} 的校验与解码测试。
 *
 * <p><b>为什么这些必须机器验证</b>：判定写错的后果是「图片静默不显示」或「主线程 OOM」——
 * 两者都不抛异常、不留日志，肉眼只能看到「没图」，无从判断是数据问题还是判定问题。
 */
public class ToolResultImageSupportTest {

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  /** 正常 PNG：应解码成功，且字节与原文一致。 */
  @Test
  public void decodesValidPng() {
    byte[] raw = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    ToolResultImageSupport.Decoded d =
        ToolResultImageSupport.decode("image/png", b64(raw));
    assertTrue("应解码成功", d instanceof ToolResultImageSupport.Decoded.Ok);
    assertEquals(
        "字节应原样还原",
        raw.length,
        ((ToolResultImageSupport.Decoded.Ok) d).getBytes().length);
  }

  /** 空数据被拒。 */
  @Test
  public void rejectsEmpty() {
    assertEquals(
        ToolResultImageSupport.Reason.EMPTY,
        ((ToolResultImageSupport.Decoded.Rejected)
                ToolResultImageSupport.decode("image/png", ""))
            .getReason());
    assertEquals(
        ToolResultImageSupport.Reason.EMPTY,
        ((ToolResultImageSupport.Decoded.Rejected)
                ToolResultImageSupport.decode("image/png", null))
            .getReason());
  }

  /**
   * 不支持的 MIME 被拒（svg 是明确排除的类型）。
   *
   * <p>svg 被排除是因为它能携带脚本，且渲染路径与位图完全不同。
   */
  @Test
  public void rejectsUnsupportedMimeType() {
    String data = b64(new byte[] {1, 2, 3});
    assertEquals(
        ToolResultImageSupport.Reason.UNSUPPORTED_TYPE,
        ((ToolResultImageSupport.Decoded.Rejected)
                ToolResultImageSupport.decode("image/svg+xml", data))
            .getReason());
  }

  /**
   * 空 MIME 放行，靠解码结果判定。
   *
   * <p>MIME 字段本身不可信（协议层的 normalizeMimeType 会把无法识别的值**篡改**成
   * image/png），所以它只能用来挡掉**明确不支持**的类型；无从判断时应当放行，
   * 让「能否解码」这个更可靠的判据说话。
   */
  @Test
  public void emptyMimeTypeIsAllowed() {
    String data = b64(new byte[] {1, 2, 3});
    assertTrue(
        "空 MIME 应放行",
        ToolResultImageSupport.decode("", data) instanceof ToolResultImageSupport.Decoded.Ok);
    assertTrue(
        "null MIME 应放行",
        ToolResultImageSupport.decode(null, data) instanceof ToolResultImageSupport.Decoded.Ok);
  }

  /**
   * 明确不支持的类型（svg）必须被拒——这是安全边界。
   *
   * <p>回归保护：早先的实现先调 normalizeMimeType 再检查，而 normalize 会把 svg
   * 兜底成 image/png，导致这条边界形同虚设（实测放行了 svg）。
   */
  @Test
  public void unsupportedTypeIsRejectedEvenThoughNormalizeWouldMaskIt() {
    String data = b64(new byte[] {1, 2, 3});
    ToolResultImageSupport.Decoded d =
        ToolResultImageSupport.decode("image/svg+xml", data);
    assertTrue("svg 必须被拒", d instanceof ToolResultImageSupport.Decoded.Rejected);
    assertEquals(
        ToolResultImageSupport.Reason.UNSUPPORTED_TYPE,
        ((ToolResultImageSupport.Decoded.Rejected) d).getReason());
  }

  /** 畸形 base64 被拒，而不是抛异常。 */
  @Test
  public void rejectsMalformedBase64() {
    ToolResultImageSupport.Decoded d =
        ToolResultImageSupport.decode("image/png", "!!!not-base64!!!");
    assertTrue("畸形 base64 应被拒", d instanceof ToolResultImageSupport.Decoded.Rejected);
  }

  /**
   * 超过单图上限的数据被拒。
   *
   * <p>这是防 OOM 的关键：不做这个判定，一张几十 MB 的截图会在解码时把进程吃掉。
   */
  @Test
  public void rejectsOversizedImage() {
    byte[] huge = new byte[ToolResultImageSupport.MAX_IMAGE_BYTES + 1024];
    ToolResultImageSupport.Decoded d =
        ToolResultImageSupport.decode("image/png", b64(huge));
    assertTrue("超限应被拒", d instanceof ToolResultImageSupport.Decoded.Rejected);
    assertEquals(
        ToolResultImageSupport.Reason.TOO_LARGE,
        ((ToolResultImageSupport.Decoded.Rejected) d).getReason());
  }

  /** 恰好在上限内的图片应通过（边界不能误杀）。 */
  @Test
  public void acceptsImageExactlyAtLimit() {
    byte[] atLimit = new byte[ToolResultImageSupport.MAX_IMAGE_BYTES];
    ToolResultImageSupport.Decoded d =
        ToolResultImageSupport.decode("image/png", b64(atLimit));
    assertTrue("恰好等于上限应通过", d instanceof ToolResultImageSupport.Decoded.Ok);
  }

  /**
   * 缓存键必须随内容变化。
   *
   * <p>键不变的后果是 Glide 命中旧缓存，用户看到**上一张**截图——比不显示更难排查。
   */
  @Test
  public void cacheKeyDiffersForDifferentContent() {
    String a = b64(new byte[] {1, 2, 3});
    String b = b64(new byte[] {4, 5, 6});
    assertNotEquals(
        "不同内容必须不同键",
        ToolResultImageSupport.cacheKey(a),
        ToolResultImageSupport.cacheKey(b));
  }

  /** 相同内容产生相同键——否则缓存永不命中，滚动回看反复重解码。 */
  @Test
  public void cacheKeyStableForSameContent() {
    String a = b64(new byte[] {1, 2, 3, 4, 5});
    assertEquals(
        "相同内容必须同键",
        ToolResultImageSupport.cacheKey(a),
        ToolResultImageSupport.cacheKey(a));
  }

  /** 空输入返回空键，不抛异常。 */
  @Test
  public void cacheKeyOfEmptyIsEmpty() {
    assertEquals("", ToolResultImageSupport.cacheKey(null));
    assertEquals("", ToolResultImageSupport.cacheKey(""));
  }

  /** 前缀相同但长度不同的内容也必须区分（键里含长度）。 */
  @Test
  public void cacheKeyDistinguishesByLength() {
    String a = b64(new byte[] {1, 2, 3});
    String b = b64(new byte[] {1, 2, 3, 4});
    assertNotEquals(
        "长度不同应不同键",
        ToolResultImageSupport.cacheKey(a),
        ToolResultImageSupport.cacheKey(b));
  }
}
