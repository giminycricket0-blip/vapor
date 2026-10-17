package com.winlator.cmod.runtime.compat.gamefixes;

import com.winlator.cmod.runtime.display.xserver.ScreenInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shipped Steam demo's settings format only; no guesses about other games' files. */
final class Eu3DemoDisplaySettings {
  private static final Pattern SIZE = Pattern.compile(
      "(?m)^([ \\t]*graphics[ \\t]*=[ \\t]*\\{[ \\t]*\\r?\\n"
          + "[ \\t]*size[ \\t]*=[ \\t]*\\{[ \\t]*\\r?\\n"
          + "[ \\t]*x[ \\t]*=[ \\t]*)([0-9]+)"
          + "([ \\t]*\\r?\\n[ \\t]*y[ \\t]*=[ \\t]*)([0-9]+)"
          + "([ \\t]*\\r?\\n[ \\t]*\\})");

  private Eu3DemoDisplaySettings() {}

  static void prepare(File settings, ScreenInfo screen) throws IOException {
    if (screen == null || screen.width < 1024 || screen.height < 768) {
      throw new IOException("The demo requires a virtual screen of at least 1024x768");
    }
    Path path = settings.toPath();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) {
      throw new IOException("The demo settings file is missing or unsupported");
    }
    // Latin-1 round-trips every original byte, including comments and non-ASCII player names.
    String original = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
    Matcher match = SIZE.matcher(original);
    if (!match.find()) throw new IOException("The demo display settings format is unsupported");
    int xStart = match.start(2), xEnd = match.end(2);
    int yStart = match.start(4), yEnd = match.end(4);
    if (match.find()) throw new IOException("The demo has ambiguous display settings");
    String updated = original.substring(0, xStart) + screen.width
        + original.substring(xEnd, yStart) + screen.height + original.substring(yEnd);
    if (updated.equals(original)) return;

    Path backup = path.resolveSibling("settings.txt.vapor-original");
    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("The original settings backup is unavailable");
      }
    } else {
      writeAtomically(backup, original.getBytes(StandardCharsets.ISO_8859_1));
    }
    writeAtomically(path, updated.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static void writeAtomically(Path path, byte[] content) throws IOException {
    Path pending = Files.createTempFile(path.getParent(), "vapor-display-", ".tmp");
    try {
      Files.write(pending, content);
      Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }
}
