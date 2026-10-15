/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

/**
 * Tests for {@link ElfArch}.
 *
 * <p>The inputs are synthetic ELF headers rather than real binaries, so the tests pin down the
 * header-parsing rules (endianness, truncation, non-ELF input) without shipping test fixtures.
 */
public class ElfArchTest {

  /** Builds a minimal ELF header with the given magic, data encoding and machine value. */
  private static byte[] elfHeader(byte[] magic, int dataEncoding, int machine, int length) {
    final byte[] header = new byte[length];
    System.arraycopy(magic, 0, header, 0, Math.min(magic.length, length));
    if (length > 5) {
      header[5] = (byte) dataEncoding;
    }
    if (length >= 20) {
      if (dataEncoding == 2) {
        // big endian
        header[18] = (byte) ((machine >> 8) & 0xff);
        header[19] = (byte) (machine & 0xff);
      } else {
        // little endian
        header[18] = (byte) (machine & 0xff);
        header[19] = (byte) ((machine >> 8) & 0xff);
      }
    }
    return header;
  }

  private static final byte[] ELF_MAGIC = {0x7f, 'E', 'L', 'F'};
  private static final int LITTLE_ENDIAN = 1;
  private static final int BIG_ENDIAN = 2;

  // ---------------------------------------------------------------- machineOf(InputStream)

  @Test
  public void readsMachineFromLittleEndianAarch64() {
    final byte[] header = elfHeader(ELF_MAGIC, LITTLE_ENDIAN, ElfArch.EM_AARCH64, 20);
    assertEquals(
        Integer.valueOf(ElfArch.EM_AARCH64),
        ElfArch.machineOf(new ByteArrayInputStream(header)));
  }

  @Test
  public void readsMachineFromLittleEndianX86_64() {
    final byte[] header = elfHeader(ELF_MAGIC, LITTLE_ENDIAN, ElfArch.EM_X86_64, 20);
    assertEquals(
        Integer.valueOf(ElfArch.EM_X86_64),
        ElfArch.machineOf(new ByteArrayInputStream(header)));
  }

  @Test
  public void readsMachineFromBigEndianHeader() {
    final byte[] header = elfHeader(ELF_MAGIC, BIG_ENDIAN, ElfArch.EM_ARM, 20);
    assertEquals(
        Integer.valueOf(ElfArch.EM_ARM), ElfArch.machineOf(new ByteArrayInputStream(header)));
  }

  @Test
  public void ignoresTrailingBytesAfterHeader() {
    final byte[] header = elfHeader(ELF_MAGIC, LITTLE_ENDIAN, ElfArch.EM_386, 64);
    assertEquals(
        Integer.valueOf(ElfArch.EM_386), ElfArch.machineOf(new ByteArrayInputStream(header)));
  }

  @Test
  public void rejectsNonElfInput() {
    final byte[] notElf = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
    assertNull(ElfArch.machineOf(new ByteArrayInputStream(notElf)));
  }

  @Test
  public void rejectsTruncatedHeader() {
    // Valid magic but fewer than 20 bytes — e_machine is not present.
    final byte[] truncated = elfHeader(ELF_MAGIC, LITTLE_ENDIAN, ElfArch.EM_AARCH64, 10);
    assertNull(ElfArch.machineOf(new ByteArrayInputStream(truncated)));
  }

  @Test
  public void rejectsEmptyInput() {
    assertNull(ElfArch.machineOf(new ByteArrayInputStream(new byte[0])));
  }

  @Test
  public void rejectsInvalidDataEncoding() {
    final byte[] header = elfHeader(ELF_MAGIC, 0, ElfArch.EM_AARCH64, 20);
    assertNull(ElfArch.machineOf(new ByteArrayInputStream(header)));
  }

  @Test
  public void handlesNullInputStream() {
    assertNull(ElfArch.machineOf((java.io.InputStream) null));
  }

  // ---------------------------------------------------------------- machineOf(File)

  @Test
  public void readsMachineFromFile() throws IOException {
    final File file = File.createTempFile("elfarch", ".bin");
    try {
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(elfHeader(ELF_MAGIC, LITTLE_ENDIAN, ElfArch.EM_AARCH64, 20));
      }
      assertEquals(Integer.valueOf(ElfArch.EM_AARCH64), ElfArch.machineOf(file));
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void returnsNullForMissingFile() {
    final File missing = new File(System.getProperty("java.io.tmpdir"), "elfarch-does-not-exist");
    assertNull(ElfArch.machineOf(missing));
  }

  @Test
  public void returnsNullForEmptyFile() throws IOException {
    final File file = File.createTempFile("elfarch-empty", ".bin");
    try {
      assertEquals(Integer.valueOf(0), Integer.valueOf((int) file.length()));
      assertNull(ElfArch.machineOf(file));
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void returnsNullForNullFile() {
    assertNull(ElfArch.machineOf((File) null));
  }

  // ---------------------------------------------------------------- isCompatibleWith

  private static final String[] ARM64_DEVICE = {"arm64-v8a", "armeabi-v7a", "armeabi"};
  private static final String[] ARM64_ONLY_DEVICE = {"arm64-v8a"};
  private static final String[] ARM32_DEVICE = {"armeabi-v7a", "armeabi"};
  private static final String[] X86_64_DEVICE = {"x86_64", "x86"};

  @Test
  public void aarch64RunsOnArm64DeviceOnly() {
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_AARCH64, ARM64_DEVICE));
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_AARCH64, ARM32_DEVICE));
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_AARCH64, X86_64_DEVICE));
  }

  @Test
  public void arm32RunsOnDevicesListingAnArm32Abi() {
    // Devices that still list armeabi-v7a can execute 32-bit ARM binaries.
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_ARM, ARM64_DEVICE));
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_ARM, ARM32_DEVICE));
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_ARM, X86_64_DEVICE));
  }

  @Test
  public void arm32IsIncompatibleOn64BitOnlyArmDevice() {
    // A 64-bit-only ARM device cannot execute a 32-bit ARM binary.
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_ARM, ARM64_ONLY_DEVICE));
  }

  @Test
  public void x86_64RunsOnX86_64DeviceOnly() {
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_X86_64, X86_64_DEVICE));
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_X86_64, ARM64_DEVICE));
  }

  @Test
  public void x86RunsOnX86_64Device() {
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_386, X86_64_DEVICE));
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_386, ARM64_DEVICE));
  }

  @Test
  public void unknownMachineDoesNotBlock() {
    // A future architecture must not block the build.
    assertTrue(ElfArch.isCompatibleWith(9999, ARM64_DEVICE));
  }

  @Test
  public void unreadableOrNonElfFileIsIncompatible() {
    // A null machine means "not an ELF binary / unreadable", which callers must repair.
    assertFalse(ElfArch.isCompatibleWith(null, ARM64_DEVICE));
  }

  @Test
  public void unknownAbisAreIgnored() {
    final String[] exotic = {"mips64", "riscv64"};
    assertFalse(ElfArch.isCompatibleWith(ElfArch.EM_AARCH64, exotic));
    assertTrue(ElfArch.isCompatibleWith(9999, exotic));
  }

  @Test
  public void toleratesNullAbiEntries() {
    final String[] withNull = {"arm64-v8a", null};
    assertTrue(ElfArch.isCompatibleWith(ElfArch.EM_AARCH64, withNull));
  }
}
