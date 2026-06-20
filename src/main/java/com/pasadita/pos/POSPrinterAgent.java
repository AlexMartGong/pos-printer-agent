package com.pasadita.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pasadita.pos.dto.TicketDTO;
import com.pasadita.pos.scale.ScaleRestServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class POSPrinterAgent extends WebSocketClient {
    private static final String DEFAULT_SERVER_URL = "ws://localhost:8080/ws/printer";
    private static final String DEFAULT_STATION_ID = "POS1";
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";
    private static final String DEFAULT_SCALE_PORT = "/dev/ttyACM0";
    private static final boolean DEFAULT_SCALE_ENABLED = true;
    private static final boolean DEFAULT_SCALE_AUTO_CONNECT = true;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String stationId;
    private final ESCPOSPrinter printer;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public POSPrinterAgent(URI serverUri, String stationId, ESCPOSPrinter printer) {
        super(serverUri);
        this.stationId = stationId;
        this.printer = printer;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log("INFO", "Conexión establecida con el servidor");
        log("INFO", "Esperando tickets para imprimir...");

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "CONNECTED");
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(LOG_FORMAT));
            send(objectMapper.writeValueAsString(msg));
            log("INFO", "Mensaje de conexión enviado al servidor");
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar mensaje de conexión: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        log("INFO", "Mensaje recibido del servidor (" + message.length() + " bytes)");

        try {
            JsonNode rootNode = objectMapper.readTree(message);

            if (rootNode.has("type") && "OPEN_DRAWER".equals(rootNode.get("type").asText())) {
                log("INFO", "Comando OPEN_DRAWER recibido - abriendo cajon");
                printer.openCashDrawer();
                return;
            }

            TicketDTO ticket = objectMapper.treeToValue(rootNode, TicketDTO.class);
            log("INFO", "Ticket #" + ticket.getId() + " parseado - Cliente: " + ticket.getCustomerName());

            printTicket(ticket);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log("ERROR", "Error parseando JSON: " + e.getMessage());
            log("DEBUG", "Mensaje recibido: " + message.substring(0, Math.min(200, message.length())));
        } catch (Exception e) {
            log("ERROR", "Error procesando mensaje: " + e.getMessage());
            e.fillInStackTrace();
        }
    }

    private void printTicket(TicketDTO ticket) {
        boolean success = false;
        String errorMessage = null;

        try {
            if (printer.isAvailable()) {
                log("INFO", "Imprimiendo ticket #" + ticket.getId() + "...");
                printer.print(ticket);
                success = true;
                log("INFO", "Ticket #" + ticket.getId() + " impreso correctamente");
            } else {
                errorMessage = "Impresora no disponible o desconectada";
                log("ERROR", errorMessage);
            }
        } catch (IOException e) {
            errorMessage = "Error de I/O al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (ESCPOSPrinter.PrinterException e) {
            errorMessage = "Error de impresora: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (Exception e) {
            errorMessage = "Error inesperado al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
            e.fillInStackTrace();
        }

        sendPrintConfirmation(ticket.getId(), success, errorMessage);
    }

    private void sendPrintConfirmation(Long ticketId, boolean success, String error) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PRINT_RESULT");
            msg.put("ticketId", ticketId);
            msg.put("success", success);
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(LOG_FORMAT));
            if (error != null) {
                msg.put("error", error);
            }

            send(objectMapper.writeValueAsString(msg));
            log("INFO", "Confirmación enviada - Ticket #" + ticketId + ": " + (success ? "OK" : "FALLO"));
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar confirmación: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String closeBy = remote ? "servidor" : "cliente";
        log("WARN", "Conexión cerrada por " + closeBy + " - Código: " + code + ", Razón: " + reason);

        if (running) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception ex) {
        log("ERROR", "Error de WebSocket: " + ex.getMessage());

        if (ex.getCause() != null) {
            log("ERROR", "Causa: " + ex.getCause().getMessage());
        }

        if (running && !isOpen()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) return;

        log("INFO", "Reintentando conexión en " + RECONNECT_DELAY_SECONDS + " segundos...");
        scheduler.schedule(() -> {
            if (running) {
                log("INFO", "Intentando reconexión...");
                try {
                    reconnect();
                } catch (Exception e) {
                    log("ERROR", "Error en reconexión: " + e.getMessage());
                    scheduleReconnect();
                }
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown() {
        log("INFO", "Deteniendo agente...");
        running = false;
        shutdownLatch.countDown();

        try {
            closeBlocking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log("INFO", "Agente detenido correctamente");
    }

    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }

    public static void main(String[] args) {
        log("INFO", "============================================");
        log("INFO", "    POS PRINTER AGENT - LA PASADITA");
        log("INFO", "============================================");

        Properties fileConfig = loadPropertiesFile(args.length > 0 ? args[0] : "config.properties");

        String serverUrl = getConfig("SERVER_URL", "server.url", fileConfig, DEFAULT_SERVER_URL);
        String stationId = getConfig("STATION_ID", "station.id", fileConfig, DEFAULT_STATION_ID);
        String printerPath = getConfig("PRINTER_PATH", "printer.path", fileConfig, DEFAULT_PRINTER_PATH);
        String printerName = getConfig("PRINTER_NAME", "printer.name", fileConfig, "");
        String businessName = getConfig("BUSINESS_NAME", "business.name", fileConfig, "LA PASADITA");
        String businessAddress = getConfig("BUSINESS_ADDRESS", "business.address", fileConfig, "");
        String businessPhone = getConfig("BUSINESS_PHONE", "business.phone", fileConfig, "");

        String scalePort = getConfig("SCALE_PORT", "scale.port", fileConfig, DEFAULT_SCALE_PORT);
        boolean scaleEnabled = Boolean.parseBoolean(getConfig("SCALE_ENABLED", "scale.enabled", fileConfig, String.valueOf(DEFAULT_SCALE_ENABLED)));
        boolean scaleAutoConnect = Boolean.parseBoolean(getConfig("SCALE_AUTO_CONNECT", "scale.autoConnect", fileConfig, String.valueOf(DEFAULT_SCALE_AUTO_CONNECT)));

        String fullUrl = serverUrl + "?stationId=" + URLEncoder.encode(stationId, StandardCharsets.UTF_8);

        log("INFO", "Configuración:");
        log("INFO", "  Station ID: " + stationId);
        log("INFO", "  Server URL: " + fullUrl);
        log("INFO", "  Conexión segura (WSS): " + serverUrl.startsWith("wss://"));
        log("INFO", "  Sistema Operativo: " + (ESCPOSPrinter.isWindows() ? "Windows" : "Linux"));
        if (ESCPOSPrinter.isWindows()) {
            log("INFO", "  Printer Name (Windows): " + (printerName.isEmpty() ? "(no configurado)" : printerName));
        } else {
            log("INFO", "  Printer Path (Linux): " + printerPath);
        }
        log("INFO", "  Business: " + businessName);
        log("INFO", "  Scale Port: " + scalePort);
        log("INFO", "  Scale Enabled: " + scaleEnabled);
        log("INFO", "  Scale Auto-Connect: " + scaleAutoConnect);
        log("INFO", "============================================");

        for (String arg : args) {
            if ("--test".equals(arg) || "-t".equals(arg)) {
                ESCPOSPrinter testPrinter = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
                try {
                    log("INFO", "Imprimiendo página de prueba...");
                    testPrinter.printTestPage();
                    log("INFO", "Página de prueba impresa correctamente");
                } catch (IOException e) {
                    log("ERROR", "Error imprimiendo página de prueba: " + e.getMessage());
                } catch (ESCPOSPrinter.PrinterException e) {
                    log("ERROR", "Error de impresora: " + e.getMessage());
                }
                return;
            }
        }

        ESCPOSPrinter printer = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
        log("INFO", "Impresora disponible: " + printer.isAvailable());

        ScaleRestServer scaleServer = null;
        try {
            scaleServer = new ScaleRestServer(scalePort, scaleEnabled, scaleAutoConnect, stationId);
            scaleServer.start();
            log("INFO", "Servidor REST iniciado en http://localhost:8081");
        } catch (Exception e) {
            log("ERROR", "Error al iniciar servidor REST: " + e.getMessage());
        }

        final ScaleRestServer finalScaleServer = scaleServer;

        try {
            URI serverUri = new URI(fullUrl);
            POSPrinterAgent agent = new POSPrinterAgent(serverUri, stationId, printer);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("INFO", "Señal de cierre recibida");
                agent.shutdown();
                if (finalScaleServer != null) {
                    log("INFO", "Deteniendo servidor REST de báscula...");
                    finalScaleServer.stop();
                }
            }));

            log("INFO", "Conectando al servidor...");
            agent.connect();

            try {
                agent.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (URISyntaxException e) {
            log("ERROR", "URL inválida: " + fullUrl);
            log("ERROR", "Detalle: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String getConfig(String envKey, String propKey, Properties props, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }

        String propValue = props.getProperty(propKey);
        if (propValue != null && !propValue.isEmpty()) {
            return propValue;
        }

        return defaultValue;
    }

    private static Properties loadPropertiesFile(String configFile) {
        Properties props = new Properties();

        try (InputStream input = new FileInputStream(configFile)) {
            props.load(input);
            log("INFO", "Configuración cargada desde: " + configFile);
        } catch (IOException e) {
            log("INFO", "Archivo de configuración no encontrado, usando ENV/defaults");
        }

        return props;
    }
}

