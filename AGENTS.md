# AGENTS.md

## AI Coding Agent Guidance for POS Printer Agent

This document summarizes essential knowledge and actionable conventions for AI agents working in this codebase. It is based on project documentation and code structure as of October 2026. `CLAUDE.md` has the full detail.

---

### 1. Architecture & Major Components

- **POSPrinterAgent** (`src/main/java/com/pasadita/pos/POSPrinterAgent.java`):
  - Main entry point. Does not extend `WebSocketClient`: the inner `AgentSocket` is a single-use client and every connection attempt creates a new one (never call the library's `reconnect()`/`connectBlocking(timeout)`).
  - Reconnection runs only on the `ws-reconnect` scheduler thread: at most one pending attempt (`AtomicBoolean` guard), backoff 5→10→20→30 s, retries forever, 30 s connection-lost timeout, 30 s safety-net check. Works when the first connection fails at boot.
  - Connects to `server.url` with `?stationId=<station.id>` appended.
  - The WebSocket thread only enqueues messages; a single `print-worker` thread processes tickets and `OPEN_DRAWER` in order. A watchdog warns every 15 s on slow jobs and exits with code 3 past `printer.stuckExitSeconds` (default 120) so WinSW restarts the service.
  - Sends confirmation JSON back to backend (`CONNECTED`, `PRINT_RESULT`). Invalid JSON is dropped without a reply; a `PRINT_RESULT` that can't be sent because the socket is closed is lost (not retried).
  - Starts the REST server on port 8081 (always, even with `scale.enabled=false`).
  - Shutdown hook drains the print queue for up to 8 s before closing the socket. Exit codes: `1` invalid server URL, `3` stuck print job.

- **ESCPOSPrinter** (`src/main/java/com/pasadita/pos/ESCPOSPrinter.java`):
  - Generates ESC/POS commands for Epson TM-T88V printers.
  - Detects ticket type (cash register vs delivery) based on `deliveryOrderId` and `deliveryAddress`.
  - **Cash register tickets start with the drawer-kick pulse** (every printed sale opens the drawer); delivery tickets don't; the test page does.
  - Cash payments (`paymentMethodId == 1`) also print `Recibido` (`amountTendered`) and `Cambio` (`changeDue`). `paid` is not printed.
  - Cross-platform: Direct device file on Linux, Java PrintService on Windows. All writes go through `send()` under a fair `ReentrantLock` (`deviceLock`).
  - 80mm printer support (42 chars/line), PC850 charset for Spanish.
  - `openCashDrawer()`: opens the cash drawer with no ticket, emitting only the ESC/POS drawer-kick pulse (`{27,112,0,25,250}`) over the same cross-platform path as `print()`. Throws `PrinterException`; the agent logs the result (no confirmation message).
  - Windows: resolves the printer by name on every job (exact match first, partial only if unique) after refreshing the JDK's printer cache via reflection (`Add-Opens java.desktop/sun.print`, see CLAUDE.md). If `printer.name` is empty and `printer.path` doesn't start with `/dev/`, `printer.path` is used as the name.

- **Scale Integration** (`src/main/java/com/pasadita/pos/scale/`):
  - `TorreyScaleController`: Serial comms with Torrey PCR scales (jSerialComm, 9600 8N1).
    - Polling-based architecture: dedicated daemon thread `scale-polling` sends `W` and reads the weight every ~100 ms, caching results in a volatile `WeightReading` record.
    - `readWeight()` returns instantly from cache (non-blocking); a cache older than 3 s is returned with `stable=false`.
    - Stable = the scale frame says stable **and** the last 3 readings are identical.
    - Physical disconnect detection: after 5 consecutive read errors, marks scale as disconnected and stops polling (no auto-reconnect; `POST /api/scale/connect` reconnects).
    - `WeightReading` is a Java `record` (not a class).
  - `ScaleRestServer`: Lightweight HTTP server (JDK `HttpServer`, port 8081, no Spring Boot).
    - Binds to loopback address only (`InetAddress.getLoopbackAddress()`) for security.
    - Endpoints: `/api/scale/weight`, `/api/scale/status`, `/api/scale/connect`, `/api/scale/disconnect`, `/api/scale/ports`, `/api/station`.
    - `/api/station` (GET): returns this agent's configured `stationId` as `{"stationId":"POS1"}` — `stationId` is passed into the `ScaleRestServer` constructor by `POSPrinterAgent.main`. The frontend caches it to tag sales.
    - CORS: `ALLOWED_ORIGIN = "*"` (all origins). Wrong method → 405; scale disabled or not connected → 503.

- **DTOs** (`src/main/java/com/pasadita/pos/dto/`):
  - `TicketDTO`, `SaleDetailDTO`: Data transfer objects for tickets and sale lines.
  - Annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility with backend changes.

- **Exceptions** in `ESCPOSPrinter`:
  - `PrinterException`: Primary exception for printer errors.
  - `USBException`: Deprecated alias for `PrinterException` — use `PrinterException` in new code.

---

### 2. Data Flow & Protocols

- Backend sends ticket JSON via WebSocket → `POSPrinterAgent` → `ESCPOSPrinter` → printer device.
- `onMessage` only enqueues; the `print-worker` thread reads the JSON tree first: a `{"type":"OPEN_DRAWER",...}` command kicks the cash drawer via `ESCPOSPrinter.openCashDrawer()` (no ticket, no confirmation); any other message is deserialized to `TicketDTO` and printed.
- Confirmation JSON (`PRINT_RESULT` with `ticketId`, `success`, `stationId`, `timestamp` and `error` on failure) sent back to backend after print attempt.
- Ticket type detection logic in `ESCPOSPrinter.isDeliveryOrder()`:
  - Delivery: `deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty()`
  - Cash register: otherwise.
- Delivery tickets emphasize the order number, customer data (name, phone, wrapped address) and payment method.

---

### 3. Build, Run, and Packaging

- **Build:**
  - `mvn clean package` (or `./mvnw clean package`) → `target/pos-printer-agent.jar` (all dependencies included). Ignore the thin `target/pos-printer-agent-1.0.0.jar`.
  - No automated tests exist (`src/test` is absent): verify by building and, with hardware, printing the test page.
- **Run:**
  - `java -jar target/pos-printer-agent.jar [config.properties] [--test|-t]`
  - The first argument is always read as the config file, so pass it before `--test`; `--test` alone runs with ENV/defaults only.
- **Installer (jpackage, bundles Java):**
  - `build-installer.bat` (Windows `.exe`) or `./build-installer.sh` (Linux `.deb` / macOS `.dmg`). Needs `JAVA_HOME` on a JDK 17+.
- **Windows Service Package:**
  - `./build-service-package.sh` (Linux, needs curl/wget and zip) or `build-service-package.bat` (Windows)
  - Output: `target/pos-printer-agent-service/` (WinSW 2.12.0 wrapper, jar, `service/` XML and scripts, config)
  - The service runs `java -Xmx256m -jar`: unlike the installer, the target PC needs Java 17+ on the `PATH`. WinSW restarts it on failure (10/20/30 s).

---

### 4. Configuration & Environment

- **Config priority:** ENV vars > `config.properties` > defaults (empty values fall through)
- **Key ENV vars:** `SERVER_URL`, `STATION_ID`, `PRINTER_PATH`, `PRINTER_NAME`, `BUSINESS_NAME`, `BUSINESS_ADDRESS`, `BUSINESS_PHONE`, `SCALE_PORT`, `SCALE_ENABLED`, `SCALE_AUTO_CONNECT`, `PRINTER_STUCK_EXIT_SECONDS`
- **Defaults:** `ws://localhost:8080/ws/printer`, `POS1`, `/dev/usb/lp0`, `LA PASADITA`, `/dev/ttyACM0`, scale enabled and auto-connect `true`, stuck exit `120` s. The checked-in `config.properties` targets Linux and sets `scale.enabled=false`.
- **Linux permissions:**
  - Printer: Add user to `lp` group or `chmod 666 /dev/usb/lp0`
  - Scale: Add user to `dialout` group

---

### 5. Project Patterns & Conventions

- **Language:** identifiers in English; comments, log messages, ticket text and scripts in Spanish.
- **Auto-reconnect:** single scheduler thread, one pending attempt at most, fresh client per attempt, backoff 5–30 s.
- **Logging:** `slf4j-simple` configured in `src/main/resources/simplelogger.properties` (stdout, date/time, thread name) → `pos-printer-agent.out.log` under WinSW, rotated by size. Use SLF4J, never `System.out`.
- **Direct device I/O:** Printer accessed as file on Linux for simplicity.
- **Character encoding:** text is encoded ISO-8859-1 while the printer uses code page PC850, so accented letters/`ñ` in ticket data print as other glyphs. Keep fixed ticket strings ASCII ("Direccion", "Telefono").
- **Ticket width:** 42 chars for 80mm printers.
- **Shutdown hook:** Graceful cleanup on SIGTERM/SIGINT.
- **No Spring Boot:** REST server is a minimal custom HTTP server.
- **Dependencies:** Java-WebSocket, Jackson, jSerialComm, SLF4J.

---

### 6. Key Files & Structure

```
pos-printer-agent/
├── pom.xml
├── config.properties
├── CLAUDE.md
├── AGENTS.md
├── README.md
├── mvnw / mvnw.cmd
├── build-installer.sh / build-installer.bat
├── build-service-package.sh / build-service-package.bat
├── service/
│   ├── install-service.bat
│   ├── pos-printer-agent.xml
│   └── uninstall-service.bat
└── src/main/
    ├── resources/simplelogger.properties
    └── java/com/pasadita/pos/
        ├── POSPrinterAgent.java
        ├── ESCPOSPrinter.java
        ├── dto/
        │   ├── TicketDTO.java
        │   └── SaleDetailDTO.java
        └── scale/
            ├── TorreyScaleController.java
            └── ScaleRestServer.java
```

---

### 7. Integration Points

- **WebSocket:** Main communication with backend for ticketing (`server.url?stationId=...`).
- **Serial port:** For Torrey scale integration (configurable port).
- **REST API:** For local scale access and `stationId` lookup (port 8081, loopback only, CORS `*`).

---

### 8. Examples

- **Print test page:** `java -jar target/pos-printer-agent.jar config.properties --test`
- **Custom config:** `java -jar target/pos-printer-agent.jar /path/to/config.properties`
- **Linux printer permissions:** `sudo usermod -a -G lp $USER` or `sudo chmod 666 /dev/usb/lp0`

---

For more details, see `README.md` and `CLAUDE.md`.
