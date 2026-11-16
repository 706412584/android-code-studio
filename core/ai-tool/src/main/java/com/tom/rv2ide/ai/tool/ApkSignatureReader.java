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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 离线读取 APK 签名证书：v1（META-INF/*.RSA 的 PKCS#7）+ v2/v3（APK Signing Block）。
 *
 * <p><b>为什么不用 apksigner/apksig</b>：apksigner 是 SDK 里的 jar（设备上未必有），
 * apksig 的 {@code ApkVerifier} 是「验签」语义——对损坏/未签名包会直接报错退不出证书。
 * 这里只做「把证书读出来」：v1 用 JDK 自带的 {@link CertificateFactory}（它能解析
 * PKCS#7），v2/v3 手读签名块，纯 Java 8、零依赖、不需要 shell。
 *
 * <p>只读证书与签名方案，<b>不校验签名是否有效</b>——「这个包是谁签的」和
 * 「这个包有没有被改过」是两个问题，本读取器只回答前者。
 */
final class ApkSignatureReader {

  /** 读取结果。 */
  static final class Result {
    final List<X509Certificate> certificates = new ArrayList<>();
    final List<String> schemes = new ArrayList<>();
    String error = "";
  }

  private static final int EOCD_SIG = 0x06054b50;
  private static final byte[] SIG_BLOCK_MAGIC =
      "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  private static final int V2_BLOCK_ID = 0x7109871a;
  private static final int V3_BLOCK_ID = 0xf05368c0;

  private ApkSignatureReader() {}

  static Result read(File apk) {
    Result result = new Result();
    try {
      readV1(apk, result);
    } catch (Exception e) {
      // v1 失败不影响后续。
    }
    try {
      readV2V3(apk, result);
    } catch (Exception e) {
      // v2/v3 失败不影响 v1 结果。
    }
    if (result.certificates.isEmpty() && result.error.isEmpty()) {
      result.error = "未在 APK 中发现签名证书（未签名，或签名使用了不支持的格式）。";
    }
    return result;
  }

  // ---- v1：META-INF/*.RSA|DSA|EC 的 PKCS#7 ----

  private static void readV1(File apk, Result result) throws Exception {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    try (ZipFile zip = new ZipFile(apk)) {
      java.util.Enumeration<? extends ZipEntry> en = zip.entries();
      boolean found = false;
      while (en.hasMoreElements()) {
        ZipEntry entry = en.nextElement();
        String name = entry.getName().toUpperCase(java.util.Locale.ROOT);
        if (!name.startsWith("META-INF/")) {
          continue;
        }
        if (!(name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
          continue;
        }
        byte[] block;
        try (java.io.InputStream in = zip.getInputStream(entry)) {
          block = ParseUtil.readAll(in);
        }
        Collection<? extends Certificate> certs =
            factory.generateCertificates(new ByteArrayInputStream(block));
        for (Certificate cert : certs) {
          if (cert instanceof X509Certificate) {
            addCert(result, (X509Certificate) cert);
          }
        }
        found = true;
      }
      if (found && !result.certificates.isEmpty()) {
        result.schemes.add("v1");
      }
    }
  }

  // ---- v2/v3：APK Signing Block ----

  private static void readV2V3(File apk, Result result) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
      long length = raf.length();
      long eocdPos = findEocd(raf, length);
      if (eocdPos < 0) {
        return;
      }
      byte[] eocd = new byte[20];
      raf.seek(eocdPos);
      raf.readFully(eocd);
      long cdOffset = ParseUtil.u32(eocd, 16) & 0xffffffffL;
      if (cdOffset < 32 || cdOffset > length) {
        return;
      }
      // 签名块紧接在中央目录之前，末尾 16 字节是魔数。
      byte[] trailer = new byte[24];
      raf.seek(cdOffset - 24);
      raf.readFully(trailer);
      if (!matchesMagic(trailer, 8)) {
        return; // 无签名块
      }
      long blockSize = ParseUtil.u64(trailer, 0) & 0xffffffffffffffffL;
      long blockStart = cdOffset - blockSize - 8;
      if (blockStart < 0 || blockSize <= 0 || blockSize > 64L * 1024 * 1024) {
        return;
      }
      byte[] block = new byte[(int) blockSize + 8];
      raf.seek(blockStart);
      raf.readFully(block);
      parseSigningBlock(block, result);
    }
  }

  private static boolean matchesMagic(byte[] buf, int offset) {
    if (buf.length < offset + SIG_BLOCK_MAGIC.length) {
      return false;
    }
    for (int i = 0; i < SIG_BLOCK_MAGIC.length; i++) {
      if (buf[offset + i] != SIG_BLOCK_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  /** 从文件尾部向前找 EOCD（End of Central Directory）。 */
  private static long findEocd(RandomAccessFile raf, long length) throws Exception {
    int maxScan = 65557; // 22 字节 EOCD + 最多 65535 字节注释
    int scan = (int) Math.min(length, maxScan);
    byte[] tail = new byte[scan];
    raf.seek(length - scan);
    raf.readFully(tail);
    for (int i = scan - 22; i >= 0; i--) {
      if (ParseUtil.u32(tail, i) == EOCD_SIG) {
        return length - scan + i;
      }
    }
    return -1;
  }

  /** 遍历签名块内的 id-value 对，解析 v2/v3。 */
  private static void parseSigningBlock(byte[] block, Result result) throws Exception {
    int p = 8; // 跳过前导 size 字段
    int end = block.length - 24; // 尾部 size(8) + 魔数(16)
    boolean v2 = false;
    boolean v3 = false;
    while (p + 12 <= end) {
      long pairLen = ParseUtil.u64(block, p) & 0xffffffffffffffffL;
      int id = ParseUtil.u32(block, p + 8);
      int valueStart = p + 12;
      int valueLen = (int) (pairLen - 4);
      if (valueLen <= 0 || valueStart + valueLen > block.length) {
        break;
      }
      if (id == V2_BLOCK_ID) {
        v2 |= extractCertsV2V3(block, valueStart, valueStart + valueLen, result);
      } else if (id == V3_BLOCK_ID) {
        v3 |= extractCertsV2V3(block, valueStart, valueStart + valueLen, result);
      }
      p += 8 + (int) pairLen;
    }
    if (v2) {
      result.schemes.add("v2");
    }
    if (v3) {
      result.schemes.add("v3");
    }
  }

  /**
   * 解析 v2/v3 block 的证书。结构：
   * {@code len(signersSeq)} 内是若干个 {@code len(signer)}；
   * signer = {@code len(signedData) + len(signatures) + len(publicKey) [v3 前面还有 min/max sdk]}；
   * signedData = {@code len(digests) + len(certificates) + len(additionalAttrs)}；
   * certificates = 若干个 {@code len(DER)}。
   *
   * @return 是否至少解析出一张证书
   */
  private static boolean extractCertsV2V3(byte[] b, int start, int end, Result result) {
    boolean any = false;
    int[] cur = {start};
    int outerLen = readLen32(b, cur);
    int outerEnd = Math.min(cur[0] + outerLen, end);
    while (cur[0] + 4 <= outerEnd) {
      int signerLen = readLen32(b, cur);
      int signerEnd = Math.min(cur[0] + signerLen, outerEnd);
      // signedData 是 signer 的第一个长度前缀块。
      if (cur[0] + 4 > signerEnd) {
        break;
      }
      int signedDataLen = readLen32(b, cur);
      int signedDataStart = cur[0];
      int signedDataEnd = Math.min(signedDataStart + signedDataLen, signerEnd);
      any |= extractCertsFromSignedData(b, signedDataStart, signedDataEnd, result);
      // 跳到 signer 末尾继续下一个 signer。
      cur[0] = signerEnd;
    }
    return any;
  }

  private static boolean extractCertsFromSignedData(byte[] b, int start, int end, Result result) {
    int[] cur = {start};
    if (cur[0] + 4 > end) {
      return false;
    }
    int digestsLen = readLen32(b, cur); // 跳过 digests
    cur[0] += digestsLen;
    if (cur[0] + 4 > end) {
      return false;
    }
    int certsLen = readLen32(b, cur);
    int certsEnd = Math.min(cur[0] + certsLen, end);
    boolean any = false;
    CertificateFactory factory;
    try {
      factory = CertificateFactory.getInstance("X.509");
    } catch (Exception e) {
      return false;
    }
    while (cur[0] + 4 <= certsEnd) {
      int derLen = readLen32(b, cur);
      if (derLen <= 0 || cur[0] + derLen > certsEnd) {
        break;
      }
      try {
        Certificate cert =
            factory.generateCertificate(new ByteArrayInputStream(b, cur[0], derLen));
        if (cert instanceof X509Certificate) {
          addCert(result, (X509Certificate) cert);
          any = true;
        }
      } catch (Exception ignored) {
        // 跳过无法解析的证书。
      }
      cur[0] += derLen;
    }
    return any;
  }

  private static int readLen32(byte[] b, int[] cur) {
    int len = ParseUtil.u32(b, cur[0]);
    cur[0] += 4;
    return len;
  }

  private static void addCert(Result result, X509Certificate cert) {
    try {
      byte[] encoded = cert.getEncoded();
      String key = ParseUtil.hex(encoded);
      for (X509Certificate existing : result.certificates) {
        if (ParseUtil.hex(existing.getEncoded()).equals(key)) {
          return;
        }
      }
      result.certificates.add(cert);
    } catch (Exception ignored) {
      // 编码失败的证书跳过。
    }
  }

  /** 便捷：把证书映射成「指纹 → 证书」有序表（调试用，非必需）。 */
  @SuppressWarnings("unused")
  private static Map<String, X509Certificate> index(List<X509Certificate> certs) {
    Map<String, X509Certificate> map = new LinkedHashMap<>();
    for (X509Certificate c : certs) {
      try {
        map.put(ParseUtil.hex(c.getEncoded()), c);
      } catch (Exception ignored) {
        // ignore
      }
    }
    return map;
  }
}
