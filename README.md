# POS Printer Agent

Agente de impresión para terminales POS de La Pasadita. Se conecta por WebSocket al backend, imprime los tickets en
impresoras térmicas ESC/POS, abre el cajón de dinero y expone una API REST local para la báscula Torrey y el ID de la
estación.

## Requisitos

- Java 17 o superior (el instalador `.exe` ya lo incluye; el servicio de Windows no)
- Maven 3.6+ o el wrapper incluido (`./mvnw`, `mvnw.cmd`)
- Impresora térmica ESC/POS de 80 mm (tickets de 42 columnas, pensado para Epson TM-T88V)
- Opcional: báscula Torrey serie PCR conectada por puerto serie/USB

## Compilación

```bash
mvn clean package
# o con el wrapper
./mvnw clean package
```

Genera `target/pos-printer-agent.jar` con todas las dependencias incluidas. El otro archivo,
`target/pos-printer-agent-1.0.0.jar`, no trae dependencias: no lo uses.

## Configuración

Edita `config.properties`. Las variables de entorno tienen prioridad sobre el archivo, y el archivo sobre los valores
por defecto; un valor vacío se ignora.

```properties
# Servidor WebSocket (el agente agrega ?stationId=... a la URL)
# Producción: wss://api.lapasadita.app/ws/printer
server.url=ws://localhost:8080/ws/printer

# ID único de esta estación
station.id=POS1

# Impresora en Linux: ruta del dispositivo
printer.path=/dev/usb/lp0
# Impresora en Windows: nombre de la impresora instalada (Configuración > Impresoras y escáneres)
#printer.name=EPSON TM-T88V Receipt

# Si una impresión tarda más de N segundos, el agente se reinicia (0 lo desactiva)
#printer.stuckExitSeconds=120

# Datos del negocio (encabezado del ticket). Escríbelos sin acentos ni ñ
business.name=LA PASADITA
business.address=Calle 123 Col. Centro
business.phone=1234-5678

# Báscula. Linux: /dev/ttyACM0, Windows: COM3
scale.port=/dev/ttyACM0
scale.enabled=true
scale.autoConnect=true
```

| Propiedad | Variable de entorno | Por defecto | Descripción |
|-----------|---------------------|-------------|-------------|
| `server.url` | `SERVER_URL` | `ws://localhost:8080/ws/printer` | URL del servidor WebSocket |
| `station.id` | `STATION_ID` | `POS1` | ID de la estación |
| `printer.path` | `PRINTER_PATH` | `/dev/usb/lp0` | Dispositivo de la impresora (Linux) |
| `printer.name` | `PRINTER_NAME` | _(vacío)_ | Nombre de la impresora (Windows) |
| `printer.stuckExitSeconds` | `PRINTER_STUCK_EXIT_SECONDS` | `120` | Reinicio por impresión colgada; `0` lo desactiva |
| `business.name` | `BUSINESS_NAME` | `LA PASADITA` | Nombre del negocio |
| `business.address` | `BUSINESS_ADDRESS` | _(vacío)_ | Dirección |
| `business.phone` | `BUSINESS_PHONE` | _(vacío)_ | Teléfono (se imprime como `WhatsApp:`) |
| `scale.port` | `SCALE_PORT` | `/dev/ttyACM0` | Puerto serie de la báscula |
| `scale.enabled` | `SCALE_ENABLED` | `true` | Activa la báscula |
| `scale.autoConnect` | `SCALE_AUTO_CONNECT` | `true` | Conecta la báscula al arrancar |

Notas:

- En Windows la impresora se busca por nombre antes de cada impresión: primero el nombre exacto (sin importar
  mayúsculas) y, si no, una coincidencia parcial solo si es única. Si no la encuentra, el error en el log lista las
  impresoras instaladas para copiar el nombre correcto.
- `scale.enabled=false` desactiva solo la báscula: la API REST del puerto 8081 sigue activa (la usa `/api/station`).

## Ejecución

```bash
# Con config.properties del directorio actual
java -jar target/pos-printer-agent.jar

# Con otro archivo de configuración
java -jar target/pos-printer-agent.jar /ruta/config.properties

# Imprimir página de prueba y salir (también abre el cajón)
java -jar target/pos-printer-agent.jar --test
java -jar target/pos-printer-agent.jar /ruta/config.properties --test
```

## Instalación en Windows

### Opción 1: servicio de Windows (recomendada para las cajas)

Se inicia con Windows y se reinicia solo si falla, incluido el reinicio por impresión colgada. **Requiere Java 17+
instalado en la PC** (en el `PATH` del sistema).

1. Genera el paquete:
   ```bash
   # Desde Linux (requiere curl o wget, y zip)
   ./build-service-package.sh
   # Desde Windows
   build-service-package.bat
   ```
   Se genera `target/pos-printer-agent-service/` (y desde Linux también `target/pos-printer-agent-service.zip`) con
   WinSW (`pos-printer-agent.exe`), el JAR, `config.properties` y los scripts de instalación.
2. Copia la carpeta a la PC de la caja y edita `config.properties` (`printer.name`, `scale.port=COMx`, `station.id`).
3. Ejecuta `install-service.bat` como Administrador.

Comandos útiles desde la carpeta del servicio:

```bat
pos-printer-agent.exe status
pos-printer-agent.exe stop
pos-printer-agent.exe start
pos-printer-agent.exe restart
uninstall-service.bat
```

Los logs quedan en `pos-printer-agent.out.log`, junto al exe (se rota cada 10 MB y se guardan 5 archivos).

### Opción 2: instalador `.exe`

Incluye Java, así que la PC no necesita tenerlo instalado. No se instala como servicio: no arranca solo con Windows ni
se reinicia si falla (si una impresión se cuelga, el agente se cierra y hay que abrirlo de nuevo).

```bat
REM En Windows, con JAVA_HOME apuntando a un JDK 17+
build-installer.bat
REM Genera: target\installer\POS Printer Agent-1.0.0.exe
```

En Linux o macOS, `./build-installer.sh` genera un `.deb` o `.dmg` del mismo modo.

## Permisos en Linux

Para acceder a la impresora USB, agrega el usuario al grupo `lp`:

```bash
sudo usermod -a -G lp $USER
# Cerrar sesión y volver a entrar
```

O da permisos al dispositivo:

```bash
sudo chmod 666 /dev/usb/lp0
```

Para la báscula, agrega el usuario al grupo `dialout`:

```bash
sudo usermod -a -G dialout $USER
# Cerrar sesión y volver a entrar
```

## Flujo de funcionamiento

1. El agente se conecta al backend por WebSocket (`server.url?stationId=POS1`) y envía `CONNECTED`. Si la conexión se
   cae o no hay red al arrancar, reintenta indefinidamente con esperas crecientes de 5 a 30 s.
2. Cuando se crea una venta o un pedido a domicilio, el backend envía el ticket en JSON.
3. El agente procesa los mensajes en orden de llegada, genera los comandos ESC/POS y los envía a la impresora.
4. El agente responde `PRINT_RESULT` al backend con el resultado (y el error, si falló).

Tipos de ticket:

- **Venta en caja**: abre el cajón al imprimirse. En pagos en efectivo muestra lo recibido y el cambio.
- **Pedido a domicilio** (cuando el ticket trae `deliveryOrderId` y `deliveryAddress`): destaca el número de pedido,
  los datos del cliente y la dirección. No abre el cajón.

### Apertura de cajón sin ticket

Cuando se cobra en efectivo sin imprimir ticket, el backend envía el comando
`{"type":"OPEN_DRAWER","timestamp":"..."}` por WebSocket. El agente detecta el `type`
y abre la gaveta (pulso ESC/POS) sin generar ticket.

### Impresión colgada

Si una impresión o una apertura de cajón tarda más de `printer.stuckExitSeconds` (120 s por defecto), por ejemplo
porque la impresora o el spooler de Windows dejaron de responder, el agente termina con código 3. Como servicio,
WinSW lo vuelve a levantar en 10 a 30 s; con el instalador `.exe` hay que abrirlo de nuevo.

## API REST local

El agente expone un servidor HTTP en `http://localhost:8081` (solo loopback, CORS abierto) para integración
con el frontend:

- `GET /api/station` - Devuelve el ID de esta estación: `{"stationId":"POS1"}`
- `GET /api/scale/weight` - Lee el peso actual de la báscula: `{"success":true,"weight":1.250,"unit":"kg","stable":true,"connected":true}`
- `GET /api/scale/status` - Estado de conexión de la báscula
- `POST /api/scale/connect` / `POST /api/scale/disconnect` - Conectar/desconectar báscula
- `GET /api/scale/ports` - Lista los puertos serie disponibles

Si la báscula se desconecta físicamente, el agente deja de leerla; para reconectarla hay que llamar a
`POST /api/scale/connect`.

## Limitaciones conocidas

- Los acentos y la ñ no se imprimen correctamente (por ejemplo, `ñ` sale como `±`): el texto se envía en ISO-8859-1 y
  la impresora usa la página de códigos PC850.
- Solo impresoras de 80 mm (42 columnas por línea).

## Estructura del proyecto

```
pos-printer-agent/
├── pom.xml
├── config.properties                 # Configuración de la estación
├── build-installer.bat / .sh         # Instalador con jpackage
├── build-service-package.bat / .sh   # Paquete de servicio de Windows (WinSW)
├── service/                          # Configuración de WinSW y scripts de instalación
├── README.md
└── src/main/
    ├── resources/simplelogger.properties   # Configuración de logs
    └── java/com/pasadita/pos/
        ├── POSPrinterAgent.java      # Clase principal: conexión WebSocket, cola de impresión, watchdog
        ├── ESCPOSPrinter.java        # Generador de comandos ESC/POS e impresión
        ├── dto/
        │   ├── TicketDTO.java        # DTO del ticket
        │   └── SaleDetailDTO.java    # DTO de detalles de venta
        └── scale/
            ├── TorreyScaleController.java  # Comunicación serial con báscula
            └── ScaleRestServer.java        # API REST local (puerto 8081)
```
