package com.webgrep.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the parts of {@link PlaywrightRenderer} that do not need a browser.
 */
public class PlaywrightRendererTest {

    @Test
    public void testRenderedTextSeparatesAdjacentElements() {
        // Framework output has no whitespace between elements. The text must still read as
        // separate words, so that phrases match and snippets are legible.
        String text = PlaywrightRenderer.renderedText("Title",
                "<h1>Hello</h1><p>World of zebras</p><ul><li>Apple</li><li>Banana</li></ul>", "desc", "kw");
        assertTrue(text, text.contains("Hello World of zebras Apple Banana"));
        assertTrue(text.startsWith("Title "));
        assertTrue(text.endsWith(" desc kw"));
        assertEquals(1, new MatchEngine().countMatches(text, "hello world", "default"));
    }

    @Test
    public void testRenderedTextKeepsInlineWordsWhole() {
        // Inline elements inside a word must not split it.
        String text = PlaywrightRenderer.renderedText("", "<p>re<b>new</b>able energy</p>", "", "");
        assertTrue(text, text.contains("renewable energy"));
    }

    @Test
    public void testBrowserFoldersPutWebGrepsOwnFolderFirst() {
        assertEquals(PlaywrightRenderer.browsersPath(), PlaywrightRenderer.browserDirs().get(0));
        if (System.getenv("PLAYWRIGHT_BROWSERS_PATH") == null) {
            assertTrue(PlaywrightRenderer.browsersPath().toString().contains("webgrep"));
            assertEquals("The shared cache is the fallback", 2, PlaywrightRenderer.browserDirs().size());
            assertTrue(PlaywrightRenderer.browserDirs().get(1).endsWith("ms-playwright"));
        }
    }
}
