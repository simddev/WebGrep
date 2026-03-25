package com.webgrep.reporting;

/**
 * A single line in a local file that contains at least one keyword match.
 * {@code page} is 0 when the source has no page structure (plain text, etc.).
 */
public record FileMatch(int page, int line, int count, String snippet) {}
