# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

POS Printer Agent for La Pasadita - A Java application that connects to a backend via WebSocket and sends receipt tickets to ESC/POS thermal printers. The system supports both cash register sales and delivery orders, and includes integration with Torrey PCR series scales.

## Build and Run Commands

### Build
```bash
mvn clean package
```
Generates `target/pos-printer-agent.jar` with all dependencies.

### Run
```bash
# With default config.properties in current directory
java -jar target/pos-printer-agent.jar

# With custom config file
java -jar target/pos-printer-agent.jar /path/to/config.properties

# Print test page
java -jar target/pos-printer-agent.jar --test
```

### Build Windows Installer
```bash
# En Windows, ejecutar:
build-installer.bat

# Genera: target/installer/POS Printer Agent-1.0.0.exe
```
El instalador incluye el Java runtime, el usuario no necesita instalar Java.

### Build Windows Service Package
```bash
# Desde Linux (Ubuntu):
./build-service-package.sh

# Desde Windows:
build-service-package.bat

# Genera: target/pos-printer-agent-service/
#   - pos-printer-agent.exe (WinSW wrapper)
#   - pos-printer-agent.jar
#   - pos-printer-agent.xml (config del servicio)
#   - config.properties
#   - install-service.bat
#   - uninstall-service.bat
```

Para instalar como servicio en Windows:
1. Copia la carpeta `pos-printer-agent-service` a la PC destino
2. Edita `config.properties` con la configuración correcta
3. Ejecuta `install-service.bat` como Administrador

El servicio:
- Inicia automáticamente con Windows
- Se reinicia automáticamente si falla
- Logs en `pos-printer-agent.out.log`

### Configuration
Configuration priority: Environment variables > `config.properties` > defaults

Key properties in `config.properties`:
- `server.url` - WebSocket server URL (e.g., `ws://localhost:8080/ws/printer`)
- `station.id` - Unique POS station identifier
- `printer.path` - Printer device path for Linux (`/dev/usb/lp0`)
- `printer.name` - Printer name for Windows (e.g., `EPSON TM-T88V Receipt`)
- `business.name`, `business.address`, `business.phone` - Business header info
- `scale.port` - Serial port for Torrey scale (`/dev/ttyACM0` on Linux, `COM1` on Windows)
- `scale.enabled` - Enable/disable scale REST server (true/false)
- `scale.autoConnect` - Auto-connect to scale on startup (true/false)
- `printer.stuckExitSeconds` - If one print/drawer job runs longer than this, the agent exits with code 3 so WinSW restarts it (default `120`, `0` disables)

## Architecture

### Core Components

**POSPrinterAgent** (`src/main/java/com/pasadita/pos/POSPrinterAgent.java`)
- Main class. Does **not** extend `WebSocketClient`: the inner `AgentSocket extends WebSocketClient` is a
  single-use client that delegates its callbacks to the agent, and **every connection attempt creates a new one**.
  Never call Java-WebSocket's `reconnect()` (throws `IllegalStateException` from the library's own threads) nor
  `connectBlocking(timeout)`: on timeout it, like `reconnect()`, goes through `reset()`, which waits on
  `closeBlocking()` with no timeout
- **Connection / reconnection** — everything runs on one scheduler thread (`ws-reconnect`), the only place that creates
  and connects clients, so two attempts never run in parallel:
  - `scheduleConnect` is guarded by `AtomicBoolean connectScheduled` (`compareAndSet`): at most one pending attempt.
    Before this guard, a failed connect (Java-WebSocket calls `onError` **and** `onClose(-1)`) scheduled two
    reconnections, doubling every 5 s; leftovers later tore down healthy connections
  - `connectAttempt` unlinks and discards the previous client (`close()` + closing its socket to abort a hung
    handshake), creates a client with a 10 s TCP connect timeout and `setConnectionLostTimeout(30)`, calls `connect()`
    and waits up to 20 s on its own latch (opened or closed). Wrapped in `catch (Throwable)` → reschedules
  - Backoff 5 → 10 → 20 → 30 s (cap), retries forever, reset on `onOpen`. `start()` schedules the first attempt with
    delay 0, so a boot without network (Windows service starting before the NIC) just keeps retrying
  - Only `onClose` schedules reconnections (`onError` only logs; the library always follows it with `onClose`).
    Safety net: `ensureConnected` every 30 s on the same thread schedules one if there's no open connection and no
    pending attempt
  - Callbacks from discarded clients are ignored by identity (`client != current`); a discarded client that opens
    late is closed
  - Connection-lost detection: Java-WebSocket closes with 1006 when no pong arrives within 1.5 × 30 s = 45 s (checked
    every 30 s → detected in 45–60 s). Pings from the backend (every 25 s) are answered automatically
- **Message processing**: the WebSocket thread only enqueues (`onMessage` → `messageWorker.execute`). A single-thread
  `ThreadPoolExecutor` (`print-worker`) processes tickets and `OPEN_DRAWER` in arrival order, so a slow printer never
  stops the socket from reading messages and answering pings
- **Watchdog** (`print-watchdog`): `runTimed` wraps every print/drawer job, logs start, end and duration, warns every
  15 s while it runs, and once it exceeds `printer.stuckExitSeconds` logs an error and calls `System.exit(3)` (backstop
  `Runtime.halt(3)` after 20 s) so WinSW restarts the service (`onfailure`). A hung job would otherwise block every
  later ticket and drawer command while the socket still looks healthy to the backend
- Sends `CONNECTED` on open and `PRINT_RESULT` after each ticket (printer errors travel in `error` with `success=false`)
- Starts the scale REST server if enabled

**ESCPOSPrinter** (`src/main/java/com/pasadita/pos/ESCPOSPrinter.java`)
- Generates ESC/POS commands for Epson TM-T88V thermal printers
- Auto-detects ticket type based on `deliveryOrderId` and `deliveryAddress`:
  - **Cash register ticket**: Standard sale receipt
  - **Delivery ticket**: Includes customer details, delivery address, payment status prominently displayed
- **Cross-platform printing**:
  - **Linux**: Writes directly to device file (`/dev/usb/lpX`) - no driver required
  - **Windows**: Uses Java PrintService with installed printer name - stable, no port changes
- Supports 80mm thermal printers (42 chars/line), PC850 charset for Spanish
- `openCashDrawer()`: opens the cash drawer standalone (no ticket), sending only the ESC/POS drawer-kick pulse (`{27,112,0,25,250}`) through the same cross-platform path as `print()`. Throws `PrinterException`; the agent logs the result (no confirmation message in the protocol)
- **Windows lookup per job** (`resolveWindowsPrintService`): before every job it refreshes the JDK's printer cache and
  looks the printer up by name — exact match (case-insensitive) first, then a partial match only if it is unique;
  otherwise a `PrinterException` lists the installed printers ("no encontrada" / "nombre ambiguo")
- **JDK printer cache** (`JdkPrintServiceCache`): on Windows `PrintServiceLookup` returns a cache refreshed only by a
  native `PrinterListener` thread that exits without retry if the spooler restarts or wasn't ready at JVM start,
  freezing the list until the JVM restarts. The agent calls the private
  `sun.print.PrintServiceLookupProvider.refreshServices()` via reflection, which needs
  `--add-opens java.desktop/sun.print=ALL-UNNAMED`: provided by the `Add-Opens` manifest entry (`java -jar`, used by
  WinSW) and by `--java-options` in `build-installer.*` (jpackage). If inaccessible it warns once and uses the plain lookup
- **Linux**: checks the device exists and is writable before writing, with explicit error messages
- All logging via SLF4J (no `System.out`)

**Scale Integration** (`src/main/java/com/pasadita/pos/scale/`)
- **TorreyScaleController**: Serial communication with Torrey PCR series scales via jSerialComm
  - Sends 'W' command to read weight
  - Parses response for weight value, unit (kg/g), and stability status
  - Configurable for 9600 baud, 8N1 settings
- **ScaleRestServer**: Lightweight HTTP server (no Spring Boot) on port 8081
  - GET `/api/scale/weight` - Read current weight
  - GET `/api/scale/status` - Connection status
  - POST `/api/scale/connect` - Connect to scale
  - POST `/api/scale/disconnect` - Disconnect from scale
  - GET `/api/scale/ports` - List available serial ports
  - GET `/api/station` - Returns this agent's configured stationId: `{"stationId":"POS1"}` (frontend caches it to tag sales)
  - CORS enabled for frontend integration

### Data Flow

1. Backend creates sale/delivery order and sends ticket JSON via WebSocket
2. `AgentSocket.onMessage()` (WebSocket thread) only enqueues the message on the `print-worker` thread
3. The worker reads the JSON tree first: if `type=OPEN_DRAWER` it calls `ESCPOSPrinter.openCashDrawer()` and returns; otherwise it deserializes `TicketDTO` with Jackson
4. `ESCPOSPrinter.print()` generates ESC/POS byte commands
5. Commands written to the printer device (Linux, e.g. `/dev/usb/lp0`) or to the Windows printer resolved by name
6. Confirmation JSON sent back to server with success/error status

### WebSocket Protocol

**Outgoing messages from agent:**
- `CONNECTED`: Sent on successful connection
  ```json
  {"type":"CONNECTED","stationId":"POS1","timestamp":"2025-01-12 14:30:00"}
  ```
- `PRINT_RESULT`: Sent after print attempt
  ```json
  {"type":"PRINT_RESULT","ticketId":123,"success":true,"stationId":"POS1","timestamp":"2025-01-12 14:30:00"}
  // With error:
  {"type":"PRINT_RESULT","ticketId":124,"success":false,"stationId":"POS1","timestamp":"...","error":"Printer not available"}
  ```

**Incoming messages to agent:**
- Full `TicketDTO` JSON object with `saleDetails` array (printed as a ticket)
- `OPEN_DRAWER`: kick the cash drawer without printing
  ```json
  {"type":"OPEN_DRAWER","timestamp":"2025-01-12T14:30:00Z"}
  ```
  Sent by the backend when a cash payment is collected but no ticket is printed. The worker inspects the `type` node before deserializing; messages with `type=OPEN_DRAWER` trigger `ESCPOSPrinter.openCashDrawer()`, anything else is parsed as a `TicketDTO`.

### Ticket Type Detection

The system automatically determines ticket type in `ESCPOSPrinter.isDeliveryOrder()`:
- **Delivery order**: `deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty()`
- **Cash register**: Otherwise

Delivery tickets emphasize customer name, phone, address, and payment status (PAGADO vs COBRAR AL ENTREGAR).

## Device Permissions (Linux)

Printer access requires:
```bash
# Add user to lp group
sudo usermod -a -G lp $USER
# Then logout/login

# Or grant direct permissions
sudo chmod 666 /dev/usb/lp0
```

Scale access requires:
```bash
# Add user to dialout group
sudo usermod -a -G dialout $USER
# Then logout/login
```

## Logging

- Provider: `slf4j-simple`, configured in `src/main/resources/simplelogger.properties`: everything goes to **stdout**
  with date/time (`yyyy-MM-dd HH:mm:ss.SSS`) and thread name; `org.java_websocket` at `warn`
- As a Windows service, logs land in `pos-printer-agent.out.log` next to the exe. WinSW rotates it by size
  (`service/pos-printer-agent.xml`: `roll-by-size`, 10240 KB per file, 5 files kept)
- Logged events: each connection attempt (`Intento de conexión #n`) and the next delay, connection established (attempt
  number, seconds offline), close (code, reason, `remota`), message received/enqueued and then processed (type,
  ticketId, time waited in queue), start/end/duration of each print and drawer opening, slow-job warnings every 15 s,
  `PRINT_RESULT` sent
- Thread names in the logs: `ws-reconnect`, `WebSocketConnectReadThread-N`, `print-worker`, `print-watchdog`

## Dependencies

- Java 17+
- Maven 3.6+
- Java-WebSocket 1.6.0 - WebSocket client
- Jackson 2.18.6 - JSON serialization/deserialization with JSR310 module for LocalDateTime
- jSerialComm 2.11.4 - Serial port communication for scale
- SLF4J 2.0.17 - Logging

## Environment Variables

Configuration can be set via environment variables (takes precedence over `config.properties`):

| Environment Variable | Property Key | Description |
|---------------------|--------------|-------------|
| `SERVER_URL` | `server.url` | WebSocket server URL |
| `STATION_ID` | `station.id` | POS station identifier |
| `PRINTER_PATH` | `printer.path` | Linux device path |
| `PRINTER_NAME` | `printer.name` | Windows printer name |
| `BUSINESS_NAME` | `business.name` | Business name for header |
| `BUSINESS_ADDRESS` | `business.address` | Business address |
| `BUSINESS_PHONE` | `business.phone` | Business phone |
| `SCALE_PORT` | `scale.port` | Serial port for scale |
| `SCALE_ENABLED` | `scale.enabled` | Enable scale REST server |
| `SCALE_AUTO_CONNECT` | `scale.autoConnect` | Auto-connect scale on startup |
| `PRINTER_STUCK_EXIT_SECONDS` | `printer.stuckExitSeconds` | Exit (code 3) when a print job hangs longer than this; `0` disables (default `120`) |

## Key Design Patterns

- **Auto-reconnection**: single scheduler thread, one pending attempt at most, a fresh client per attempt, backoff 5–30 s, retries forever (see POSPrinterAgent)
- **Ordered processing**: one worker thread for tickets and drawer commands; the WebSocket thread only enqueues
- **Self-healing**: a print job stuck past `printer.stuckExitSeconds` exits the process (code 3) for WinSW to restart it
- **Direct device I/O**: Printer accessed as file (`FileOutputStream`) for simplicity
- **Character encoding**: ISO-8859-1 for ESC/POS, PC850 charset for Spanish characters
- **Ticket width**: 42 characters for 80mm printers
- **Configuration cascade**: ENV vars override properties file, which overrides defaults
- **Shutdown hook**: Graceful cleanup on SIGTERM/SIGINT

## Project Structure

```
src/main/java/com/pasadita/pos/
├── POSPrinterAgent.java      # Main class: connection supervisor, message worker, watchdog
├── ESCPOSPrinter.java        # ESC/POS command generator
├── dto/
│   ├── TicketDTO.java        # Ticket data transfer object
│   └── SaleDetailDTO.java    # Sale line item DTO
└── scale/
    ├── TorreyScaleController.java  # Serial communication with scale
    └── ScaleRestServer.java        # HTTP REST API for scale
```