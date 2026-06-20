# AGENTS.md

## AI Coding Agent Guidance for POS Printer Agent

This document summarizes essential knowledge and actionable conventions for AI agents working in this codebase. It is based on project documentation and code structure as of March 2026.

---

### 1. Architecture & Major Components

- **POSPrinterAgent** (`src/main/java/com/pasadita/pos/POSPrinterAgent.java`):
  - Main entry point. Extends `WebSocketClient`.
  - Handles WebSocket connection to backend, auto-reconnects on failure.
  - Receives ticket JSON, deserializes with Jackson, prints via `ESCPOSPrinter`.
  - Sends confirmation JSON back to backend (`CONNECTED`, `PRINT_RESULT`).
  - Starts scale REST server if enabled.

- **ESCPOSPrinter** (`src/main/java/com/pasadita/pos/ESCPOSPrinter.java`):
  - Generates ESC/POS commands for Epson TM-T88V printers.
  - Detects ticket type (cash register vs delivery) based on `deliveryOrderId` and `deliveryAddress`.
  - Cross-platform: Direct device file on Linux, Java PrintService on Windows.
  - 80mm printer support (42 chars/line), PC850 charset for Spanish.
  - `openCashDrawer()`: opens the cash drawer with no ticket, emitting only the ESC/POS drawer-kick pulse (`{27,112,0,25,250}`) over the same cross-platform path as `print()`. Fire-and-forget: errors are logged, not propagated.

- **Scale Integration** (`src/main/java/com/pasadita/pos/scale/`):
  - `TorreyScaleController`: Serial comms with Torrey PCR scales (jSerialComm).
    - Polling-based architecture: dedicated daemon thread reads weight every 300ms, caches results in a volatile `WeightReading` record.
    - `readWeight()` returns instantly from cache (non-blocking).
    - Physical disconnect detection: after 5 consecutive read errors, marks scale as disconnected.
    - `WeightReading` is a Java `record` (not a class).
  - `ScaleRestServer`: Lightweight HTTP server (port 8081, no Spring Boot).
    - Binds to loopback address only (`InetAddress.getLoopbackAddress()`) for security.
    - Endpoints: `/api/scale/weight`, `/api/scale/status`, `/api/scale/connect`, `/api/scale/disconnect`, `/api/scale/ports`, `/api/station`.
    - `/api/station` (GET): returns this agent's configured `stationId` as `{"stationId":"POS1"}` — `stationId` is passed into the `ScaleRestServer` constructor by `POSPrinterAgent.main`. The frontend caches it to tag sales.
    - CORS: `ALLOWED_ORIGIN = "*"` (all origins).

- **DTOs** (`src/main/java/com/pasadita/pos/dto/`):
  - `TicketDTO`, `SaleDetailDTO`: Data transfer objects for tickets and sale lines.
  - Annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility with backend changes.

- **Exceptions** in `ESCPOSPrinter`:
  - `PrinterException`: Primary exception for printer errors.
  - `USBException`: Deprecated alias for `PrinterException` — use `PrinterException` in new code.

---

### 2. Data Flow & Protocols

- Backend sends ticket JSON via WebSocket → `POSPrinterAgent` → `ESCPOSPrinter` → printer device.
- `onMessage` reads the JSON tree first: a `{"type":"OPEN_DRAWER",...}` command kicks the cash drawer via `ESCPOSPrinter.openCashDrawer()` (no ticket, no confirmation); any other message is deserialized to `TicketDTO` and printed.
- Confirmation JSON sent back to backend after print attempt.
- Ticket type detection logic in `ESCPOSPrinter.isDeliveryOrder()`:
  - Delivery: `deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty()`
  - Cash register: otherwise.
- Delivery tickets emphasize customer/delivery info and payment status.

---

### 3. Build, Run, and Packaging

- **Build:**
  - `mvn clean package` → `target/pos-printer-agent.jar` (all dependencies included)
- **Run:**
  - `java -jar target/pos-printer-agent.jar [config.properties|--test]`
- **Windows Installer:**
  - `build-installer.bat` (Windows)
- **Windows Service Package:**
  - `./build-service-package.sh` (Linux) or `build-service-package.bat` (Windows)
  - Output: `target/pos-printer-agent-service/` (includes service wrapper, config, scripts)

---

### 4. Configuration & Environment

- **Config priority:** ENV vars > `config.properties` > defaults
- **Key ENV vars:** `SERVER_URL`, `STATION_ID`, `PRINTER_PATH`, `PRINTER_NAME`, `BUSINESS_NAME`, `BUSINESS_ADDRESS`, `BUSINESS_PHONE`, `SCALE_PORT`, `SCALE_ENABLED`, `SCALE_AUTO_CONNECT`
- **Linux permissions:**
  - Printer: Add user to `lp` group or `chmod 666 /dev/usb/lp0`
  - Scale: Add user to `dialout` group

---

### 5. Project Patterns & Conventions

- **Auto-reconnect:** 5s delay on WebSocket disconnect/error.
- **Direct device I/O:** Printer accessed as file on Linux for simplicity.
- **Character encoding:** ISO-8859-1 for ESC/POS, PC850 for Spanish.
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
├── README.md
├── build-installer.sh
├── build-service-package.sh
├── service/
│   ├── install-service.bat
│   ├── pos-printer-agent.xml
│   └── uninstall-service.bat
└── src/main/java/com/pasadita/pos/
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

- **WebSocket:** Main communication with backend for ticketing.
- **Serial port:** For Torrey scale integration (configurable port).
- **REST API:** For local scale access and `stationId` lookup (port 8081, loopback only, CORS `*`).

---

### 8. Examples

- **Print test page:** `java -jar target/pos-printer-agent.jar --test`
- **Custom config:** `java -jar target/pos-printer-agent.jar /path/to/config.properties`
- **Linux printer permissions:** `sudo usermod -a -G lp $USER` or `sudo chmod 666 /dev/usb/lp0`

---

For more details, see `README.md` and `CLAUDE.md`.

