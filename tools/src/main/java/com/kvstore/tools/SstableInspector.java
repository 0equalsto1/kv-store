package com.kvstore.tools;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.core.util.ByteArrayComparator;
import com.kvstore.io.MappedFileReader;
import com.kvstore.sstable.SstableHeader;
import com.kvstore.sstable.SstableHeaderCodec;
import com.kvstore.sstable.SstableIterator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Diagnostic tool for inspecting and verifying the integrity of SSTable files.
 * This tool can decode SSTable headers, perform full integrity checks by scanning
 * all records, and list key-value pairs stored in the file.
 * 
 * <p>The inspector provides deep visibility into the physical layout of an SSTable,
 * which is essential for debugging corruption issues or understanding the 
 * distribution of data on disk. It handles both printable and binary data
 * by providing hexadecimal fallback for non-printable characters.
 * </p>
 * 
 * <p>Usage from command line:
 * {@code java SstableInspector [options] <sstable-path>}
 * </p>
 * 
 * <p>Supported Options:
 * <ul>
 *   <li>{@code --verify}: Scans every record and validates internal checksums.</li>
 *   <li>{@code --list}: Prints all records (or a subset based on filters).</li>
 *   <li>{@code --keys-only}: When listing, only print the keys.</li>
 *   <li>{@code --offset <bytes>}: Start scanning from a specific byte offset.</li>
 *   <li>{@code --limit <bytes>}: Stop scanning after processing this many bytes.</li>
 *   <li>{@code --start-key <key>}: Filter records starting from this key (inclusive).</li>
 *   <li>{@code --end-key <key>}: Filter records ending at this key (inclusive).</li>
 * </ul>
 * </p>
 */
public class SstableInspector {
    /**
     * Main entry point for the SSTable Inspector tool.
     * <p>
     * Parses command line arguments, validates file existence, and dispatches
     * to specific inspection logic based on the provided flags.
     * </p>
     *
     * @param args Command line arguments. See class documentation for details.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        boolean verify = false;
        boolean list = false;
        boolean keysOnly = false;
        long offset = -1;
        long limit = -1;
        byte[] startKey = null;
        byte[] endKey = null;
        String pathStr = null;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--verify" -> verify = true;
                    case "--list" -> list = true;
                    case "--keys-only" -> keysOnly = true;
                    case "--offset" -> {
                        if (i + 1 < args.length) {
                            offset = Long.parseLong(args[++i]);
                        }
                    }
                    case "--limit" -> {
                        if (i + 1 < args.length) {
                            limit = Long.parseLong(args[++i]);
                        }
                    }
                    case "--start-key" -> {
                        if (i + 1 < args.length) {
                            startKey = args[++i].getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    case "--end-key" -> {
                        if (i + 1 < args.length) {
                            endKey = args[++i].getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    default -> {
                        if (!args[i].startsWith("-")) {
                            pathStr = args[i];
                        } else {
                            System.err.println("Warning: Unrecognized option: " + args[i]);
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid numeric value provided in arguments.");
            return;
        }

        if (pathStr == null) {
            System.err.println("Error: No SSTable path provided.");
            printUsage();
            return;
        }

        Path path = Paths.get(pathStr);
        if (!Files.exists(path)) {
            System.err.println("Error: File does not exist: " + path);
            return;
        }

        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (Exception e) {
            System.err.println("Error determining file size: " + e.getMessage());
            return;
        }

        if (fileSize < SstableHeader.HEADER_SIZE) {
            System.err.println("Error: File is too small to be a valid SSTable (size: " + fileSize + " bytes).");
            return;
        }

        if (offset != -1 && offset >= fileSize) {
            System.err.println("Error: Start offset (" + offset + ") is beyond file size (" + fileSize + ").");
            return;
        }

        // Initialize budget based on file size + overhead (1MB).
        // The tool uses memory-mapped I/O via MappedFileReader.
        MemoryBudget budget = new MemoryBudget(fileSize + (1024 * 1024));

        try (MappedFileReader reader = new MappedFileReader(path, budget, "tools")) {
            // Decodes the fixed-size header from the start of the file.
            SstableHeader header = SstableHeaderCodec.decode(reader.getSegment(), 0);

            if (header.magic() != SstableHeader.MAGIC) {
                System.err.println("Warning: Invalid magic bytes. This may not be a valid SSTable.");
            }

            System.out.println("--- SSTable Header ---");
            System.out.printf("Magic: 0x%x%n", header.magic());
            System.out.printf("Version: %d%n", header.version());
            System.out.printf("Key Count: %d%n", header.keyCount());
            System.out.printf("Index Offset: %d%n", header.indexOffset());
            System.out.printf("Data Offset: %d%n", header.dataOffset());
            System.out.printf("Created Time: %s (%d)%n", Instant.ofEpochMilli(header.createdTime()), header.createdTime());
            System.out.printf("Checksum: 0x%x%n", header.checksum());

            if (verify) {
                performIntegrityCheck(reader, header);
            }

            if (list) {
                performList(reader, header, offset, limit, keysOnly, startKey, endKey);
            }

        } catch (Exception e) {
            System.err.println("Error inspecting SSTable: " + e.getMessage());
            if (e instanceof StorageException se && se.getErrorCode() == StorageErrorCode.CHECKSUM_MISMATCH) {
                // SstableIterator already includes offset in message for checksum mismatch.
            } else {
                e.printStackTrace();
            }
        }
    }

    /**
     * Performs a full integrity check of the SSTable by scanning all records
     * and verifying their internal checksums.
     * <p>
     * This method uses the {@link SstableIterator} to traverse the file from the
     * start of the data section to the end of the index section. Every record
     * encountered is fully decoded and its checksum is verified.
     * </p>
     *
     * @param reader The file reader mapping the SSTable. Must not be null.
     * @param header The decoded SSTable header. Must not be null.
     */
    private static void performIntegrityCheck(MappedFileReader reader, SstableHeader header) {
        System.out.println("\n--- Integrity Check ---");
        long recordsScanned = 0;
        boolean success = true;

        try (SstableIterator it = new SstableIterator(reader.getSegment(), header.dataOffset(), header.indexOffset())) {
            while (it.hasNext()) {
                it.next();
                recordsScanned++;
                if (recordsScanned % 1000 == 0) {
                    System.out.printf("Scanned %d records...%n", recordsScanned);
                }
            }
        } catch (Exception e) {
            System.err.println("Corruption detected: " + e.getMessage());
            success = false;
        }

        if (success) {
            System.out.println("Integrity Check: PASSED");
            System.out.printf("Records scanned: %d%n", recordsScanned);
        } else {
            System.err.println("Integrity Check: FAILED");
        }
    }

    /**
     * Lists records stored in the SSTable based on provided filters.
     * <p>
     * The scan range is determined by {@code offset} and {@code limit}.
     * Records are further filtered logically by {@code startKey} and {@code endKey}.
     * Since SSTables are sorted by key, the scan terminates immediately if a
     * record's key exceeds {@code endKey}.
     * </p>
     *
     * @param reader    The file reader mapping the SSTable. Must not be null.
     * @param header    The decoded SSTable header. Must not be null.
     * @param offset    Byte offset to start scanning from (-1 for default data offset).
     * @param limit     Maximum number of bytes to scan (-1 for scan until end of data).
     * @param keysOnly  If {@code true}, only keys are printed; otherwise, key-value pairs are shown.
     * @param startKey  Starting key for range filter (inclusive, null for none).
     * @param endKey    Ending key for range filter (inclusive, null for none).
     */
    private static void performList(MappedFileReader reader, SstableHeader header, long offset, long limit, boolean keysOnly, byte[] startKey, byte[] endKey) {
        System.out.println("\n--- Data Listing ---");
        long startPos = (offset != -1) ? offset : header.dataOffset();
        long limitPos = (limit != -1) ? Math.min(header.indexOffset(), startPos + limit) : header.indexOffset();

        long recordsListed = 0;
        try (SstableIterator it = new SstableIterator(reader.getSegment(), startPos, limitPos)) {
            while (it.hasNext()) {
                SstableIterator.Record record = it.next();

                // Logical filtering by key.
                if (startKey != null && ByteArrayComparator.INSTANCE.compare(record.key(), startKey) < 0) {
                    continue;
                }
                if (endKey != null && ByteArrayComparator.INSTANCE.compare(record.key(), endKey) > 0) {
                    // Optimization: stop scanning since SSTables are sorted.
                    break;
                }

                String keyStr = formatBytes(record.key());
                if (keysOnly) {
                    System.out.println(keyStr);
                } else {
                    String valStr = (record.type() == 2) ? "[TOMBSTONE]" : formatBytes(record.value());
                    System.out.printf("%s => %s%n", keyStr, valStr);
                }
                recordsListed++;
            }
        } catch (Exception e) {
            System.err.println("Error during listing: " + e.getMessage());
        }
        System.out.printf("Total records listed: %d%n", recordsListed);
    }

    /**
     * Formats a byte array as a string, using UTF-8 if printable, otherwise hex.
     * <p>
     * This ensures that binary keys or values don't garble the console output.
     * </p>
     *
     * @param bytes The byte array to format.
     * @return A string representation of the bytes (either plain text or 0x... hex).
     */
    private static String formatBytes(byte[] bytes) {
        if (isPrintable(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "0x" + bytesToHex(bytes);
        }
    }

    /**
     * Checks if a byte array contains only printable characters.
     * <p>
     * A heuristic that considers characters between ASCII 32 and 126, 
     * plus common whitespace (TAB, LF, CR), as printable. It also attempts
     * to decode as UTF-8 for non-ASCII characters.
     * </p>
     *
     * @param bytes The byte array to check.
     * @return {@code true} if printable, {@code false} otherwise.
     */
    private static boolean isPrintable(byte[] bytes) {
        for (byte b : bytes) {
            // Allow standard ASCII and typical UTF-8 printable range.
            if ((b < 32 && b != 9 && b != 10 && b != 13) || (b & 0xFF) > 126) {
                // If it's outside ASCII, we check for valid UTF-8.
                try {
                    StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Converts a byte array to a hex string.
     *
     * @param bytes The byte array to convert. Must not be null.
     * @return A lowercase hex string representation (e.g., "0a1b2c").
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789abcdef".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Prints usage information to the console.
     * <p>
     * Lists all supported command line arguments and their purpose.
     * </p>
     */
    private static void printUsage() {
        System.out.println("SSTable Inspector");
        System.out.println("Usage: java SstableInspector [options] <sstable-path>");
        System.out.println("Options:");
        System.out.println("  --verify           Perform full integrity check by scanning all records");
        System.out.println("  --list             List key-value pairs");
        System.out.println("  --keys-only        Only print keys when listing");
        System.out.println("  --offset <bytes>   Start listing from this byte offset (default: data offset)");
        System.out.println("  --limit <bytes>    Limit the scan to this many bytes from start offset");
        System.out.println("  --start-key <key>  Start listing from this key (inclusive)");
        System.out.println("  --end-key <key>    Stop listing at this key (inclusive)");
    }
}
