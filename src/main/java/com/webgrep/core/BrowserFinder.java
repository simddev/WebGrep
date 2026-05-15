package com.webgrep.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates system-installed browser binaries for use with Playwright.
 *
 * <p>Chromium/Chrome is checked first because those browsers speak CDP natively — Playwright
 * can drive any system Chrome or Chromium via {@code executablePath} without any custom
 * protocol patches. Firefox is checked second; Playwright's Firefox needs its own patched
 * build, so system Firefox support is best-effort and may fail on pre-release channels.
 *
 * <p>Preference order on every platform: Chromium → Chrome, then Firefox Dev Edition →
 * Nightly → stable → ESR. Hard-coded OS paths are checked first for speed; {@code which} /
 * {@code where} is used as a fallback for non-standard package-manager install locations.
 */
public class BrowserFinder {

    // ── Chromium / Chrome ─────────────────────────────────────────────────────

    public static Optional<Path> findChromium() {
        for (Path p : knownChromiumPaths()) {
            if (Files.isExecutable(p)) return Optional.of(p);
        }
        return findViaShell("chromium-browser", "chromium",
                            "google-chrome", "google-chrome-stable");
    }

    private static List<Path> knownChromiumPaths() {
        String os = System.getProperty("os.name", "").toLowerCase();

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

    // ── Firefox ───────────────────────────────────────────────────────────────

    public static Optional<Path> findFirefox() {
        for (Path p : knownFirefoxPaths()) {
            if (Files.isExecutable(p)) return Optional.of(p);
        }
        return findViaShell("firefox-developer-edition", "firefox-nightly",
                            "firefox", "firefox-esr", "iceweasel");
    }

    private static List<Path> knownFirefoxPaths() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return List.of(
                Path.of("/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox"),
                Path.of("/Applications/Firefox Nightly.app/Contents/MacOS/firefox"),
                Path.of("/Applications/Firefox.app/Contents/MacOS/firefox"),
                Path.of("/Applications/Firefox ESR.app/Contents/MacOS/firefox")
            );
        }

        if (os.contains("win")) {
            String pf   = System.getenv().getOrDefault("ProgramFiles",     "C:\\Program Files");
            String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", pf);
            return List.of(
                Path.of(pf,   "Firefox Developer Edition", "firefox.exe"),
                Path.of(pf,   "Mozilla Firefox Nightly",   "firefox.exe"),
                Path.of(pf,   "Mozilla Firefox",           "firefox.exe"),
                Path.of(pf86, "Mozilla Firefox",           "firefox.exe")
            );
        }

        // Linux
        return List.of(
            Path.of("/usr/bin/firefox-developer-edition"),
            Path.of("/usr/bin/firefox-nightly"),
            Path.of("/usr/bin/firefox"),
            Path.of("/usr/bin/firefox-esr"),
            Path.of("/snap/bin/firefox"),
            Path.of("/usr/bin/iceweasel"),
            Path.of("/opt/firefox-developer-edition/firefox"),
            Path.of("/opt/firefox/firefox")
        );
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private static Optional<Path> findViaShell(String... names) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
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
