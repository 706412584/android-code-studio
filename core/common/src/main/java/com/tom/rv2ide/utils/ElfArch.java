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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal ELF header reader, used to tell which CPU architecture a native binary was built for.
 *
 * <p>Why this exists: {@code libaapt2.so} is extracted from the APK's native library directory,
 * and that directory depends on the ABI variant of the installed APK. On multi-ABI devices it is
 * possible for a previously extracted binary of the <em>wrong</em> architecture to survive in the
 * tools directory — for example an x86_64 {@code aapt2} left behind on an ARM device. Executing
 * it fails with an unhelpful "Exec format error", and because the old extraction logic only
 * checked {@code File.exists()}, the bad binary was never replaced. See upstream issue #370.
 *
 * <p>This class only reads the fixed-size ELF identification and header fields — no section or
 * program headers are parsed, and the file is not loaded into memory.
 *
 * <p>Deliberately free of Android types so it can be unit tested on the JVM.
 */
public final class ElfArch {

  /** Machine values from the ELF specification (e_machine). */
  public static final int EM_386 = 3;
  public static final int EM_ARM = 40;
  public static final int EM_X86_64 = 62;
  public static final int EM_AARCH64 = 183;

  private ElfArch() {}

  /**
   * Read the {@code e_machine} field from an ELF file.
   *
   * @param file The file to inspect. May be any file; non-ELF input yields {@code null}.
   * @return The {@code e_machine} value, or {@code null} if the file is missing, unreadable,
   *     truncated, or not an ELF file.
   */
  @Nullable
  public static Integer machineOf(@Nullable File file) {
    if (file == null || !file.isFile() || file.length() <= 0) {
      return null;
    }

    try (InputStream in = new FileInputStream(file)) {
      return machineOf(in);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Read the {@code e_machine} field from an ELF stream. The stream is not closed.
   *
   * @param in The stream to read. Must supply at least 20 bytes for a valid ELF header.
   * @return The {@code e_machine} value, or {@code null} if the input is not a readable ELF
   *     header.
   */
  @Nullable
  public static Integer machineOf(@Nullable InputStream in) {
    if (in == null) {
      return null;
    }

    // e_ident is 16 bytes; e_machine follows immediately (offset 18 for both ELF32 and ELF64).
    final byte[] header = new byte[20];
    try {
      int read = 0;
      while (read < header.length) {
        final int count = in.read(header, read, header.length - read);
        if (count <= 0) {
          return null;
        }
        read += count;
      }
    } catch (IOException e) {
      return null;
    }

    // Magic: 0x7f 'E' 'L' 'F'
    if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
      return null;
    }

    // EI_DATA: 1 = little endian, 2 = big endian. Anything else is invalid.
    final int dataEncoding = header[5] & 0xff;
    final boolean bigEndian = dataEncoding == 2;
    if (dataEncoding != 1 && !bigEndian) {
      return null;
    }

    final int low = header[18] & 0xff;
    final int high = header[19] & 0xff;
    return bigEndian ? (low << 8) | high : low | (high << 8);
  }

  /**
   * Check whether a binary's architecture can run on a device reporting the given ABI list.
   *
   * <p>A 32-bit ARM binary is accepted only when the device lists a 32-bit ARM ABI. 64-bit-only
   * ARM devices (no {@code armeabi-v7a} in the list) cannot execute such binaries, so they are
   * reported as incompatible and get replaced.
   *
   * @param machine The {@code e_machine} value, or {@code null} when it could not be determined
   *     (file missing, unreadable, truncated, or not an ELF binary).
   * @param supportedAbis The device's supported ABI names, in priority order, as reported by
   *     {@code Build.SUPPORTED_ABIS}.
   * @return {@code true} if the binary is compatible. An unrecognised {@code e_machine} is treated
   *     as compatible so a future architecture cannot block the build; an unreadable or non-ELF
   *     file is treated as incompatible so callers repair it.
   */
  public static boolean isCompatibleWith(
      @Nullable Integer machine, @NonNull String[] supportedAbis) {
    if (machine == null) {
      // Not an ELF binary (or unreadable) — treat as broken so callers re-extract it.
      return false;
    }

    boolean arm64 = false;
    boolean arm32 = false;
    boolean x86_64 = false;
    boolean x86 = false;

    for (final String abi : supportedAbis) {
      if (abi == null) {
        continue;
      }
      switch (abi) {
        case "arm64-v8a":
          arm64 = true;
          break;
        case "armeabi-v7a":
        case "armeabi":
        case "arm":
          arm32 = true;
          break;
        case "x86_64":
          x86_64 = true;
          break;
        case "x86":
          x86 = true;
          break;
        default:
          // Unknown ABI names are ignored.
          break;
      }
    }

    switch (machine) {
      case EM_AARCH64:
        return arm64;
      case EM_ARM:
        // A 32-bit ARM binary needs a 32-bit ARM ABI. ARM64 alone is not enough: 64-bit-only
        // devices cannot execute 32-bit ARM binaries.
        return arm32;
      case EM_X86_64:
        return x86_64;
      case EM_386:
        return x86;
      default:
        // Unknown machine type — allow it rather than blocking the build.
        return true;
    }
  }
}
