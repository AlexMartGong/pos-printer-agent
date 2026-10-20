# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

POS Printer Agent for La Pasadita - A Java 17 application that connects to a backend via WebSocket and sends receipt
tickets to ESC/POS thermal printers. The system supports both cash register sales and delivery orders, can open the
cash drawer without printing, and exposes a local REST API (port 8081) for a Torrey PCR series scale and the station ID.

Code identifiers are in English; comments, log messages, ticket text and the build/service scripts are in Spanish. Keep
that split when adding code.

## Build and Run Commands

### Build
```bash
mvn clean package
# or with the Maven wrapper
./mvnw clean package
```
Generates `target/pos-printer-agent.jar` with all dependencies (its manifest carries `Main-Class` and
`Add-Opens: java.desktop/sun.print`). `target/pos-printer-agent-1.0.0.jar` is the thin jar without dependencies: don't
ship it.

There are no automated tests (`src/test` doesn't exist). Verify changes by building and, with hardware, by printing the
test page or running against a backend.

### Run
```bash
# With default config.properties in current directory
java -jar target/pos-printer-agent.jar

# With custom config file
java -jar target/pos-printer-agent.jar /path/to/config.properties

# Print test page and exit (also kicks the cash drawer)
java -jar target/pos-printer-agent.jar --test
java -jar target/pos-printer-agent.jar /path/to/config.properties --test
```
`--test` (or `-t`) can go in any position. The config file is the first argument that isn't `--test`/`-t`
(default `config.properties` in the working directory); further arguments are ignored. A missing file is logged by
name and the agent runs with ENV/defaults.

### Build Installer (jpackage)
```bash
# En Windows, ejecutar:
build-installer.bat
# Genera: target/installer/POS Printer Agent-1.0.0.exe

# En Linux (.deb) o macOS (.dmg):
./build-installer.sh
# Genera: target/installer/*.deb o *.dmg
```
El instalador incluye el Java runtime, el usuario no necesita instalar Java. Ambos scripts requieren `JAVA_HOME`
apuntando a un JDK 17+ con `jpackage`, copian `config.properties` dentro del instalador y pasan
`--java-options "-Xmx256m"` y `--add-opens=java.desktop/sun.print=ALL-UNNAMED`. El `.bat` usa `mvnw.cmd` si existe;
el `.sh` usa `mvn` del `PATH`.

### Build Windows Service Package
```bash
# Desde Linux (Ubuntu): requiere curl o wget, y zip
./build-service-package.sh

# Desde Windows:
build-service-package.bat

# Genera: target/pos-printer-agent-service/
#   - pos-printer-agent.exe (WinSW 2.12.0 x64, descargado de GitHub)
#   - pos-printer-agent.jar
#   - pos-printer-agent.xml (config del servicio, copiada de service/)
#   - config.properties
#   - install-service.bat
#   - uninstall-service.bat
#   - LEEME.txt
# El .sh además genera target/pos-printer-agent-service.zip
```

Para instalar como servicio en Windows:
1. Copia la carpeta `pos-printer-agent-service` a la PC destino
2. Edita `config.properties` con la configuración correcta (en Windows: `printer.name` y `scale.port=COMx`)
3. Ejecuta `install-service.bat` como Administrador

El servicio:
- Ejecuta `java -Xmx256m -jar pos-printer-agent.jar` con `%BASE%` (la carpeta del exe) como directorio de trabajo.
  **A diferencia del instalador, no incluye Java**: la PC necesita Java 17+ en el `PATH` del sistema
- Inicia automáticamente con Windows
- Se reinicia automáticamente si falla (`onfailure`: 10 s, 20 s, 30 s), incluida la salida con código 3 por impresión
  colgada
- `stoptimeout` de 15 s (el shutdown hook espera hasta 8 s a que termine la cola de impresión)
- Logs en `pos-printer-agent.out.log` (y `pos-printer-agent.err.log`)

### Configuration
Configuration priority: Environment variables > `config.properties` > defaults. An empty value (env var or property)
falls through to the next level. Every key, its env var and its default are in [Environment Variables](#environment-variables).

- The agent appends `?stationId=<URL-encoded station.id>` to `server.url`, so `server.url` must not carry its own
  query string. Production uses `wss://api.lapasadita.app/ws/printer` (Cloudflare SSL, commented in `config.properties`)
- On Windows, if `printer.name` is empty and `printer.path` doesn't start with `/dev/`, `printer.path` is used as the
  printer name
- `scale.enabled` only controls the scale: the REST server on 8081 **always** starts, so `/api/station` works with the
  scale disabled. The code default is `true`; the checked-in `config.properties` sets `false`
- `printer.stuckExitSeconds`: if one print/drawer job runs longer than this, the agent exits with code 3 so WinSW
  restarts it. `0` disables; a non-numeric or negative value logs a warning and uses `120`
- The checked-in `config.properties` targets Linux (`printer.path=/dev/usb/lp6`, `scale.port=/dev/ttyACM0`) with the
  Windows lines commented out. It is copied as-is into the installer and the service package

## Architecture

### Core Components

**POSPrinterAgent** (`src/main/java/com/pasadita/pos/POSPrinterAgent.java`)
- Main class. Does **not** extend `WebSocketClient`: the inner `AgentSocket extends WebSocketClient` is a
  single-use client that delegates its callbacks to the agent, and **every connection attempt creates a new one**.
  Never call Java-WebSocket's `reconnect()` (throws `IllegalStateException` from the library's own threads) nor
  `connectBlocking(timeout)`: on timeout it, like `reconnect()`, goes through `reset()`, which waits on
  `closeBlocking()` with no timeout
- **Startup** (`main`): splits the arguments (`--test`/`-t` flag vs. config path, any order), loads the config and
  logs the effective values, handles `--test`, creates the `ESCPOSPrinter`
  (logs `isAvailable()`), starts `ScaleRestServer` (if that fails it is logged and the agent continues without REST),
  registers the shutdown hook, calls `start()` and blocks on `awaitTermination()`. An invalid server URL exits with code 1
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
  - Invalid JSON is logged and dropped (no `PRINT_RESULT`: there is no ticketId to report)
  - A failed ticket sends `PRINT_RESULT` with `success=false` and `error` prefixed `Error de impresora:`,
    `Error de I/O al imprimir:` or `Error inesperado al imprimir:`. `OPEN_DRAWER` failures are only logged
  - If the socket is closed when a job ends, its `PRINT_RESULT` is dropped with a warning (not queued or retried)
- **Watchdog** (`print-watchdog`): `runTimed` wraps every print/drawer job, logs start, end and duration, warns every
  15 s while it runs, and once it exceeds `printer.stuckExitSeconds` logs an error and calls `System.exit(3)` (backstop
  `Runtime.halt(3)` after 20 s) so WinSW restarts the service (`onfailure`). A hung job would otherwise block every
  later ticket and drawer command while the socket still looks healthy to the backend
- **Shutdown hook** (SIGTERM/SIGINT, service stop, `System.exit`): stops `ws-reconnect`, drains `print-worker` for up
  to 8 s (queued jobs finish and their `PRINT_RESULT` can still go out; then `shutdownNow`), stops the watchdog,
  discards the socket, then stops the REST server and disconnects the scale
- Sends `CONNECTED` on open and `PRINT_RESULT` after each ticket (printer errors travel in `error` with `success=false`)
- Exit codes: `1` invalid server URL, `3` stuck print/drawer job

**ESCPOSPrinter** (`src/main/java/com/pasadita/pos/ESCPOSPrinter.java`)
- Generates ESC/POS commands for Epson TM-T88V thermal printers
- Auto-detects ticket type based on `deliveryOrderId` and `deliveryAddress` (see [Tickets](#tickets)):
  - **Cash register ticket**: Standard sale receipt. **Starts with the drawer-kick pulse**, so every printed sale opens
    the cash drawer
  - **Delivery ticket**: Customer details and delivery address displayed prominently; does not kick the drawer
- **Cross-platform printing**: every job (ticket, drawer, test page) goes through `send()`:
  - **Linux**: Writes directly to device file (`/dev/usb/lpX`) - no driver required
  - **Windows**: Uses Java PrintService with installed printer name (`DocFlavor.BYTE_ARRAY.AUTOSENSE`, raw bytes) -
    stable, no port changes
  - Device access is serialized by a fair `ReentrantLock` (`deviceLock`), so two jobs never interleave bytes
- Supports 80mm thermal printers (42 chars/line), PC850 charset for Spanish
- `openCashDrawer()`: opens the cash drawer standalone (no ticket), sending only the ESC/POS drawer-kick pulse (`{27,112,0,25,250}`) through the same cross-platform path as `print()`. Throws `PrinterException`; the agent logs the result (no confirmation message in the protocol)
- **Windows lookup per job** (`resolveWindowsPrintService`): before every job it refreshes the JDK's printer cache and
  looks the printer up by name — exact match (case-insensitive) first, then a partial match only if it is unique;
  otherwise a `PrinterException` lists the installed printers ("no encontrada" / "nombre ambiguo")
- **JDK printer cache** (`JdkPrintServiceCache`, private nested class): on Windows `PrintServiceLookup` returns a cache
  refreshed only by a native `PrinterListener` thread that exits without retry if the spooler restarts or wasn't ready
  at JVM start, freezing the list until the JVM restarts. The agent calls the private
  `sun.print.PrintServiceLookupProvider.refreshServices()` via reflection, which needs
  `--add-opens java.desktop/sun.print=ALL-UNNAMED`: provided by the `Add-Opens` manifest entry (`java -jar`, used by
  WinSW) and by `--java-options` in `build-installer.*` (jpackage). If inaccessible it warns once and uses the plain lookup
- **Linux**: checks the device exists and is writable before writing, with explicit error messages
- `isAvailable()` (logged at startup): resolves the printer on Windows; checks the device exists and is writable on Linux
- `PrinterException` is the printer error type. `USBException` is a `@Deprecated` subclass kept for compatibility —
  don't use it in new code
- All logging via SLF4J (no `System.out`)

**DTOs** (`src/main/java/com/pasadita/pos/dto/`)
- `TicketDTO` and `SaleDetailDTO`: plain getters/setters, annotated `@JsonIgnoreProperties(ignoreUnknown = true)` so
  new backend fields don't break parsing. `LocalDateTime` fields use Jackson's JSR310 module (ISO-8601 without zone,
  e.g. `"2025-01-12T14:30:00"`)

**Scale Integration** (`src/main/java/com/pasadita/pos/scale/`)
- **TorreyScaleController**: Serial communication with Torrey PCR series scales via jSerialComm
  - 9600 baud, 8N1, blocking timeouts (read 2 s, write 1 s)
  - `connect()` opens the port, waits 500 ms and starts a daemon `scale-polling` thread: every ~100 ms it flushes the
    input buffer, sends the `W` command, waits 50 ms and reads the available bytes
  - `readWeight()` never touches the port: it returns the last cached `WeightReading` (a Java `record`: `weight`,
    `unit`, `stable`, `success`, `errorMessage`). Returns zero before the first reading; if the cache is older than
    3 s it returns it with `stable=false`
  - Parsing: strips non-printable characters, unit `kg` if the frame contains "kg" (otherwise `g`), negative if it
    contains `-`, weight as `BigDecimal` with scale 3
  - Stability = frame stable (contains `ST`, or doesn't contain `US`) **and** the last 3 readings identical
  - 5 consecutive read errors → treated as physically disconnected: `isConnected=false` and polling stops. There is no
    auto-reconnect; the frontend must call `POST /api/scale/connect`
- **ScaleRestServer**: Lightweight HTTP server (`com.sun.net.httpserver`, no Spring Boot) on port 8081 (hard-coded),
  **bound to the loopback address only**, 4-thread pool
  - GET `/api/scale/weight` - Read current weight: `{"success":true,"weight":1.250,"unit":"kg","stable":true,"connected":true}`.
    503 if the scale is disabled or not connected, 500 on a read error
  - GET `/api/scale/status` - Connection status: `{"enabled","port","connected","portName"}`
  - POST `/api/scale/connect` - Connect to scale (creates the controller if needed). 500 if the port can't be opened,
    503 if the scale is disabled
  - POST `/api/scale/disconnect` - Disconnect from scale
  - GET `/api/scale/ports` - List available serial ports: `{"success","ports":[...],"count","currentPort"}`
  - GET `/api/station` - Returns this agent's configured stationId: `{"stationId":"POS1"}` (frontend caches it to tag sales)
  - CORS enabled for frontend integration: `Access-Control-Allow-Origin: *`, `OPTIONS` preflight → 204 (cached 24 h).
    A wrong method → 405 `{"success":false,"error":"Método no permitido"}`
  - `BigDecimal` serialized as plain numbers (`WRITE_BIGDECIMAL_AS_PLAIN`); "Broken pipe" from clients that drop the
    connection mid-response (frequent polling) is ignored

### Data Flow

1. Backend creates sale/delivery order and sends ticket JSON via WebSocket
2. `AgentSocket.onMessage()` (WebSocket thread) only enqueues the message on the `print-worker` thread
3. The worker reads the JSON tree first: if `type=OPEN_DRAWER` it calls `ESCPOSPrinter.openCashDrawer()` and returns; otherwise it deserializes `TicketDTO` with Jackson
4. `ESCPOSPrinter.print()` generates ESC/POS byte commands
5. Commands written to the printer device (Linux, e.g. `/dev/usb/lp0`) or to the Windows printer resolved by name
6. Confirmation JSON sent back to server with success/error status

### WebSocket Protocol

The agent connects to `server.url?stationId=<station.id>`. Timestamps it sends are local time, `yyyy-MM-dd HH:mm:ss`.

**Outgoing messages from agent:**
- `CONNECTED`: Sent on successful connection
  ```json
  {"type":"CONNECTED","stationId":"POS1","timestamp":"2025-01-12 14:30:00"}
  ```
- `PRINT_RESULT`: Sent after print attempt
  ```json
  {"type":"PRINT_RESULT","ticketId":123,"success":true,"stationId":"POS1","timestamp":"2025-01-12 14:30:00"}
  // With error:
  {"type":"PRINT_RESULT","ticketId":124,"success":false,"stationId":"POS1","timestamp":"...","error":"Error de impresora: ..."}
  ```

**Incoming messages to agent:**
- Full `TicketDTO` JSON object with `saleDetails` array (printed as a ticket)
  - `TicketDTO`: `id`, `employeeId`, `employeeName`, `employeePhone`, `deliveryOrderId`, `customerId`,
    `customerName`, `customerPhone`, `customerDiscount`, `deliveryAddress`, `paymentMethodId`, `paymentMethodName`,
    `datetime`, `subtotal`, `discountAmount`, `total`, `amountTendered`, `changeDue`, `paid`, `notes`, `saleDetails`
  - `SaleDetailDTO`: `detailId`, `saleId`, `saleDate`, `productId`, `productName`, `productCategory`, `quantity`,
    `unitPrice`, `discount`, `subtotal`, `total`
- `OPEN_DRAWER`: kick the cash drawer without printing
  ```json
  {"type":"OPEN_DRAWER","timestamp":"2025-01-12T14:30:00Z"}
  ```
  Sent by the backend when a cash payment is collected but no ticket is printed. The worker inspects the `type` node before deserializing; messages with `type=OPEN_DRAWER` trigger `ESCPOSPrinter.openCashDrawer()`, anything else is parsed as a `TicketDTO`.

### Tickets

The system automatically determines ticket type in `ESCPOSPrinter.isDeliveryOrder()`:
- **Delivery order**: `deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty()`
- **Cash register**: Otherwise

Both start with the header: business name (double size), address, `WhatsApp: <phone>`.

- **Cash register**: drawer kick, `TICKET #<id>`, Fecha, Cajero, Cliente, Pago, line items, totals, Nota,
  "Gracias por su compra!"
- **Delivery**: "PEDIDO A DOMICILIO" banner, `PEDIDO #<deliveryOrderId>` (double height), `Ticket Venta: #<id>`, Fecha,
  "DATOS DEL CLIENTE" block (Nombre, Descuento Cliente if `customerDiscount` > 0, Telefono, Direccion wrapped at 42
  chars), Atendio, Forma de Pago, line items, totals, NOTAS (wrapped), "Gracias por su preferencia!"
- **Line items**: `CANT(5) PRODUCTO(18) PRECIO(8) TOTAL(8)` = 42 chars. Names over 18 chars wrap on a word boundary
  onto continuation lines; TOTAL is the line's `subtotal`; whole quantities print without decimals. Empty
  `saleDetails` prints "(Sin productos)". The line's `discount` and `total` are not printed
- **Totals** (right-aligned): Subtotal, Descuento (only if `discountAmount` > 0), TOTAL (bold, double height). When
  `paymentMethodId == 1` (cash) it also prints Recibido (`amountTendered`) and Cambio (`changeDue`), `0.00` if null
- `paid` is deserialized but **not printed**: there is no paid / pay-on-delivery line today
- Text is encoded as ISO-8859-1 while the printer is switched to code page PC850 (`ESC t 2`), which differ above 0x7F:
  accented letters and `ñ` in ticket data come out as other glyphs (e.g. `ñ` → `±`). That's why the fixed strings are
  ASCII ("Direccion", "Atendio", "Telefono"); keep new literals ASCII

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
  at `info`, with date/time (`yyyy-MM-dd HH:mm:ss.SSS`), thread name, `[LEVEL]` and short logger name;
  `org.java_websocket` at `warn`
- As a Windows service, logs land in `pos-printer-agent.out.log` next to the exe. WinSW rotates it by size
  (`service/pos-printer-agent.xml`: `roll-by-size`, 10240 KB per file, 5 files kept)
- Logged events: each connection attempt (`Intento de conexión #n`) and the next delay, connection established (attempt
  number, seconds offline), close (code, reason, `remota`), message received/enqueued and then processed (type,
  ticketId, time waited in queue), start/end/duration of each print and drawer opening, slow-job warnings every 15 s,
  `PRINT_RESULT` sent
- Thread names in the logs: `ws-reconnect`, `WebSocketConnectReadThread-N`, `print-worker`, `print-watchdog`,
  `scale-polling`, `stuck-exit` / `stuck-exit-halt` (stuck-job exit), `pool-N-thread-M` (REST handlers)

## Dependencies

- Java 17+ (also builds on newer JDKs)
- Maven 3.6+ (or the bundled wrapper `mvnw` / `mvnw.cmd`)
- Java-WebSocket 1.6.0 - WebSocket client
- Jackson 2.18.6 - JSON serialization/deserialization with JSR310 module for LocalDateTime
- jSerialComm 2.11.4 - Serial port communication for scale
- SLF4J 2.0.17 - Logging (`slf4j-api` + `slf4j-simple`)
- Build plugins: `maven-compiler-plugin` 3.11.0, `maven-assembly-plugin` 3.6.0 (`jar-with-dependencies`, plus the
  `Add-Opens` manifest entry)
- Windows service: WinSW 2.12.0 (downloaded by `build-service-package.*`, not in the repo)

## Environment Variables

Configuration can be set via environment variables (takes precedence over `config.properties`):

| Environment Variable | Property Key | Default | Description |
|---------------------|--------------|---------|-------------|
| `SERVER_URL` | `server.url` | `ws://localhost:8080/ws/printer` | WebSocket server URL (`?stationId=` is appended) |
| `STATION_ID` | `station.id` | `POS1` | POS station identifier |
| `PRINTER_PATH` | `printer.path` | `/dev/usb/lp0` | Linux device path |
| `PRINTER_NAME` | `printer.name` | _(empty)_ | Windows printer name (e.g. `EPSON TM-T88V Receipt`) |
| `BUSINESS_NAME` | `business.name` | `LA PASADITA` | Business name for header |
| `BUSINESS_ADDRESS` | `business.address` | _(empty)_ | Business address |
| `BUSINESS_PHONE` | `business.phone` | _(empty)_ | Business phone (printed as `WhatsApp:`) |
| `SCALE_PORT` | `scale.port` | `/dev/ttyACM0` | Serial port for scale (`COMx` on Windows) |
| `SCALE_ENABLED` | `scale.enabled` | `true` | Enable the scale (the REST server starts regardless) |
| `SCALE_AUTO_CONNECT` | `scale.autoConnect` | `true` | Auto-connect scale on startup |
| `PRINTER_STUCK_EXIT_SECONDS` | `printer.stuckExitSeconds` | `120` | Exit (code 3) when a print job hangs longer than this; `0` disables |

## Key Design Patterns

- **Auto-reconnection**: single scheduler thread, one pending attempt at most, a fresh client per attempt, backoff 5–30 s, retries forever (see POSPrinterAgent)
- **Ordered processing**: one worker thread for tickets and drawer commands; the WebSocket thread only enqueues
- **Self-healing**: a print job stuck past `printer.stuckExitSeconds` exits the process (code 3) for WinSW to restart it
- **Serialized device access**: every printer write goes through `ESCPOSPrinter.send()` under `deviceLock`
- **Direct device I/O**: on Linux the printer is accessed as a file (`FileOutputStream`) for simplicity
- **Character encoding**: ISO-8859-1 bytes with the printer on code page PC850; keep fixed ticket strings ASCII (see [Tickets](#tickets))
- **Ticket width**: 42 characters for 80mm printers
- **Configuration cascade**: ENV vars override properties file, which overrides defaults
- **Shutdown hook**: Graceful cleanup on SIGTERM/SIGINT, draining queued print jobs first
- **No frameworks**: no Spring; the REST server is the JDK's `HttpServer`

## Project Structure

```
pos-printer-agent/
├── pom.xml
├── config.properties             # Per-machine config (copied into installer and service package)
├── mvnw, mvnw.cmd, .mvn/          # Maven wrapper
├── build-installer.bat / .sh     # jpackage installer (.exe / .deb / .dmg)
├── build-service-package.bat / .sh  # WinSW service package
├── service/
│   ├── pos-printer-agent.xml     # WinSW config (restart on failure, log rotation)
│   ├── install-service.bat
│   └── uninstall-service.bat
├── CLAUDE.md, AGENTS.md, README.md
└── src/main/
    ├── resources/
    │   └── simplelogger.properties   # slf4j-simple config
    └── java/com/pasadita/pos/
        ├── POSPrinterAgent.java      # Main class: connection supervisor, message worker, watchdog
        ├── ESCPOSPrinter.java        # ESC/POS command generator, printer I/O, JDK printer cache refresh
        ├── dto/
        │   ├── TicketDTO.java        # Ticket data transfer object
        │   └── SaleDetailDTO.java    # Sale line item DTO
        └── scale/
            ├── TorreyScaleController.java  # Serial communication with scale (polling + cache)
            └── ScaleRestServer.java        # HTTP REST API for scale and station ID (port 8081)
```
