# POS Printer Agent

Agente de impresión para terminales POS que conecta via WebSocket al backend de La Pasadita y envía tickets a impresoras térmicas ESC/POS.

## Requisitos

- Java 17 o superior
- Maven 3.6+
- Impresora térmica ESC/POS (58mm o 80mm)

## Compilación

```bash
mvn clean package
```

Esto genera el archivo `target/pos-printer-agent.jar` con todas las dependencias incluidas.

## Configuración

Editar el archivo `config.properties`:

```properties
# URL del servidor WebSocket
server.url=ws://localhost:8080/ws/printer

# ID único de esta estación
station.id=POS1

# Ruta de la impresora
# Linux: /dev/usb/lp0
# Windows: COM1 o LPT1
printer.path=/dev/usb/lp0

# Información del negocio
business.name=LA PASADITA
business.address=Dirección del local
business.phone=1234-5678
```

## Ejecución

```bash
# Ejecutar con configuración por defecto
java -jar target/pos-printer-agent.jar

# Ejecutar con archivo de configuración específico
java -jar target/pos-printer-agent.jar /ruta/config.properties

# Imprimir página de prueba
java -jar target/pos-printer-agent.jar --test
```

## Permisos en Linux

Para acceder a la impresora USB en Linux, agregar el usuario al grupo `lp`:

```bash
sudo usermod -a -G lp $USER
# Cerrar sesión y volver a entrar
```

O dar permisos al dispositivo:

```bash
sudo chmod 666 /dev/usb/lp0
```

## Flujo de funcionamiento

1. El agente se conecta al backend via WebSocket
2. Cuando se crea una venta en el backend, se envía el ticket JSON al agente
3. El agente recibe el JSON, lo parsea y genera los comandos ESC/POS
4. Los comandos se envían a la impresora térmica
5. El agente envía confirmación de impresión al backend

### Apertura de cajón sin ticket

Cuando se cobra en efectivo sin imprimir ticket, el backend envía el comando
`{"type":"OPEN_DRAWER","timestamp":"..."}` por WebSocket. El agente detecta el `type`
y abre la gaveta (pulso ESC/POS) sin generar ticket.

## API REST local

El agente expone un servidor HTTP en `http://localhost:8081` (solo loopback) para integración
con el frontend:

- `GET /api/station` - Devuelve el ID de esta estación: `{"stationId":"POS1"}`
- `GET /api/scale/weight` - Lee el peso actual de la báscula
- `GET /api/scale/status` - Estado de conexión de la báscula
- `POST /api/scale/connect` / `POST /api/scale/disconnect` - Conectar/desconectar báscula
- `GET /api/scale/ports` - Lista los puertos serie disponibles

## Estructura del proyecto

```
pos-printer-agent/
├── pom.xml
├── config.properties
├── README.md
└── src/main/java/com/pasadita/pos/
    ├── POSPrinterAgent.java      # Clase principal, cliente WebSocket
    ├── ESCPOSPrinter.java        # Generador de comandos ESC/POS
    ├── dto/
    │   ├── TicketDTO.java        # DTO del ticket
    │   └── SaleDetailDTO.java    # DTO de detalles de venta
    └── scale/
        ├── TorreyScaleController.java  # Comunicación serial con báscula
        └── ScaleRestServer.java        # API REST local (puerto 8081)
```

