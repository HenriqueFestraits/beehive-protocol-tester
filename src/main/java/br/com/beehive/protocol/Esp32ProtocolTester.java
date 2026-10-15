package br.com.beehive.protocol;

import com.fazecast.jSerialComm.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Automated client for the ESP32-C6 beehive monitoring USB serial protocol.
 *
 * Usage: mvn exec:java "-Dexec.mainClass=br.com.beehive.protocol.Esp32ProtocolTester" "-Dexec.args=COM5"
 */
public final class Esp32ProtocolTester {

    private static final int BAUD_RATE = 115_200;
    private static final long LINE_TIMEOUT_MS = 35_000;
    private static final long TRANSFER_TIMEOUT_MS = 60_000;
    private static final int MAX_LINE_LENGTH = 256;
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final int READ_BUFFER_SIZE = 4096;
    private static final Path OUTPUT_FILE = Path.of("measurements-received.bin");

    private final SerialPort port;

    private Esp32ProtocolTester(SerialPort port) {
        this.port = port;
    }

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: java Esp32ProtocolTester <COM_PORT>");
            System.err.println("Available serial ports:");
            for (SerialPort availablePort : SerialPort.getCommPorts()) {
                System.err.printf("  %s - %s%n",
                        availablePort.getSystemPortName(),
                        availablePort.getDescriptivePortName());
            }
            System.exit(2);
        }

        SerialPort serialPort = SerialPort.getCommPort(args[0].trim());
        serialPort.setBaudRate(BAUD_RATE);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        serialPort.setParity(SerialPort.NO_PARITY);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!serialPort.openPort()) {
            System.err.println("ERROR: Could not open serial port " + args[0]);
            System.err.println("Close Arduino Serial Monitor and other serial-terminal applications.");
            System.exit(1);
        }

        try {
            new Esp32ProtocolTester(serialPort).run();
        } catch (Exception exception) {
            System.err.println("TEST FAILED: " + exception.getMessage());
            System.exitCode = 1;
        } finally {
            if (serialPort.isOpen()) {
                serialPort.closePort();
            }
        }
    }

    private void run() throws IOException {
        System.out.printf("Connected to %s at %d baud.%n",
                port.getSystemPortName(), BAUD_RATE);
        System.out.println("Starting protocol test...");

        sendLine("HELLO");
        waitForExactLine("READY", LINE_TIMEOUT_MS);
        System.out.println("[OK] Handshake completed.");

        sendLine("GET_FILE");
        String fileInfo = waitForPrefix("FILE_INFO:", LINE_TIMEOUT_MS);
        FileInfo info = parseFileInfo(fileInfo);
        System.out.printf("[INFO] ESP32 reports %d bytes; CRC32=%08X%n",
                info.sizeBytes(), info.crc32());

        if (info.sizeBytes() < 0 || info.sizeBytes() > MAX_FILE_SIZE) {
            throw new IOException("Reported file size is outside the allowed range: "
                    + info.sizeBytes());
        }

        byte[] fileBytes = readExactBytes(info.sizeBytes(), TRANSFER_TIMEOUT_MS);
        long actualCrc32 = calculateCrc32(fileBytes);

        Files.write(OUTPUT_FILE, fileBytes);
        System.out.printf("[INFO] Saved received file to %s (%d bytes).%n",
                OUTPUT_FILE.toAbsolutePath(), fileBytes.length);
        System.out.printf("[INFO] Expected CRC32=%08X; calculated CRC32=%08X%n",
                info.crc32(), actualCrc32);

        if (actualCrc32 != info.crc32()) {
            throw new IOException(String.format(Locale.ROOT,
                    "CRC32 mismatch. Expected %08X but received %08X. "
                            + "The ESP32 was not sent a receipt confirmation.",
                    info.crc32(), actualCrc32));
        }
        System.out.println("[OK] File size and CRC32 verified.");

        sendLine(String.format(Locale.ROOT, "RECEIVED:%d:%08X",
                fileBytes.length, actualCrc32));
        waitForExactLine("TRANSFER_OK", LINE_TIMEOUT_MS);
        System.out.println("[OK] ESP32 accepted the transfer confirmation.");

        waitForExactLine("REQUEST_TIMESTAMP", LINE_TIMEOUT_MS);
        long timestamp = System.currentTimeMillis() / 1000L;
        sendLine(Long.toString(timestamp));
        System.out.println("[INFO] Sent Unix timestamp: " + timestamp);

        waitForExactLine("SESSION_COMPLETE", LINE_TIMEOUT_MS);
        System.out.println();
        System.out.println("SUCCESS: Protocol session completed.");
        System.out.println("The received measurements file is available at:");
        System.out.println(OUTPUT_FILE.toAbsolutePath());
        System.out.println("WARNING: The ESP32 measurement file should now contain only the new timestamp.");
    }

    private void sendLine(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long written = port.writeBytes(bytes, bytes.length);
        if (written != bytes.length) {
            throw new IOException("Could not write complete serial line: " + line);
        }
        System.out.println("[SEND] " + line);
    }

    private String waitForExactLine(String expected, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            String line = readLineUntil(deadline);
            if (line == null) {
                break;
            }
            if (line.equals(expected)) {
                System.out.println("[RECV] " + line);
                return line;
            }
            handleUnexpectedLine(line);
        }
        throw new IOException("Timed out waiting for '" + expected + "'.");
    }

    private String waitForPrefix(String prefix, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            String line = readLineUntil(deadline);
            if (line == null) {
                break;
            }
            if (line.startsWith(prefix)) {
                System.out.println("[RECV] " + line);
                return line;
            }
            handleUnexpectedLine(line);
        }
        throw new IOException("Timed out waiting for a line beginning with '" + prefix + "'.");
    }

    private void handleUnexpectedLine(String line) throws IOException {
        if (line.startsWith("ERROR:")) {
            throw new IOException("ESP32 reported " + line);
        }
        if (!line.isBlank()) {
            System.out.println("[LOG] " + line);
        }
    }

    /**
     * Reads one newline-terminated ASCII line without consuming bytes beyond its newline.
     * Returns null if the deadline expires before a line is complete.
     */
    private String readLineUntil(long deadlineNanos) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (System.nanoTime() < deadlineNanos) {
            int next = readByteUntil(deadlineNanos);
            if (next < 0) {
                return null;
            }
            if (next == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length,
                        java.nio.charset.StandardCharsets.US_ASCII).trim();
            }
            line.write(next);
            if (line.size() > MAX_LINE_LENGTH) {
                throw new IOException("Received serial line exceeds " + MAX_LINE_LENGTH + " bytes.");
            }
        }
        return null;
    }

    private byte[] readExactBytes(int expectedSize, long timeoutMs) throws IOException {
        byte[] result = new byte[expectedSize];
        int offset = 0;
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();

        while (offset < expectedSize && System.nanoTime() < deadline) {
            int available = port.bytesAvailable();
            if (available <= 0) {
                pauseBriefly();
                continue;
            }

            int requested = Math.min(Math.min(available, READ_BUFFER_SIZE), expectedSize - offset);
            byte[] buffer = new byte[requested];
            int count = (int) port.readBytes(buffer, requested);
            if (count > 0) {
                System.arraycopy(buffer, 0, result, offset, count);
                offset += count;
                if (offset == expectedSize || offset % (16 * 1024) < count) {
                    System.out.printf("\r[RECV] Binary payload: %d/%d bytes", offset, expectedSize);
                }
            }
        }

        if (expectedSize > 0) {
            System.out.println();
        }
        if (offset != expectedSize) {
            throw new IOException("Binary transfer timed out: received " + offset
                    + " of " + expectedSize + " bytes.");
        }
        return result;
    }

    private int readByteUntil(long deadlineNanos) throws IOException {
        while (System.nanoTime() < deadlineNanos) {
            if (port.bytesAvailable() > 0) {
                byte[] oneByte = new byte[1];
                long count = port.readBytes(oneByte, 1);
                if (count == 1) {
                    return Byte.toUnsignedInt(oneByte[0]);
                }
            } else {
                pauseBriefly();
            }
        }
        return -1;
    }

    private static FileInfo parseFileInfo(String line) throws IOException {
        String[] parts = line.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("FILE_INFO")) {
            throw new IOException("Invalid FILE_INFO response: " + line);
        }

        try {
            int size = Integer.parseInt(parts[1].trim());
            String crcText = parts[2].trim();
            if (crcText.startsWith("0x") || crcText.startsWith("0X")) {
                crcText = crcText.substring(2);
            }
            long crc = Long.parseUnsignedLong(crcText, 16);
            if (crc > 0xFFFF_FFFFL) {
                throw new NumberFormatException("CRC32 exceeds 32 bits");
            }
            return new FileInfo(size, crc);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid size or hexadecimal CRC32 in response: " + line, exception);
        }
    }

    private static long calculateCrc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    private static void pauseBriefly() throws IOException {
        try {
            Thread.sleep(2);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for serial data.", exception);
        }
    }

    private record FileInfo(int sizeBytes, long crc32) {
    }
}
