package com.webgrep;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class AppIntegrationTest {

    @Test
    public void testHelpOutput() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        Main.main(new String[]{"--help"});

        String output = outContent.toString();
        assertTrue(output.contains("Usage: java -jar WebGrep.jar"));
        assertTrue(output.contains("--url"));
        assertTrue(output.contains("--keyword"));

        System.setOut(System.out);
    }

    @Test
    public void testHelpShortFlag() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        Main.main(new String[]{"-h"});

        String output = outContent.toString();
        assertTrue(output.contains("Usage: java -jar WebGrep.jar"));
        System.setOut(System.out);
    }

    @Test
    public void testNoArgsShowsHelp() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        Main.main(new String[]{});

        String output = outContent.toString();
        assertTrue(output.contains("Usage: java -jar WebGrep.jar"));
        System.setOut(System.out);
    }
}
