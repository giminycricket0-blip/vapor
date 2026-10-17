package com.winlator.cmod.runtime.wine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public abstract class PEHelper {
  public enum Architecture {
    X86,
    X64,
    UNKNOWN
  }

  public enum GraphicsApi { D3D8, D3D9, D3D10, D3D11, D3D12, OPENGL, VULKAN }
  public enum ImportStatus { COMPLETE, UNKNOWN }

  /** Positive static-import evidence only. Even COMPLETE cannot see LoadLibrary or child processes. */
  public static final class ImportHints {
    public final ImportStatus status;
    public final Set<GraphicsApi> graphicsApis;
    public final boolean xInputImported;

    private ImportHints(ImportStatus status, Set<GraphicsApi> graphicsApis, boolean xInputImported) {
      this.status = status;
      this.graphicsApis = Collections.unmodifiableSet(EnumSet.copyOf(graphicsApis));
      this.xInputImported = xInputImported;
    }
  }

  /** Bounded, read-only inspection of ordinary and delay-import DLL names in the selected PE. */
  public static ImportHints inspectImports(File file) {
    try {
      if (file != null && file.isFile()) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
          return new ImportReader(input).inspect();
        }
      }
    } catch (IOException | SecurityException ignored) {
    }
    return new ImportHints(ImportStatus.UNKNOWN, EnumSet.noneOf(GraphicsApi.class), false);
  }

  private static final class ImportReader {
    private final RandomAccessFile input;
    private final long length;
    private final byte[] optional;
    private final byte[][] sections;
    private final int directoryOffset;
    private final long directoryCount;
    private final long headerSize;
    private final long imageBase;
    private final Set<GraphicsApi> graphics = EnumSet.noneOf(GraphicsApi.class);
    private boolean xInput;

    ImportReader(RandomAccessFile input) throws IOException {
      this.input = input;
      length = input.length();
      byte[] dos = read(0, 64);
      if (dos[0] != 'M' || dos[1] != 'Z') throw new IOException("Not PE");
      long offset = uint(dos, 60);
      if (offset < 64) throw new IOException("Invalid PE offset");
      byte[] coff = read(offset, 24);
      if (coff[0] != 'P' || coff[1] != 'E' || coff[2] != 0 || coff[3] != 0)
        throw new IOException("Invalid PE signature");
      int sectionCount = ushort(coff, 6);
      if (sectionCount > 96) throw new IOException("Section budget exceeded");
      optional = read(offset + 24, ushort(coff, 20));
      if (optional.length < 2) throw new IOException("Missing optional header");
      int magic = ushort(optional, 0);
      if (magic != 0x10b && magic != 0x20b) throw new IOException("Unknown PE format");
      directoryOffset = magic == 0x10b ? 96 : 112;
      if (optional.length < directoryOffset) throw new IOException("Truncated optional header");
      directoryCount = uint(optional, directoryOffset - 4);
      if (directoryCount > (optional.length - directoryOffset) / 8)
        throw new IOException("Truncated directory table");
      headerSize = uint(optional, 60);
      // VA-based delay imports are a legacy PE32 format. PE32+ must use RVAs.
      imageBase = magic == 0x10b ? uint(optional, 28) : -1;
      long sectionOffset = offset + 24 + optional.length;
      if (headerSize < sectionOffset + sectionCount * 40L || headerSize > length)
        throw new IOException("Invalid header size");
      sections = new byte[sectionCount][];
      for (int i = 0; i < sectionCount; i++) sections[i] = read(sectionOffset + i * 40L, 40);
    }

    ImportHints inspect() throws IOException {
      inspectDirectory(1, false);
      inspectDirectory(13, true);
      return new ImportHints(ImportStatus.COMPLETE, graphics, xInput);
    }

    private void inspectDirectory(int index, boolean delayed) throws IOException {
      if (index >= directoryCount) return;
      long rva = uint(optional, directoryOffset + index * 8);
      long size = uint(optional, directoryOffset + index * 8 + 4);
      if (rva == 0 && size == 0) return;
      int stride = delayed ? 32 : 20;
      if (rva == 0 || size < stride) throw new IOException("Invalid import directory");
      // Do not trust arbitrary counts or offsets from downloaded executables.
      for (int i = 0; i < 256 && (i + 1L) * stride <= size; i++) {
        byte[] descriptor = read(fileOffset(rva + i * (long) stride, stride), stride);
        boolean end = true;
        for (byte value : descriptor) if (value != 0) { end = false; break; }
        if (end) return;
        long nameRva = uint(descriptor, delayed ? 4 : 12);
        if (delayed) {
          long attributes = uint(descriptor, 0);
          if (attributes == 0 && imageBase >= 0 && nameRva >= imageBase) nameRva -= imageBase;
          else if (attributes != 1) throw new IOException("Unsupported delay imports");
        }
        if (nameRva == 0) throw new IOException("Missing import name");
        record(readName(nameRva));
      }
      throw new IOException("Unterminated or excessive imports");
    }

    private String readName(long rva) throws IOException {
      StringBuilder name = new StringBuilder();
      for (int i = 0; i < 256; i++) {
        input.seek(fileOffset(rva + i, 1));
        int value = input.read();
        if (value == 0 && i > 0) return name.toString().toLowerCase(Locale.ROOT);
        if (value < 32 || value > 126) throw new IOException("Invalid import name");
        name.append((char) value);
      }
      throw new IOException("Import name budget exceeded");
    }

    private void record(String dll) {
      switch (dll) {
        case "d3d8.dll": graphics.add(GraphicsApi.D3D8); break;
        case "d3d9.dll": graphics.add(GraphicsApi.D3D9); break;
        case "d3d10.dll": case "d3d10_1.dll": case "d3d10core.dll":
          graphics.add(GraphicsApi.D3D10); break;
        case "d3d11.dll": graphics.add(GraphicsApi.D3D11); break;
        case "d3d12.dll": case "d3d12core.dll": graphics.add(GraphicsApi.D3D12); break;
        case "opengl32.dll": graphics.add(GraphicsApi.OPENGL); break;
        case "vulkan-1.dll": graphics.add(GraphicsApi.VULKAN); break;
        case "xinput1_1.dll": case "xinput1_2.dll": case "xinput1_3.dll":
        case "xinput1_4.dll": case "xinput9_1_0.dll": case "xinputuap.dll":
          xInput = true; break;
        default: break;
      }
    }

    private long fileOffset(long rva, int size) throws IOException {
      if (rva < 0 || rva > 0xffffffffL - size + 1) throw new IOException("Invalid RVA");
      long found = rva + size <= headerSize ? rva : -1;
      for (byte[] section : sections) {
        long start = uint(section, 12);
        long rawSize = uint(section, 16);
        long span = Math.max(uint(section, 8), rawSize);
        if (rva < start || rva - start >= span) continue;
        if (found != -1 || rva - start + size > rawSize)
          throw new IOException("Ambiguous or non-file-backed RVA");
        found = uint(section, 20) + rva - start;
      }
      if (found < 0 || found > length - size) throw new IOException("RVA outside file");
      return found;
    }

    private byte[] read(long offset, int size) throws IOException {
      if (offset < 0 || offset > length - size) throw new IOException("Truncated PE");
      byte[] bytes = new byte[size];
      input.seek(offset);
      input.readFully(bytes);
      return bytes;
    }
    private static int ushort(byte[] bytes, int at) {
      return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8);
    }
    private static long uint(byte[] bytes, int at) {
      return ushort(bytes, at) | ((long) ushort(bytes, at + 2) << 16);
    }
  }

  public static Architecture detectArchitecture(File file) {
    // Retain the historical 32/64-bit buckets used by is64Bit().
    switch (readMachine(file)) {
      case 0x014c:
      case 0x01c0:
      case 0x01c4:
        return Architecture.X86;
      case 0x8664:
      case 0xAA64:
      case 0xA641:
        return Architecture.X64;
      default:
        return Architecture.UNKNOWN;
    }
  }

  public static String getArchitecture(File file) {
    // CPU identity, unlike the legacy bitness buckets above.
    switch (readMachine(file)) {
      case 0x014C:
        return "x86";
      case 0x01c0:
        return "arm";
      case 0x01c4:
        return "armv7";
      case 0x8664:
        return "x86_64";
      case 0xAA64:
        return "arm64";
      case 0xA641:
        return "arm64ec";
      default:
        return "";
    }
  }

  /** Read only the DOS/COFF headers. Malformed or unavailable files are unknown. */
  private static int readMachine(File file) {
    if (file == null || !file.isFile()) return 0;
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      byte[] dosHeader = new byte[64];
      input.readFully(dosHeader);
      if (dosHeader[0] != 'M' || dosHeader[1] != 'Z') return 0;
      long peOffset = (dosHeader[60] & 0xFFL)
          | ((dosHeader[61] & 0xFFL) << 8)
          | ((dosHeader[62] & 0xFFL) << 16)
          | ((dosHeader[63] & 0xFFL) << 24);
      if (peOffset < 64 || peOffset > input.length() - 24) return 0;
      input.seek(peOffset);
      byte[] peHeader = new byte[24];
      input.readFully(peHeader);
      if (peHeader[0] != 'P' || peHeader[1] != 'E' || peHeader[2] != 0 || peHeader[3] != 0) return 0;
      return (peHeader[4] & 0xFF) | ((peHeader[5] & 0xFF) << 8);
    } catch (IOException | SecurityException e) {
      return 0;
    }
  }

  public static boolean is64Bit(File file) {
    return detectArchitecture(file) == Architecture.X64;
  }
}
