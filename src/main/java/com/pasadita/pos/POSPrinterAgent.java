package com.pasadita.pos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pasadita.pos.dto.TicketDTO;
import com.pasadita.pos.scale.ScaleRestServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agente de impresión: mantiene la conexión WebSocket con el backend y procesa tickets y
 * comandos OPEN_DRAWER.
 *
 * <p>Hilos:
 * <ul>
 *   <li>{@code ws-reconnect}: único hilo que crea y conecta clientes WebSocket. Cada intento usa un
 *   cliente nuevo (Java-WebSocket no permite reconectar desde sus propios hilos y su
 *   {@code reconnect()} espera sin timeout). Una guarda impide que haya dos intentos programados.</li>
 *   <li>Hilos de Java-WebSocket: solo encolan los mensajes recibidos.</li>
 *   <li>{@code print-worker}: un solo hilo procesa los mensajes en orden de llegada.</li>
 *   <li>{@code print-watchdog}: avisa si una impresión tarda y, pasado el límite, reinicia el proceso.</li>
 * </ul>
 */
public class POSPrinterAgent {
    private static final Logger log = LoggerFactory.getLogger(POSPrinterAgent.class);

    private static final String DEFAULT_SERVER_URL = "ws://localhost:8080/ws/printer";
    private static final String DEFAULT_STATION_ID = "POS1";
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";
    private static final String DEFAULT_SCALE_PORT = "/dev/ttyACM0";
    private static final boolean DEFAULT_SCALE_ENABLED = true;
    private static final boolean DEFAULT_SCALE_AUTO_CONNECT = true;
    private static final int DEFAULT_PRINTER_STUCK_EXIT_SECONDS = 120;

    // Reconexión: 5 → 10 → 20 → 30 s (tope), reintentos indefinidos
    private static final int RECONNECT_INITIAL_DELAY_SECONDS = 5;
    private static final int RECONNECT_MAX_DELAY_SECONDS = 30;
    private static final int CONNECTION_CHECK_INTERVAL_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 20;
    // Java-WebSocket cierra con 1006 si no recibe pong en 1.5 × este valor (lo revisa cada este valor)
    private static final int CONNECTION_LOST_TIMEOUT_SECONDS = 30;

    private static final int SLOW_TASK_WARN_SECONDS = 15;
    private static final int WORKER_SHUTDOWN_TIMEOUT_SECONDS = 8;
    private static final int STUCK_EXIT_CODE = 3;
    private static final int STUCK_HALT_GRACE_SECONDS = 20;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final URI serverUri;
    private final String stationId;
    private final ESCPOSPrinter printer;
    private final int stuckExitSeconds;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService connectionScheduler;
    private final ThreadPoolExecutor messageWorker;
    private final ScheduledExecutorService printWatchdog;

    private final AtomicBoolean connectScheduled = new AtomicBoolean(false);
    private final AtomicInteger attemptsSinceConnected = new AtomicInteger(0);
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    // Solo lo escribe el hilo ws-reconnect; los callbacks lo leen para ignorar clientes descartados
    private volatile AgentSocket current;
    private volatile long disconnectedSinceMillis = System.currentTimeMillis();
    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public POSPrinterAgent(URI serverUri, String stationId, ESCPOSPrinter printer, int stuckExitSeconds) {
        this.serverUri = serverUri;
        this.stationId = stationId;
        this.printer = printer;
        this.stuckExitSeconds = stuckExitSeconds;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.connectionScheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("ws-reconnect"));
        this.messageWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreadFactory("print-worker"));
        this.printWatchdog = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("print-watchdog"));
    }

    /**
     * Programa el primer intento de conexión y la verificación periódica. Si la red todavía no
     * está lista (arranque de Windows), el agente simplemente sigue reintentando.
     */
    public void start() {
        connectionScheduler.scheduleWithFixedDelay(this::ensureConnected,
                CONNECTION_CHECK_INTERVAL_SECONDS, CONNECTION_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduleConnect(0, "arranque");
    }

    // ------------------------------------------------------------------
    // Conexión y reconexión (todo intento corre en el hilo ws-reconnect)
    // ------------------------------------------------------------------

    private void scheduleConnect(String reason) {
        scheduleConnect(nextReconnectDelaySeconds(), reason);
    }

    private void scheduleConnect(long delaySeconds, String reason) {
        if (!running) return;
        if (!connectScheduled.compareAndSet(false, true)) {
            log.debug("Ya hay un intento de conexión programado - se ignora ({})", reason);
            return;
        }
        try {
            connectionScheduler.schedule(this::connectAttempt, delaySeconds, TimeUnit.SECONDS);
            if (delaySeconds > 0) {
                log.info("Próximo intento de conexión en {} s ({})", delaySeconds, reason);
            }
        } catch (RejectedExecutionException e) {
            connectScheduled.set(false);
        }
    }

    private long nextReconnectDelaySeconds() {
        int failedAttempts = Math.min(attemptsSinceConnected.get(), 3);
        return Math.min(RECONNECT_INITIAL_DELAY_SECONDS << failedAttempts, RECONNECT_MAX_DELAY_SECONDS);
    }

    private void connectAttempt() {
        connectScheduled.set(false);
        if (!running) return;

        try {
            AgentSocket previous = current;
            if (previous != null && previous.isOpen()) {
                log.debug("Ya conectado - intento de conexión omitido");
                return;
            }
            // Primero se desvincula: los callbacks tardíos del cliente anterior quedan ignorados
            current = null;
            discard(previous);

            int attempt = attemptsSinceConnected.incrementAndGet();
            AgentSocket client = new AgentSocket(serverUri);
            current = client;
            log.info("Intento de conexión #{} a {}", attempt, serverUri);
            client.connect();

            boolean settled = client.awaitSettled(HANDSHAKE_TIMEOUT_SECONDS);
            if (client.isOpen()) {
                return;
            }
            if (!settled) {
                // Sin respuesta (TCP, TLS o handshake HTTP colgados): se aborta cerrando el socket
                log.warn("Intento de conexión #{} sin respuesta en {} s - abortando", attempt, HANDSHAKE_TIMEOUT_SECONDS);
                discard(client);
            }
            // Si el intento falló, onClose ya programó el siguiente; la guarda evita duplicarlo
            scheduleConnect("intento #" + attempt + " fallido");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("Error inesperado en el intento de conexión", t);
            scheduleConnect("error inesperado");
        }
    }

    /**
     * Red de seguridad: si no hay conexión abierta ni intento programado, programa uno.
     * Corre en el mismo hilo que los intentos, así que nunca coincide con uno en curso.
     */
    private void ensureConnected() {
        try {
            if (!running) return;
            AgentSocket client = current;
            if ((client == null || !client.isOpen()) && !connectScheduled.get()) {
                log.warn("Sin conexión y sin intento programado - programando reconexión");
                scheduleConnect("verificación periódica");
            }
        } catch (Throwable t) {
            log.error("Error en la verificación periódica de la conexión", t);
        }
    }

    private void discard(AgentSocket client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Error cerrando cliente WebSocket descartado: {}", e.toString());
        }
        if (!client.isOpen()) {
            // close() no hace nada si el handshake no terminó: cerrar el socket aborta el intento
            try {
                Socket socket = client.getSocket();
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception e) {
                log.debug("Error cerrando socket descartado: {}", e.toString());
            }
        }
    }

    private void handleOpen(AgentSocket client) {
        if (client != current || !running) {
            log.warn("Se abrió una conexión descartada - cerrándola");
            client.close();
            return;
        }

        int attempts = attemptsSinceConnected.getAndSet(0);
        long downSeconds = (System.currentTimeMillis() - disconnectedSinceMillis) / 1000;
        log.info("Conectado al servidor {} (intento #{}, sin conexión durante {} s) - esperando tickets",
                serverUri, attempts, downSeconds);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "CONNECTED");
        msg.put("stationId", stationId);
        msg.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
        sendJson(client, msg, "CONNECTED");
    }

    private void handleClose(AgentSocket client, int code, String reason, boolean remote) {
        if (client != current) {
            log.debug("Cierre de una conexión descartada ignorado (código {})", code);
            return;
        }

        if (client.opened) {
            disconnectedSinceMillis = System.currentTimeMillis();
            log.warn("Conexión cerrada - código {}, motivo '{}', remota={} (estuvo conectada {} s)",
                    code, reason, remote, (disconnectedSinceMillis - client.openedAtMillis) / 1000);
        } else {
            log.warn("Intento de conexión fallido - código {}, motivo '{}', remota={}", code, reason, remote);
        }

        // Único punto (junto con la red de seguridad) que programa reconexiones: Java-WebSocket
        // siempre llama a onClose después de un error, incluido un connect fallido
        if (running) {
            scheduleConnect("cierre con código " + code);
        }
    }

    private void handleError(AgentSocket client, Exception ex) {
        if (client != current) {
            log.debug("Error de una conexión descartada ignorado: {}", ex.toString());
            return;
        }
        if (client.opened) {
            log.error("Error de WebSocket", ex);
        } else {
            log.warn("Error al conectar: {}", ex.toString());
        }
    }

    // ------------------------------------------------------------------
    // Mensajes (el hilo del WebSocket solo encola; print-worker procesa en orden)
    // ------------------------------------------------------------------

    private void handleMessage(String message) {
        long receivedAtNanos = System.nanoTime();
        try {
            messageWorker.execute(() -> processMessage(message, receivedAtNanos));
            log.info("Mensaje recibido ({} bytes) - encolado, pendientes: {}",
                    message.length(), messageWorker.getQueue().size());
        } catch (RejectedExecutionException e) {
            log.warn("Agente en proceso de cierre - mensaje descartado ({} bytes)", message.length());
        }
    }

    private void processMessage(String message, long receivedAtNanos) {
        long waitedMillis = elapsedMillis(receivedAtNanos);
        try {
            JsonNode rootNode = objectMapper.readTree(message);

            if ("OPEN_DRAWER".equals(rootNode.path("type").asText())) {
                log.info("Procesando mensaje tipo=OPEN_DRAWER (esperó {} ms en cola)", waitedMillis);
                openCashDrawer();
                return;
            }

            TicketDTO ticket = objectMapper.treeToValue(rootNode, TicketDTO.class);
            log.info("Procesando mensaje tipo=TICKET ticketId={} cliente='{}' (esperó {} ms en cola)",
                    ticket.getId(), ticket.getCustomerName(), waitedMillis);
            printTicket(ticket);

        } catch (JsonProcessingException e) {
            log.error("Mensaje con JSON inválido descartado: {}", e.getOriginalMessage());
            log.debug("Mensaje recibido: {}", message.substring(0, Math.min(200, message.length())));
        } catch (Throwable t) {
            log.error("Error procesando mensaje", t);
        }
    }

    private void openCashDrawer() {
        try {
            runTimed("Apertura de cajón", printer::openCashDrawer);
        } catch (Exception e) {
            // runTimed ya registró el fallo; OPEN_DRAWER no tiene confirmación en el protocolo
        }
    }

    private void printTicket(TicketDTO ticket) {
        boolean success = false;
        String errorMessage = null;

        try {
            runTimed("Impresión del ticket #" + ticket.getId(), () -> printer.print(ticket));
            success = true;
        } catch (ESCPOSPrinter.PrinterException e) {
            errorMessage = "Error de impresora: " + e.getMessage();
        } catch (IOException e) {
            errorMessage = "Error de I/O al imprimir: " + e.getMessage();
        } catch (Exception e) {
            errorMessage = "Error inesperado al imprimir: " + e.getMessage();
        }

        sendPrintConfirmation(ticket.getId(), success, errorMessage);
    }

    @FunctionalInterface
    private interface HardwareTask {
        void run() throws Exception;
    }

    /**
     * Ejecuta una operación de impresora registrando inicio, fin y duración. Mientras no termine,
     * el watchdog avisa cada {@value #SLOW_TASK_WARN_SECONDS} s.
     */
    private void runTimed(String description, HardwareTask task) throws Exception {
        log.info("{} - inicio", description);
        long startNanos = System.nanoTime();
        ScheduledFuture<?> watchdog = scheduleSlowTaskWatchdog(description, startNanos);
        try {
            task.run();
            log.info("{} - fin OK en {} ms", description, elapsedMillis(startNanos));
        } catch (Exception e) {
            log.error("{} - FALLÓ en {} ms: {}", description, elapsedMillis(startNanos), e.getMessage());
            if (!(e instanceof ESCPOSPrinter.PrinterException) && !(e instanceof IOException)) {
                log.error("Detalle del error", e);
            }
            throw e;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> scheduleSlowTaskWatchdog(String description, long startNanos) {
        try {
            return printWatchdog.scheduleAtFixedRate(() -> checkSlowTask(description, startNanos),
                    SLOW_TASK_WARN_SECONDS, SLOW_TASK_WARN_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private void checkSlowTask(String description, long startNanos) {
        long elapsedSeconds = elapsedMillis(startNanos) / 1000;
        int pending = messageWorker.getQueue().size();

        if (stuckExitSeconds > 0 && elapsedSeconds >= stuckExitSeconds) {
            log.error("{} lleva {} s sin terminar (límite printer.stuckExitSeconds={} s, {} mensajes en espera). "
                            + "La impresora o el spooler no responden: se reinicia el agente (código {})",
                    description, elapsedSeconds, stuckExitSeconds, pending, STUCK_EXIT_CODE);
            exitForRestart();
            return;
        }
        log.warn("{} lleva {} s sin terminar - ¿impresora o spooler colgados? Mensajes en espera: {}",
                description, elapsedSeconds, pending);
    }

    /**
     * Termina el proceso con código distinto de 0 para que WinSW lo vuelva a levantar
     * ({@code onfailure}). Si el cierre ordenado no termina a tiempo, se fuerza con halt.
     */
    private void exitForRestart() {
        if (!exitRequested.compareAndSet(false, true)) return;

        Thread halt = new Thread(() -> {
            try {
                Thread.sleep(STUCK_HALT_GRACE_SECONDS * 1000L);
            } catch (InterruptedException ignored) {
                // se fuerza la salida de todos modos
            }
            log.error("El cierre ordenado no terminó en {} s - forzando salida", STUCK_HALT_GRACE_SECONDS);
            Runtime.getRuntime().halt(STUCK_EXIT_CODE);
        }, "stuck-exit-halt");
        halt.setDaemon(true);
        halt.start();

        new Thread(() -> System.exit(STUCK_EXIT_CODE), "stuck-exit").start();
    }

    private void sendPrintConfirmation(Long ticketId, boolean success, String error) {
        AgentSocket client = current;
        if (client == null || !client.isOpen()) {
            log.warn("Sin conexión - no se pudo enviar confirmación del ticket #{} (resultado: {})",
                    ticketId, success ? "OK" : "FALLO");
            return;
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "PRINT_RESULT");
        msg.put("ticketId", ticketId);
        msg.put("success", success);
        msg.put("stationId", stationId);
        msg.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
        if (error != null) {
            msg.put("error", error);
        }

        if (sendJson(client, msg, "PRINT_RESULT")) {
            log.info("Confirmación enviada - Ticket #{}: {}", ticketId, success ? "OK" : "FALLO");
        }
    }

    private boolean sendJson(AgentSocket client, Map<String, Object> msg, String type) {
        try {
            client.send(objectMapper.writeValueAsString(msg));
            return true;
        } catch (Exception e) {
            log.error("No se pudo enviar el mensaje {}: {}", type, e.toString());
            return false;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public void shutdown() {
        log.info("Deteniendo agente...");
        running = false;
        connectionScheduler.shutdownNow();

        // Drenar el worker antes de cerrar el WebSocket: los mensajes encolados terminan y sus
        // confirmaciones aún pueden enviarse
        messageWorker.shutdown();
        try {
            if (!messageWorker.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Mensajes pendientes no terminaron en {} s - forzando cierre", WORKER_SHUTDOWN_TIMEOUT_SECONDS);
                messageWorker.shutdownNow();
            }
        } catch (InterruptedException e) {
            messageWorker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        printWatchdog.shutdownNow();

        AgentSocket client = current;
        if (client != null) {
            discard(client);
        }

        shutdownLatch.countDown();
        log.info("Agente detenido");
    }

    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * Cliente WebSocket de un solo uso: cada intento de conexión crea uno nuevo. Los callbacks
     * se delegan al agente, que ignora los de clientes descartados.
     */
    private final class AgentSocket extends WebSocketClient {
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile boolean opened;
        private volatile long openedAtMillis;

        AgentSocket(URI uri) {
            super(uri, new Draft_6455(), null, CONNECT_TIMEOUT_MILLIS);
            setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SECONDS);
            setDaemon(true);
        }

        boolean awaitSettled(long timeoutSeconds) throws InterruptedException {
            return settled.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            opened = true;
            openedAtMillis = System.currentTimeMillis();
            try {
                handleOpen(this);
            } finally {
                settled.countDown();
            }
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            try {
                handleClose(this, code, reason, remote);
            } finally {
                settled.countDown();
            }
        }

        @Override
        public void onError(Exception ex) {
            handleError(this, ex);
        }
    }

    public static void main(String[] args) {
        log.info("============================================");
        log.info("    POS PRINTER AGENT - LA PASADITA");
        log.info("============================================");

        // El archivo de configuración es el primer argumento que no sea --test/-t, en cualquier orden
        String configFile = "config.properties";
        boolean configFileGiven = false;
        boolean testMode = false;
        for (String arg : args) {
            if (isTestFlag(arg)) {
                testMode = true;
            } else if (!configFileGiven) {
                configFile = arg;
                configFileGiven = true;
            }
        }

        Properties fileConfig = loadPropertiesFile(configFile);

        String serverUrl = getConfig("SERVER_URL", "server.url", fileConfig, DEFAULT_SERVER_URL);
        String stationId = getConfig("STATION_ID", "station.id", fileConfig, DEFAULT_STATION_ID);
        String printerPath = getConfig("PRINTER_PATH", "printer.path", fileConfig, DEFAULT_PRINTER_PATH);
        String printerName = getConfig("PRINTER_NAME", "printer.name", fileConfig, "");
        String businessName = getConfig("BUSINESS_NAME", "business.name", fileConfig, "LA PASADITA");
        String businessAddress = getConfig("BUSINESS_ADDRESS", "business.address", fileConfig, "");
        String businessPhone = getConfig("BUSINESS_PHONE", "business.phone", fileConfig, "");
        int stuckExitSeconds = parseNonNegativeInt(
                getConfig("PRINTER_STUCK_EXIT_SECONDS", "printer.stuckExitSeconds", fileConfig,
                        String.valueOf(DEFAULT_PRINTER_STUCK_EXIT_SECONDS)),
                "printer.stuckExitSeconds", DEFAULT_PRINTER_STUCK_EXIT_SECONDS);

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
        log.info("  Reinicio por impresión colgada: {}",
                stuckExitSeconds > 0 ? stuckExitSeconds + " s" : "desactivado");
        log.info("  Business: {}", businessName);
        log.info("  Scale Port: {}", scalePort);
        log.info("  Scale Enabled: {}", scaleEnabled);
        log.info("  Scale Auto-Connect: {}", scaleAutoConnect);
        log.info("============================================");

        if (testMode) {
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
            POSPrinterAgent agent = new POSPrinterAgent(serverUri, stationId, printer, stuckExitSeconds);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Señal de cierre recibida");
                agent.shutdown();
                if (finalScaleServer != null) {
                    log.info("Deteniendo servidor REST de báscula...");
                    finalScaleServer.stop();
                }
            }));

            agent.start();

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

    private static boolean isTestFlag(String arg) {
        return "--test".equals(arg) || "-t".equals(arg);
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

    private static int parseNonNegativeInt(String value, String key, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // se usa el valor por defecto
        }
        log.warn("Valor inválido para {}: '{}' - usando {}", key, value, defaultValue);
        return defaultValue;
    }

    private static Properties loadPropertiesFile(String configFile) {
        Properties props = new Properties();

        try (InputStream input = new FileInputStream(configFile)) {
            props.load(input);
            log.info("Configuración cargada desde: {}", configFile);
        } catch (IOException e) {
            log.info("Archivo de configuración {} no encontrado, usando ENV/defaults", configFile);
        }

        return props;
    }
}
