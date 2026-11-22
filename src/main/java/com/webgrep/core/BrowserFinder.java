package com.webgrep.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Locates a system-installed Chromium or Google Chrome binary for use with Playwright.
 *
 * <p>Chromium-family browsers speak CDP natively, so Playwright can drive any system Chrome or
 * Chromium via {@code executablePath} without patches. Firefox is deliberately not looked for:
 * Playwright drives Firefox through a protocol that exists only in its own patched build, so
 * an installed Firefox - stable, ESR, Developer Edition or Nightly - fails to start. Firefox
 * comes from Playwright's own download instead (see {@link PlaywrightRenderer}).
 *
 * <p>Hard-coded OS paths are checked first for speed; {@code which} / {@code where} is used
 * as a fallback for non-standard package-manager install locations.
 */
public class BrowserFinder {

    /**
     * Searches for an installed Chromium or Google Chrome binary on the current system.
     *
     * <p>Checks the hard-coded paths returned by {@link #knownChromiumPaths()} first, then
     * falls back to running {@code which chromium-browser}, {@code which chromium},
     * {@code which google-chrome}, and {@code which google-chrome-stable} (or the Windows
     * {@code where} equivalent).
     *
     * @return an {@code Optional} containing the path to the executable if found,
     *         or {@code Optional.empty()} if no Chromium/Chrome binary could be located.
     */
    public static Optional<Path> findChromium() {
        for (Path p : knownChromiumPaths()) {
            if (Files.isExecutable(p)) return Optional.of(p);
        }
        return findViaShell("chromium-browser", "chromium",
                            "google-chrome", "google-chrome-stable");
    }

    /**
     * Returns the list of hard-coded file-system paths where Chromium or Chrome are
     * typically installed on the detected operating system.
     *
     * <p>On Linux, Snap installs ({@code /snap/bin/chromium}) are included. On Windows,
     * the {@code LOCALAPPDATA} and {@code ProgramFiles} environment variables are consulted.
     *
     * @return an ordered list of candidate paths; paths that do not exist on disk are harmless.
     */
    private static List<Path> knownChromiumPaths() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            return List.of(
                Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"),
                Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
            );
        }

        if (os.contains("win")) {
            String lad = System.getenv().getOrDefault("LOCALAPPDATA",
                    System.getProperty("user.home") + "\\AppData\\Local");
            String pf  = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            return List.of(
                Path.of(lad, "Chromium",       "Application", "chrome.exe"),
                Path.of(lad, "Google", "Chrome", "Application", "chrome.exe"),
                Path.of(pf,  "Chromium",       "Application", "chrome.exe")
            );
        }

        // Linux
        return List.of(
            Path.of("/usr/bin/chromium-browser"),
            Path.of("/usr/bin/chromium"),
            Path.of("/usr/bin/google-chrome"),
            Path.of("/usr/bin/google-chrome-stable"),
            Path.of("/snap/bin/chromium")
        );
    }

    /**
     * Attempts to locate a browser binary by running {@code which} (Linux/macOS) or
     * {@code where} (Windows) for each of the supplied command names, in order.
     *
     * <p>The first line of output from a successful {@code which}/{@code where} invocation is
     * parsed as a file path. The path is returned only if the file is executable on disk -
     * this guards against stale {@code PATH} entries that point to scripts or aliases.
     *
     * @param names one or more command names to search for, tried in order.
     * @return an {@code Optional} containing the path of the first located executable,
     *         or {@code Optional.empty()} if none were found.
     */
    private static Optional<Path> findViaShell(String... names) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String cmd = isWindows ? "where" : "which";
        for (String name : names) {
            try {
                Process proc = new ProcessBuilder(cmd, name)
                        .redirectErrorStream(true)
                        .start();
                String line;
                try (var in = proc.getInputStream()) {
                    line = new String(in.readAllBytes()).trim().lines().findFirst().orElse("");
                }
                if (!line.isEmpty() && proc.waitFor() == 0) {
                    Path p = Path.of(line);
                    if (Files.isExecutable(p)) return Optional.of(p);
                }
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
