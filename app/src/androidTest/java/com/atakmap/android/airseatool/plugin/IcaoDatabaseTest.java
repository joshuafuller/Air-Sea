/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class IcaoDatabaseTest {

    private File testRoot;
    private final List<IcaoDatabase> databases = new ArrayList<>();

    @Before
    public void setUp() {
        testRoot = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),
                "icao-db-test-" + System.nanoTime());
        assertTrue(testRoot.mkdirs());
    }

    @After
    public void tearDown() {
        for (IcaoDatabase database : databases) database.shutdown();
        deleteRecursively(testRoot);
    }

    @Test
    public void testMalformedRowDoesNotStopFollowingRows() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        String validBefore = "{\"icao\":\"abc123\",\"model\":\"Before\",\"mil\":false}";
        String malformed = "{\"icao\":\"7c3c56\",\"model\":\"A-319\","
                + "\"ownop\":\"UAB " + "\\\\" + "\"AVIAAM B02" + "\\\\"
                + "\", SKYTRANS AUSTRALIA PTY LTD\",\"mil\":false}";
        String validAfter = "{\"icao\":\"def456\",\"ownop\":\"After\",\"mil\":true}";
        StringWriter output = new StringWriter();

        IcaoDatabase.ProcessResult result = process(database,
                validBefore + "\n" + malformed + "\n" + validAfter + "\n", output);

        assertEquals(2, result.recordCount);
        assertEquals(1, result.malformedCount);
        assertTrue(output.toString().contains("abc123\t"));
        assertTrue(output.toString().contains("def456\t"));
        assertFalse(output.toString().contains("7c3c56\t"));
    }

    @Test
    public void testBroadFormatChangeIsRejected() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 10_001; i++) input.append("not-json\n");
        input.append("{\"icao\":\"abc123\",\"model\":\"Valid\"}\n");

        try {
            process(database, input.toString(), new StringWriter());
            fail("Expected broad format change to be rejected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("possible format change"));
        }
    }

    @Test
    public void testValidRowsWithoutUsefulFieldsAreRejected() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        try {
            process(database, "{\"icao\":\"abc123\",\"mil\":false}\n",
                    new StringWriter());
            fail("Expected an empty useful database to be rejected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("no useful records"));
        }
    }

    @Test
    public void testRefreshUpdatesRecordsAndRemovesStaleEntries() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        writeDatabase(testRoot,
                "abc123\t\tOld Model\tOld Operator\tL1J\n"
                        + "def456\tY\tFighter\tMilitary\tL2J\n");
        assertEquals(2, database.loadFromDisk(2));
        assertNotNull(database.lookup("def456"));

        writeDatabase(testRoot, "abc123\t\tNew Model\tNew Operator\tL1J\n");
        assertEquals(1, database.loadFromDisk(1));

        assertEquals("New Model", database.lookup("abc123").model);
        assertNull(database.lookup("def456"));
    }

    @Test
    public void testInterruptedReplacementRestoresBackup() throws Exception {
        writeFile(new File(testRoot, "icao_db_old.tsv"),
                "abc123\tY\tRestored\tOperator\tL1J\n");

        IcaoDatabase database = newDatabase(testRoot);

        assertTrue(new File(testRoot, "icao_db.tsv").exists());
        assertFalse(new File(testRoot, "icao_db_old.tsv").exists());
        assertEquals(1, database.loadFromDisk(1));
        assertEquals("Restored", database.lookup("abc123").model);
    }

    @Test
    public void testMalformedTsvUnpublishesPartialRefresh() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        writeDatabase(testRoot, "abc123\t\tOriginal\tOperator\tL1J\n");
        assertEquals(1, database.loadFromDisk(1));

        writeDatabase(testRoot, "invalid\trow\n");
        try {
            database.loadFromDisk(1);
            fail("Expected malformed TSV load to fail");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Malformed ICAO TSV row"));
        }

        assertFalse(database.isAvailable());
        assertNull(database.lookup("abc123"));
    }

    @Test
    public void testLoadCountMismatchFails() throws Exception {
        IcaoDatabase database = newDatabase(testRoot);
        writeDatabase(testRoot, "abc123\t\tModel\tOperator\tL1J\n");

        try {
            database.loadFromDisk(2);
            fail("Expected load count mismatch to fail");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("load count mismatch"));
        }
        assertFalse(database.isAvailable());
    }

    @Test
    public void testShutdownRejectsNewUpdates() {
        IcaoDatabase database = newDatabase(testRoot);
        boolean[] called = {false};
        boolean[] success = {true};
        String[] message = {null};
        database.shutdown();

        database.downloadAndUpdate((ok, msg) -> {
            called[0] = true;
            success[0] = ok;
            message[0] = msg;
        });

        assertTrue(called[0]);
        assertFalse(success[0]);
        assertTrue(message[0].contains("shutting down"));
    }

    private IcaoDatabase newDatabase(File directory) {
        IcaoDatabase database = new IcaoDatabase(directory);
        databases.add(database);
        return database;
    }

    private static IcaoDatabase.ProcessResult process(IcaoDatabase database, String input,
                                                      StringWriter output) throws Exception {
        BufferedWriter writer = new BufferedWriter(output);
        IcaoDatabase.ProcessResult result = database.processJsonLines(
                new BufferedReader(new StringReader(input)), writer);
        writer.flush();
        return result;
    }

    private static void writeDatabase(File directory, String contents) throws Exception {
        writeFile(new File(directory, "icao_db.tsv"), contents);
    }

    private static void writeFile(File file, String contents) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(contents);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
