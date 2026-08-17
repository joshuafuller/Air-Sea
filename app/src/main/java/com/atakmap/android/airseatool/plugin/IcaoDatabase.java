/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.util.JsonReader;
import android.util.JsonToken;
import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Manages a local trimmed copy of the ADS-B Exchange basic aircraft database.
 *
 * <p>Download: https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz
 *
 * <p>The full database is downloaded, decompressed, and trimmed to only
 * ICAO, MIL, MODEL, OWNOP, SHORT_TYPE fields, then stored as a compact
 * tab-separated file for fast loading. Lookups are O(1) via a HashMap.
 *
 * <p>Thread-safe: the in-memory map is replaced atomically after each load.
 */
public class IcaoDatabase {

    private static final String TAG = "IcaoDatabase";
    private static final String DB_FILENAME  = "icao_db.tsv";
    private static final String TMP_FILENAME = "icao_db_new.tsv";
    private static final String DL_FILENAME  = "icao_db_dl.gz";
    private static final String BAK_FILENAME = "icao_db_old.tsv";
    private static final int MAX_LOGGED_MALFORMED_ROWS = 100;
    private static final int FORMAT_CHANGE_MIN_MALFORMED_ROWS = 10_000;
    private static final int FORMAT_CHANGE_MALFORMED_PERCENT = 90;
    private static final String DB_URL =
            "https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz";

    /** Called when a download+rebuild completes (on a background thread). */
    public interface UpdateCallback {
        void onComplete(boolean success, String message);
    }

    /**
     * Called on a background thread to report download/parse progress.
     * @param stage  Human-readable stage label.
     * @param percent 0-100 during download, -1 for indeterminate stages.
     */
    public interface ProgressCallback {
        void onProgress(String stage, int percent);
    }

    private final File dbDir;
    /** Volatile so reads from the main thread always see the latest map. */
    private volatile Map<String, IcaoRecord> records = null;
    /** Prevents lookups from observing an in-place database refresh. */
    private final Object recordsLock = new Object();
    /** Identifies records seen during the latest in-place refresh. */
    private int loadGeneration = 0;
    /** Serializes update/load startup and worker lifecycle transitions. */
    private final Object operationLock = new Object();
    /** True while a background download+parse is in progress. */
    private volatile boolean downloading = false;
    /** True while the on-disk database is being loaded outside an update. */
    private volatile boolean loading = false;
    /** Set to true by cancelDownload() to abort an in-progress operation. */
    private volatile boolean cancelled = false;
    /** Set by shutdown() so this instance cannot start new work. */
    private volatile boolean closed = false;
    /** The active HTTP connection, held so cancelDownload() can disconnect it. */
    private volatile HttpURLConnection activeConn = null;
    private Thread updateThread = null;
    private Thread loadThread = null;

    public IcaoDatabase(File dbDir) {
        this.dbDir = dbDir;
        dbDir.mkdirs();
        recoverInterruptedReplacement();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns the record for the given ICAO hex address, or null if not found. */
    public IcaoRecord lookup(String icao) {
        if (icao == null || icao.isEmpty()) return null;
        synchronized (recordsLock) {
            Map<String, IcaoRecord> r = records;
            if (r == null) return null;
            return r.get(icao.toLowerCase());
        }
    }

    /** True once the database has been loaded into memory. */
    public boolean isAvailable() {
        synchronized (recordsLock) {
            Map<String, IcaoRecord> r = records;
            return r != null && !r.isEmpty();
        }
    }

    /** True if the trimmed TSV file exists on disk. */
    public boolean isFilePresent() {
        return getDbFile().exists();
    }

    /** True while a download is already running — callers should not start another. */
    public boolean isDownloading() {
        return downloading;
    }

    /** Cancels an in-progress download. The update callback will be called with success=false. */
    public void cancelDownload() {
        HttpURLConnection conn;
        Thread thread;
        synchronized (operationLock) {
            cancelled = true;
            conn = activeConn;
            thread = updateThread;
        }
        if (conn != null) conn.disconnect();
        if (thread != null) thread.interrupt();
    }

    /** Stops all background work and prevents this instance from being reused. */
    public void shutdown() {
        HttpURLConnection conn;
        Thread update;
        Thread load;
        synchronized (operationLock) {
            if (closed) return;
            closed = true;
            cancelled = true;
            conn = activeConn;
            update = updateThread;
            load = loadThread;
        }
        if (conn != null) conn.disconnect();
        stopThread(update);
        stopThread(load);
    }

    /** Last-modified timestamp of the local TSV file, or 0 if not present. */
    public long getLastUpdatedMs() {
        File f = getDbFile();
        return f.exists() ? f.lastModified() : 0L;
    }

    /**
     * Loads the database from disk into memory on a background thread.
     * Safe to call multiple times; subsequent calls are a no-op if already loaded.
     */
    public void loadAsync() {
        synchronized (operationLock) {
            if (closed || loading || downloading || records != null) return;
            loading = true;
            loadThread = new Thread(() -> {
                try {
                    loadFromDisk(-1);
                } catch (Exception e) {
                    if (!closed) Log.e(TAG, "Failed to load ICAO database from disk", e);
                } finally {
                    synchronized (operationLock) {
                        loading = false;
                        if (loadThread == Thread.currentThread()) loadThread = null;
                    }
                }
            }, "ICAO-DB-Load");
            loadThread.start();
        }
    }

    /**
     * Downloads the full database from ADS-B Exchange, trims it to the
     * five required fields, saves it locally, then loads it into memory.
     * Both callbacks are invoked on the background thread.
     */
    public void downloadAndUpdate(UpdateCallback callback) {
        downloadAndUpdate(callback, null);
    }

    /**
     * Same as {@link #downloadAndUpdate(UpdateCallback)} but also reports
     * progress via {@code progress} during the download and parse stages.
     */
    public void downloadAndUpdate(UpdateCallback callback, ProgressCallback progress) {
        String rejection = null;
        synchronized (operationLock) {
            if (closed) {
                rejection = "ICAO database is shutting down";
            } else if (downloading) {
                rejection = "Download already in progress";
            } else if (loading) {
                rejection = "Database load already in progress";
            } else {
                downloading = true;
                cancelled = false;
                updateThread = new Thread(() -> {
                    try {
                        ProcessResult result = downloadAndProcess(progress);
                        if (progress != null)
                            progress.onProgress("Loading into memory\u2026", -1);
                        int loadedCount = loadFromDisk(result.recordCount);
                        String msg = "Loaded " + loadedCount + " records; skipped "
                                + result.malformedCount + " malformed "
                                + (result.malformedCount == 1 ? "row" : "rows");
                        Log.i(TAG, "ICAO DB update complete: " + msg);
                        if (callback != null && !closed) callback.onComplete(true, msg);
                    } catch (Exception e) {
                        String message = cancelled ? "Download cancelled" : e.getMessage();
                        if (!cancelled) Log.e(TAG, "ICAO DB update failed", e);
                        if (callback != null && !closed) callback.onComplete(false, message);
                    } finally {
                        synchronized (operationLock) {
                            downloading = false;
                            if (updateThread == Thread.currentThread()) updateThread = null;
                        }
                    }
                }, "ICAO-DB-Download");
                updateThread.start();
            }
        }
        if (rejection != null && callback != null)
            callback.onComplete(false, rejection);
    }

    /** Deletes the local database file and clears the in-memory records. */
    public void deleteDatabase() {
        getDbFile().delete();
        synchronized (recordsLock) {
            records = null;
        }
        Log.i(TAG, "ICAO DB deleted");
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private File getDbFile() {
        return new File(dbDir, DB_FILENAME);
    }

    /** Restores the previous database if the process stopped during file replacement. */
    private void recoverInterruptedReplacement() {
        File dbFile = getDbFile();
        File backupFile = new File(dbDir, BAK_FILENAME);
        if (!backupFile.exists()) return;
        if (dbFile.exists()) {
            if (!backupFile.delete())
                Log.w(TAG, "Failed to remove stale ICAO DB backup");
        } else if (!backupFile.renameTo(dbFile)) {
            Log.e(TAG, "Failed to restore ICAO DB backup");
        }
    }

    /** Loads the local TSV file, reusing the existing map during an update to limit peak memory. */
    int loadFromDisk(int expectedCount) throws Exception {
        File f = getDbFile();
        if (!f.exists())
            throw new Exception("ICAO DB file not found: " + f.getAbsolutePath());
        synchronized (recordsLock) {
            Map<String, IcaoRecord> target = records;
            boolean refreshing = target != null;
            if (!refreshing) target = new HashMap<>();
            int generation = ++loadGeneration;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                int loadedRows = 0;
                int lineNumber = 0;
                while ((line = br.readLine()) != null) {
                    checkCancelled();
                    lineNumber++;
                    // Format: icao \t mil \t model \t ownop \t short_type
                    String[] p = line.split("\t", -1);
                    if (p.length != 5 || p[0].isEmpty())
                        throw new Exception("Malformed ICAO TSV row " + lineNumber);
                    IcaoRecord record = new IcaoRecord(
                            "Y".equals(p[1]), p[2], p[3], p[4]);
                    record.loadGeneration = generation;
                    target.put(p[0], record);
                    loadedRows++;
                }
                if (refreshing) {
                    Iterator<Map.Entry<String, IcaoRecord>> it =
                            target.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue().loadGeneration != generation) it.remove();
                    }
                }
                if (target.isEmpty())
                    throw new Exception("ICAO database contained no records");
                if (expectedCount >= 0 && target.size() != expectedCount)
                    throw new Exception("ICAO database load count mismatch: expected "
                            + expectedCount + " records but loaded " + target.size());
                records = target;
                Log.i(TAG, "ICAO DB loaded: " + target.size()
                        + " records from " + f.getName());
                return target.size();
            } catch (OutOfMemoryError e) {
                records = null;
                target.clear();
                throw new Exception("Insufficient memory to load ICAO database", e);
            } catch (Exception e) {
                records = null;
                target.clear();
                throw e;
            }
        }
    }

    private void checkCancelled() throws Exception {
        if (cancelled || closed || Thread.currentThread().isInterrupted())
            throw new Exception("Download cancelled");
    }

    private static void stopThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive())
            Log.w(TAG, "ICAO database worker did not stop within 3 seconds: "
                    + thread.getName());
    }

    /**
     * Downloads the .gz file, streams it through a JSON parser, and writes
     * the trimmed TSV to a temporary file before atomically renaming it.
     *
     * @return processing counts for records written and malformed rows skipped
     */
    private ProcessResult downloadAndProcess(ProgressCallback progress) throws Exception {
        File dlFile  = new File(dbDir, DL_FILENAME);
        File tmpFile = new File(dbDir, TMP_FILENAME);
        File dbFile  = getDbFile();

        // ── Step 1: download ───────────────────────────────────────────────
        checkCancelled();
        Log.i(TAG, "ICAO DB: downloading from " + DB_URL);
        if (progress != null) progress.onProgress("Connecting\u2026", 0);
        URL url = new URL(DB_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);  // large file; allow 5 min
        conn.setRequestProperty("User-Agent", "AirSeaTool/1.2.0");
        try {
            checkCancelled();
            int code = conn.getResponseCode();
            checkCancelled();
            if (code != 200)
                throw new Exception("HTTP " + code + " from ICAO database server");
            long totalBytes = conn.getContentLengthLong();
            long readBytes = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(dlFile)) {
                byte[] buf = new byte[65536];
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    checkCancelled();
                    fos.write(buf, 0, n);
                    readBytes += n;
                    if (progress != null && totalBytes > 0) {
                        int pct = (int) (readBytes * 100L / totalBytes);
                        if (pct != lastPct) {
                            progress.onProgress("Downloading\u2026", pct);
                            lastPct = pct;
                        }
                    }
                }
            }
            if (totalBytes > 0 && readBytes != totalBytes)
                throw new Exception("Incomplete ICAO database download: received "
                        + readBytes + " of " + totalBytes + " bytes");
        } finally {
            conn.disconnect();
            activeConn = null;
        }
        Log.i(TAG, "ICAO DB: download complete (" + dlFile.length() + " bytes compressed)");
        if (progress != null) progress.onProgress("Parsing records\u2026", -1);

        // ── Step 2: parse JSON and write trimmed TSV ───────────────────────
        // ADS-B Exchange publishes JSONL. Parse each row independently so malformed
        // source data cannot corrupt the parser state for subsequent records.
        ProcessResult result;
        try (GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(dlFile));
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            result = processJsonLines(input, writer);
        }
        return installProcessedDatabase(dlFile, tmpFile, dbFile, result);
    }

    ProcessResult processJsonLines(BufferedReader input, BufferedWriter writer) throws Exception {
        int count = 0;
        int sourceRows = 0;
        int malformedRows = 0;
        String line;
        while ((line = input.readLine()) != null) {
            checkCancelled();
            if (line.trim().isEmpty()) continue;
            sourceRows++;
            try (JsonReader reader = new JsonReader(new StringReader(line))) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT)
                    throw new Exception("Expected an aircraft JSON object");
                String[] h = {null};
                IcaoRecord rec = parseRecord(reader, h);
                if (reader.peek() != JsonToken.END_DOCUMENT)
                    throw new Exception("Unexpected content after aircraft object");
                if (rec != null && h[0] != null && hasUsefulData(rec)) {
                    writeRecord(writer, h[0], rec);
                    count++;
                }
            } catch (Exception e) {
                malformedRows++;
                if (malformedRows <= MAX_LOGGED_MALFORMED_ROWS) {
                    Log.w(TAG, "Skipping malformed ICAO DB row " + sourceRows + ": "
                            + e.getMessage() + "; row=" + line);
                }
            }
        }
        if (malformedRows > MAX_LOGGED_MALFORMED_ROWS) {
            Log.w(TAG, "ICAO DB: suppressed full logging for "
                    + (malformedRows - MAX_LOGGED_MALFORMED_ROWS)
                    + " additional malformed rows");
        }
        Log.i(TAG, "ICAO DB: parsed " + count + " useful records from " + sourceRows
                + " source rows; skipped " + malformedRows + " malformed rows");

        if (malformedRows >= FORMAT_CHANGE_MIN_MALFORMED_ROWS
                && malformedRows * 100L > sourceRows * FORMAT_CHANGE_MALFORMED_PERCENT) {
            throw new Exception("Rejected ICAO database: " + malformedRows + " of "
                    + sourceRows + " rows were malformed (possible format change)");
        }
        if (count == 0)
            throw new Exception("Rejected ICAO database: no useful records were found");

        return new ProcessResult(count, malformedRows);
    }

    private ProcessResult installProcessedDatabase(File dlFile, File tmpFile, File dbFile,
                                                    ProcessResult result) throws Exception {
        // ── Step 3: guarded replacement ────────────────────────────────────
        dlFile.delete();
        File backupFile = new File(dbDir, BAK_FILENAME);
        if (backupFile.exists() && !backupFile.delete())
            throw new Exception("Failed to remove old ICAO DB backup");
        boolean hadDatabase = dbFile.exists();
        if (hadDatabase && !dbFile.renameTo(backupFile))
            throw new Exception("Failed to preserve existing ICAO database");
        if (!tmpFile.renameTo(dbFile)) {
            if (hadDatabase && !backupFile.renameTo(dbFile))
                Log.e(TAG, "Failed to restore existing ICAO database after replacement failure");
            throw new Exception("Failed to rename temp DB file to " + dbFile.getName());
        }
        if (backupFile.exists() && !backupFile.delete())
            Log.w(TAG, "Failed to remove ICAO DB backup after successful update");

        return result;
    }

    /**
     * Parses a single aircraft JSON object.
     * If {@code icaoOut} is non-null, populates {@code icaoOut[0]} with the "icao" field value.
     */
    private static IcaoRecord parseRecord(JsonReader reader, String[] icaoOut) throws Exception {
        String icao      = null;
        String model     = "";
        String ownop     = "";
        String shortType = "";
        boolean isMil    = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            JsonToken valToken = reader.peek();
            if (valToken == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (name) {
                case "icao":
                    icao = (valToken == JsonToken.STRING)
                            ? reader.nextString().toLowerCase() : null;
                    if (icao == null) reader.skipValue();
                    break;
                case "mil":
                    // Stored as "Y"/""/bool/int depending on database version
                    if (valToken == JsonToken.BOOLEAN) {
                        isMil = reader.nextBoolean();
                    } else if (valToken == JsonToken.STRING) {
                        String v = reader.nextString();
                        isMil = "Y".equalsIgnoreCase(v) || "YES".equalsIgnoreCase(v)
                                || "true".equalsIgnoreCase(v) || "1".equals(v);
                    } else if (valToken == JsonToken.NUMBER) {
                        isMil = reader.nextInt() != 0;
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "model":
                    if (valToken == JsonToken.STRING) model = reader.nextString();
                    else reader.skipValue();
                    break;
                case "ownop":
                    if (valToken == JsonToken.STRING) ownop = reader.nextString();
                    else reader.skipValue();
                    break;
                case "short_type":
                    if (valToken == JsonToken.STRING) shortType = reader.nextString();
                    else reader.skipValue();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        if (icaoOut != null) icaoOut[0] = icao;
        return new IcaoRecord(isMil,
                model     != null ? model     : "",
                ownop     != null ? ownop     : "",
                shortType != null ? shortType : "");
    }

    /** A record is worth keeping if it has military status, model, ownop, or short_type. */
    private static boolean hasUsefulData(IcaoRecord rec) {
        return rec.mil || !rec.model.isEmpty() || !rec.ownop.isEmpty() || !rec.shortType.isEmpty();
    }

    /** Writes one TSV record: icao \t mil \t model \t ownop \t short_type */
    private static void writeRecord(BufferedWriter writer, String icao, IcaoRecord rec)
            throws Exception {
        writer.write(icao);
        writer.write('\t');
        writer.write(rec.mil ? "Y" : "");
        writer.write('\t');
        writer.write(sanitize(rec.model));
        writer.write('\t');
        writer.write(sanitize(rec.ownop));
        writer.write('\t');
        writer.write(sanitize(rec.shortType));
        writer.newLine();
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static final class ProcessResult {
        final int recordCount;
        final int malformedCount;

        ProcessResult(int recordCount, int malformedCount) {
            this.recordCount = recordCount;
            this.malformedCount = malformedCount;
        }
    }

}
