package com.pasadita.pos.scale;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Servidor REST simple para báscula usando HttpServer de Java
 * Sin dependencias de Spring Boot
 */
public class ScaleRestServer {

    private static final Logger logger = LoggerFactory.getLogger(ScaleRestServer.class);
    private static final String ALLOWED_ORIGIN = "*";
    private static final int PORT = 8081;

    private HttpServer server;
    private TorreyScaleController scaleController;
    private final ObjectMapper objectMapper;

    private final String scalePort;
    private final boolean scaleEnabled;
    private final boolean autoConnect;
    private final String stationId;

    public ScaleRestServer(String scalePort, boolean scaleEnabled, boolean autoConnect, String stationId) {
        this.scalePort = scalePort;
        this.scaleEnabled = scaleEnabled;
        this.autoConnect = autoConnect;
        this.stationId = stationId;
        this.objectMapper = new ObjectMapper();
        // Serializar BigDecimal como número plano (ej: 2.110) sin notación científica
        this.objectMapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    /**
     * Inicia el servidor REST
     */
    public void start() throws IOException {
        logger.info("Iniciando servidor REST en puerto {}...", PORT);

        // Crear servidor HTTP
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);

        // Configurar endpoints
        server.createContext("/api/scale/weight", new WeightHandler());
        server.createContext("/api/scale/status", new StatusHandler());
        server.createContext("/api/scale/connect", new ConnectHandler());
        server.createContext("/api/scale/disconnect", new DisconnectHandler());
        server.createContext("/api/scale/ports", new PortsHandler());
        server.createContext("/api/station", new StationHandler());

        // Configurar executor
        server.setExecutor(Executors.newFixedThreadPool(4));

        // Inicializar báscula si está habilitada
        if (scaleEnabled) {
            logger.info("Báscula habilitada en puerto: {}", scalePort);
            scaleController = new TorreyScaleController(scalePort);

            if (autoConnect) {
                logger.info("Auto-conectando con báscula...");
                scaleController.connect();
            }
        } else {
            logger.warn("Báscula deshabilitada en configuración");
        }

        // Iniciar servidor
        server.start();
        logger.info("Servidor REST iniciado en http://localhost:{}", PORT);
    }

    /**
     * Detiene el servidor REST
     */
    public void stop() {
        if (server != null) {
            logger.info("Deteniendo servidor REST...");
            server.stop(0);
        }

        if (scaleController != null && scaleController.isConnected()) {
            logger.info("Desconectando báscula...");
            scaleController.disconnect();
        }
    }

    /**
     * Handler para GET /api/scale/weight
     */
    private class WeightHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Manejar CORS preflight
            if (handleCorsPreflightIfOptions(exchange)) return;

            // Solo permitir GET
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (!scaleEnabled) {
                response.put("success", false);
                response.put("error", "Báscula no habilitada");
                sendResponse(exchange, 503, response);
                return;
            }

            if (scaleController == null || !scaleController.isConnected()) {
                response.put("success", false);
                response.put("error", "Báscula no conectada");
                response.put("connected", false);
                sendResponse(exchange, 503, response);
                return;
            }

            try {
                TorreyScaleController.WeightReading reading = scaleController.readWeight();

                if (reading.success()) {
                    response.put("success", true);
                    response.put("weight", reading.weight());
                    response.put("unit", reading.unit());
                    response.put("stable", reading.stable());
                    response.put("connected", true);
                    sendResponse(exchange, 200, response);
                } else {
                    response.put("success", false);
                    response.put("error", reading.errorMessage());
                    response.put("connected", scaleController.isConnected());
                    sendResponse(exchange, 500, response);
                }

            } catch (Exception e) {
                logger.error("Error al leer peso: {}", e.getMessage(), e);
                response.put("success", false);
                response.put("error", "Error interno al leer peso");
                response.put("connected", false);
                sendResponse(exchange, 500, response);
            }
        }
    }

    /**
     * Handler para GET /api/scale/status
     */
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Manejar CORS preflight
            if (handleCorsPreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("enabled", scaleEnabled);
            response.put("port", scalePort);

            if (scaleController != null) {
                response.put("connected", scaleController.isConnected());
                response.put("portName", scaleController.getPortName());
            } else {
                response.put("connected", false);
                response.put("portName", null);
            }

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * Handler para POST /api/scale/connect
     */
    private class ConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Manejar CORS preflight
            if (handleCorsPreflightIfOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (!scaleEnabled) {
                response.put("success", false);
                response.put("error", "Báscula no habilitada en configuración");
                sendResponse(exchange, 503, response);
                return;
            }

            if (scaleController == null) {
                scaleController = new TorreyScaleController(scalePort);
            }

            if (scaleController.isConnected()) {
                response.put("success", true);
                response.put("message", "Ya conectado");
                response.put("connected", true);
                sendResponse(exchange, 200, response);
                return;
            }

            boolean connected = scaleController.connect();

            response.put("success", connected);
            response.put("connected", connected);
            response.put("message", connected ? "Conectado exitosamente" : "Error al conectar");

            if (!connected) {
                response.put("error", "No se pudo establecer conexión con la báscula");
                sendResponse(exchange, 500, response);
            } else {
                sendResponse(exchange, 200, response);
            }
        }
    }

    /**
     * Handler para POST /api/scale/disconnect
     */
    private class DisconnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Manejar CORS preflight
            if (handleCorsPreflightIfOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (scaleController != null) {
                scaleController.disconnect();
                response.put("success", true);
                response.put("message", "Desconectado exitosamente");
                response.put("connected", false);
            } else {
                response.put("success", false);
                response.put("message", "No hay controlador de báscula");
                response.put("connected", false);
            }

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * Handler para GET /api/scale/ports
     */
    private class PortsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Manejar CORS preflight
            if (handleCorsPreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            try {
                String[] ports = TorreyScaleController.getAvailablePorts();
                response.put("success", true);
                response.put("ports", ports);
                response.put("count", ports.length);
                response.put("currentPort", scalePort);

                sendResponse(exchange, 200, response);

            } catch (Exception e) {
                logger.error("Error al listar puertos: {}", e.getMessage(), e);
                response.put("success", false);
                response.put("error", "Error interno al listar puertos");
                sendResponse(exchange, 500, response);
            }
        }
    }

    /**
     * Handler para GET /api/station
     */
    private class StationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            if (handleCorsPreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("stationId", stationId);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * Envía una respuesta JSON
     */
    private void sendResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) {
        try {
            // Agregar headers CORS - solo permitir origen de producción
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().add("Content-Type", "application/json");

            // Convertir a JSON
            String jsonResponse = objectMapper.writeValueAsString(data);
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            // Enviar respuesta
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
            // Ignorar "Broken pipe" - el cliente cerró la conexión antes de recibir respuesta
            // Esto es normal con polling frecuente
            if (!e.getMessage().contains("Tubería rota") && !e.getMessage().contains("Broken pipe")) {
                logger.warn("Error enviando respuesta: {}", e.getMessage());
            }
        }
    }

    /**
     * Crea un mapa de error
     */
    private Map<String, Object> createError() {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", "Método no permitido");
        return error;
    }

    /**
     * Maneja solicitudes OPTIONS preflight para CORS
     *
     * @return true si fue una solicitud OPTIONS (ya manejada), false si debe continuar procesando
     */
    private boolean handleCorsPreflightIfOptions(HttpExchange exchange) {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            try {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.getResponseHeaders().add("Access-Control-Max-Age", "86400"); // Cache preflight 24h
                exchange.sendResponseHeaders(204, -1); // No content
            } catch (IOException e) {
                logger.warn("Error enviando respuesta preflight: {}", e.getMessage());
            }
            return true;
        }
        return false;
    }
}