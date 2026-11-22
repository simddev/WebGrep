package com.webgrep.utils;

/**
 * Makes untrusted text safe to print to a terminal.
 *
 * <p>Page text, link targets, server error messages and file names all come from outside
 * WebGrep. Printed raw, a control character in any of them reaches the terminal as an
 * instruction rather than as text: {@code ESC ] 0 ; title BEL} retitles the window,
 * {@code ESC [ 2 J} clears the screen, and other sequences move the cursor to hide output or,
 * in some terminals, write to the clipboard. Every such string passes through
 * {@link #safe(String)} before it is printed as text.
 *
 * <p>JSON output does not need this: its string escaping already turns control characters
 * into {@code \}{@code u} escapes.
 */
public final class ConsoleText {

    /** Not instantiable: all methods are static. */
    private ConsoleText() {}

    /**
     * Replaces every control character - C0 (U+0000 to U+001F), DEL (U+007F) and C1
     * (U+0080 to U+009F) - with a visible {@code \xNN} escape, leaving all other text unchanged.
     *
     * <p>The escape keeps the character visible, so a file name containing one is still
     * recognisable, without letting the terminal act on it.
     *
     * @param text the text to print; {@code null} is treated as {@code ""}.
     * @return the text with control characters escaped; the same instance when there are none.
     */
    public static String safe(String text) {
        if (text == null) return "";
        int i = 0;
        while (i < text.length() && !isControl(text.charAt(i))) i++;
        if (i == text.length()) return text;

        StringBuilder sb = new StringBuilder(text.length() + 16);
        sb.append(text, 0, i);
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isControl(c)) {
                sb.append(String.format("\\x%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} for C0 controls, DEL and C1 controls.
     *
     * @param c the character to test.
     * @return whether {@code c} is a control character.
     */
    public static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }
}
