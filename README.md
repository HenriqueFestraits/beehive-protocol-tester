# Beehive Protocol Tester

Java/Maven client used to test the USB Serial file-transfer protocol implemented by the ESP32-C6 beehive-monitoring firmware.

The tester connects to the ESP32, performs the handshake, requests the measurements file, validates its size and CRC-32, saves a local copy, confirms receipt, sends a new Unix timestamp, and waits for the session-complete response.

## Requirements

- JDK 21 or newer
- Apache Maven
- Seeed Studio XIAO ESP32-C6 connected over USB
- The matching beehive-monitoring firmware flashed to the board

## Build

From the repository root:

```powershell
mvn clean package
```

## Run

First, identify the ESP32 COM port in Windows Device Manager or Arduino IDE. Close the Arduino Serial Monitor before running the tester, because only one application can normally own the serial port at a time.

For example, if the board is on COM5:

```powershell
mvn exec:java "-Dexec.mainClass=br.com.beehive.protocol.Esp32ProtocolTester" "-Dexec.args=COM5"
```

You can pass another port as the first argument. If no port is supplied, the program lists the available serial ports and exits.

## Protocol sequence

1. Client sends `HELLO\n`; ESP32 replies `READY\n`.
2. Client sends `GET_FILE\n`.
3. ESP32 sends `FILE_INFO:<size_bytes>:<crc32>\n`, followed by exactly `size_bytes` binary bytes.
4. Client checks the number of bytes and CRC-32/ISO-HDLC, then saves the file as `measurements-received.bin`.
5. Client sends `RECEIVED:<size_bytes>:<CRC32_UPPERCASE_8_HEX_DIGITS>\n`.
6. ESP32 replies `TRANSFER_OK\n`, then `REQUEST_TIMESTAMP\n`.
7. Client sends the current Unix timestamp in seconds as decimal digits followed by a newline (no `TIMESTAMP:` prefix).
8. Client waits for `SESSION_COMPLETE\n`.

The tester ignores unrelated text log lines while waiting for protocol messages. Firmware debug text must not be emitted between `FILE_INFO` and the end of the binary payload, because those bytes are treated as file contents.

## Important: this test changes the ESP32 file

A successful session causes the firmware to replace the ESP32's current measurements file with a new file containing only the newly received 8-byte timestamp. The old measurements are saved locally as `measurements-received.bin`. Back up any data you need before running the test.

## Output

- `measurements-received.bin`: exact binary file received from the ESP32.
- Console output: protocol progress, received size, expected and actual CRC-32, and final result.

## Troubleshooting

- **Port cannot be opened:** close Arduino Serial Monitor and other serial-terminal applications; verify the COM port.
- **No READY response:** confirm firmware is running and the USB CDC serial configuration is enabled.
- **CRC mismatch:** make sure firmware logs do not interrupt the binary transfer and that both sides use CRC-32/ISO-HDLC (reflected polynomial `0xEDB88320`).
- **Timeout:** reset the board and try again. Check that the firmware and tester implement the same protocol version.
