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

## Architecture

### Core Components

**POSPrinterAgent** (`src/main/java/com/pasadita/pos/POSPrinterAgent.java`)
- Main class extending `WebSocketClient`
- Manages WebSocket connection to backend with auto-reconnect
- Receives JSON ticket messages, deserializes with Jackson, and sends to printer
- Sends confirmation messages back to server (`CONNECTED`, `PRINT_RESULT`)
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
- `openCashDrawer()`: opens the cash drawer standalone (no ticket), sending only the ESC/POS drawer-kick pulse (`{27,112,0,25,250}`) through the same cross-platform path as `print()`

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
2. `POSPrinterAgent.onMessage()` reads the JSON tree first: if `type=OPEN_DRAWER` it calls `ESCPOSPrinter.openCashDrawer()` and returns; otherwise it deserializes `TicketDTO` with Jackson
3. `ESCPOSPrinter.print()` generates ESC/POS byte commands
4. Commands written directly to printer device (e.g., `/dev/usb/lp0`)
5. Confirmation JSON sent back to server with success/error status

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
  Sent by the backend when a cash payment is collected but no ticket is printed. `onMessage` inspects the `type` node before deserializing; messages with `type=OPEN_DRAWER` trigger `ESCPOSPrinter.openCashDrawer()`, anything else is parsed as a `TicketDTO`.

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

## Key Design Patterns

- **Auto-reconnection**: 5-second delay retry on WebSocket disconnect/error
- **Direct device I/O**: Printer accessed as file (`FileOutputStream`) for simplicity
- **Character encoding**: ISO-8859-1 for ESC/POS, PC850 charset for Spanish characters
- **Ticket width**: 42 characters for 80mm printers
- **Configuration cascade**: ENV vars override properties file, which overrides defaults
- **Shutdown hook**: Graceful cleanup on SIGTERM/SIGINT

## Project Structure

```
src/main/java/com/pasadita/pos/
├── POSPrinterAgent.java      # Main class, WebSocket client
├── ESCPOSPrinter.java        # ESC/POS command generator
├── dto/
│   ├── TicketDTO.java        # Ticket data transfer object
│   └── SaleDetailDTO.java    # Sale line item DTO
└── scale/
    ├── TorreyScaleController.java  # Serial communication with scale
    └── ScaleRestServer.java        # HTTP REST API for scale
```