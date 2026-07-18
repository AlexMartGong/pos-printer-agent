package com.pasadita.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pasadita.pos.dto.TicketDTO;
import com.pasadita.pos.scale.ScaleRestServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class POSPrinterAgent extends WebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(POSPrinterAgent.class);

    private static final String DEFAULT_SERVER_URL = "ws://localhost:8080/ws/printer";
    private static final String DEFAULT_STATION_ID = "POS1";
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";
    private static final String DEFAULT_SCALE_PORT = "/dev/ttyACM0";
    private static final boolean DEFAULT_SCALE_ENABLED = true;
    private static final boolean DEFAULT_SCALE_AUTO_CONNECT = true;
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int HARDWARE_POOL_SIZE = 3;
    private static final int HARDWARE_SHUTDOWN_TIMEOUT_SECONDS = 10;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final AtomicInteger POOL_COUNTER = new AtomicInteger(1);

    private final String stationId;
    private final ESCPOSPrinter printer;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    // Pool dedicado a comandos de hardware: libera el hilo de lectura del WebSocket
    // de la E/S bloqueante de impresora/cajón.
    private final ExecutorService hardwareExecutor;
    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public POSPrinterAgent(URI serverUri, String stationId, ESCPOSPrinter printer) {
        super(serverUri);
        this.stationId = stationId;
        this.printer = printer;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.hardwareExecutor = Executors.newFixedThreadPool(HARDWARE_POOL_SIZE,
                r -> new Thread(r, "hardware-pool-" + POOL_COUNTER.getAndIncrement()));
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("Conexión establecida con el servidor");
        log.info("Esperando tickets para imprimir...");

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "CONNECTED");
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
            send(objectMapper.writeValueAsString(msg));
            log.info("Mensaje de conexión enviado al servidor");
        } catch (Exception e) {
            log.error("No se pudo enviar mensaje de conexión: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        log.info("Mensaje recibido del servidor ({} bytes)", message.length());

        try {
            hardwareExecutor.submit(() -> processMessage(message));
        } catch (RejectedExecutionException e) {
            log.warn("Agente en proceso de cierre - mensaje descartado ({} bytes)", message.length());
        }
    }

    /**
     * Procesa un mensaje entrante en un hilo del pool de hardware.
     * El catch de Throwable garantiza que ningún fallo de parseo o de hardware
     * mate un hilo del pool ni afecte al hilo del WebSocket.
     */
    private void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);

            if (rootNode.has("type") && "OPEN_DRAWER".equals(rootNode.get("type").asText())) {
                log.info("Comando OPEN_DRAWER recibido - abriendo cajon");
                printer.openCashDrawer();
                return;
            }

            TicketDTO ticket = objectMapper.treeToValue(rootNode, TicketDTO.class);
            log.info("Ticket #{} parseado - Cliente: {}", ticket.getId(), ticket.getCustomerName());

            printTicket(ticket);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Error parseando JSON: {}", e.getMessage());
            log.debug("Mensaje recibido: {}", message.substring(0, Math.min(200, message.length())));
        } catch (Throwable t) {
            log.error("Error procesando mensaje", t);
        }
    }

    private void printTicket(TicketDTO ticket) {
        boolean success = false;
        String errorMessage = null;

        try {
            if (printer.isAvailable()) {
                log.info("Imprimiendo ticket #{}...", ticket.getId());
                printer.print(ticket);
                success = true;
                log.info("Ticket #{} impreso correctamente", ticket.getId());
            } else {
                errorMessage = "Impresora no disponible o desconectada";
                log.error(errorMessage);
            }
        } catch (IOException e) {
            errorMessage = "Error de I/O al imprimir: " + e.getMessage();
            log.error("Error de I/O al imprimir ticket #{}", ticket.getId(), e);
        } catch (ESCPOSPrinter.PrinterException e) {
            errorMessage = "Error de impresora: " + e.getMessage();
            log.error("Error de impresora al imprimir ticket #{}", ticket.getId(), e);
        } catch (Exception e) {
            errorMessage = "Error inesperado al imprimir: " + e.getMessage();
            log.error("Error inesperado al imprimir ticket #{}", ticket.getId(), e);
        }

        sendPrintConfirmation(ticket.getId(), success, errorMessage);
    }

    private void sendPrintConfirmation(Long ticketId, boolean success, String error) {
        if (!isOpen()) {
            log.warn("Conexión cerrada - no se pudo enviar confirmación del ticket #{} (resultado: {})",
                    ticketId, success ? "OK" : "FALLO");
            return;
        }

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PRINT_RESULT");
            msg.put("ticketId", ticketId);
            msg.put("success", success);
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
            if (error != null) {
                msg.put("error", error);
            }

            send(objectMapper.writeValueAsString(msg));
            log.info("Confirmación enviada - Ticket #{}: {}", ticketId, success ? "OK" : "FALLO");
        } catch (Exception e) {
            log.error("No se pudo enviar confirmación del ticket #{}", ticketId, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String closeBy = remote ? "servidor" : "cliente";
        log.warn("Conexión cerrada por {} - Código: {}, Razón: {}", closeBy, code, reason);

        if (running) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception ex) {
        log.error("Error de WebSocket", ex);

        if (running && !isOpen()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) return;

        log.info("Reintentando conexión en {} segundos...", RECONNECT_DELAY_SECONDS);
        scheduler.schedule(() -> {
            if (running) {
                log.info("Intentando reconexión...");
                try {
                    reconnect();
                } catch (Exception e) {
                    log.error("Error en reconexión: {}", e.getMessage());
                    scheduleReconnect();
                }
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown() {
        log.info("Deteniendo agente...");
        running = false;

        // Drenar el pool de hardware antes de cerrar el WebSocket: los prints
        // encolados terminan y sus confirmaciones aún pueden enviarse.
        hardwareExecutor.shutdown();
        try {
            if (!hardwareExecutor.awaitTermination(HARDWARE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Tareas de hardware no terminaron a tiempo - forzando cierre");
                hardwareExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            hardwareExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

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

        shutdownLatch.countDown();
        log.info("Agente detenido correctamente");
    }

    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    public static void main(String[] args) {
        log.info("============================================");
        log.info("    POS PRINTER AGENT - LA PASADITA");
        log.info("============================================");

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

        log.info("Configuración:");
        log.info("  Station ID: {}", stationId);
        log.info("  Server URL: {}", fullUrl);
        log.info("  Conexión segura (WSS): {}", serverUrl.startsWith("wss://"));
        log.info("  Sistema Operativo: {}", ESCPOSPrinter.isWindows() ? "Windows" : "Linux");
        if (ESCPOSPrinter.isWindows()) {
            log.info("  Printer Name (Windows): {}", printerName.isEmpty() ? "(no configurado)" : printerName);
        } else {
            log.info("  Printer Path (Linux): {}", printerPath);
        }
        log.info("  Business: {}", businessName);
        log.info("  Scale Port: {}", scalePort);
        log.info("  Scale Enabled: {}", scaleEnabled);
        log.info("  Scale Auto-Connect: {}", scaleAutoConnect);
        log.info("============================================");

        for (String arg : args) {
            if ("--test".equals(arg) || "-t".equals(arg)) {
                ESCPOSPrinter testPrinter = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
                try {
                    log.info("Imprimiendo página de prueba...");
                    testPrinter.printTestPage();
                    log.info("Página de prueba impresa correctamente");
                } catch (IOException e) {
                    log.error("Error imprimiendo página de prueba: {}", e.getMessage());
                } catch (ESCPOSPrinter.PrinterException e) {
                    log.error("Error de impresora: {}", e.getMessage());
                }
                return;
            }
        }

        ESCPOSPrinter printer = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
        log.info("Impresora disponible: {}", printer.isAvailable());

        ScaleRestServer scaleServer = null;
        try {
            scaleServer = new ScaleRestServer(scalePort, scaleEnabled, scaleAutoConnect, stationId);
            scaleServer.start();
            log.info("Servidor REST iniciado en http://localhost:8081");
        } catch (Exception e) {
            log.error("Error al iniciar servidor REST: {}", e.getMessage());
        }

        final ScaleRestServer finalScaleServer = scaleServer;

        try {
            URI serverUri = new URI(fullUrl);
            POSPrinterAgent agent = new POSPrinterAgent(serverUri, stationId, printer);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Señal de cierre recibida");
                agent.shutdown();
                if (finalScaleServer != null) {
                    log.info("Deteniendo servidor REST de báscula...");
                    finalScaleServer.stop();
                }
            }));

            log.info("Conectando al servidor...");
            agent.connect();

            try {
                agent.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (URISyntaxException e) {
            log.error("URL inválida: {}", fullUrl);
            log.error("Detalle: {}", e.getMessage());
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
            log.info("Configuración cargada desde: {}", configFile);
        } catch (IOException e) {
            log.info("Archivo de configuración no encontrado, usando ENV/defaults");
        }

        return props;
    }
}
