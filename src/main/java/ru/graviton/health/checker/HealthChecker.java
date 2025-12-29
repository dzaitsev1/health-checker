package ru.graviton.health.checker;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.*;

public class HealthChecker {

    // ================= CONFIG =================
    private static final int CHECK_PERIOD_SECONDS = 10;
    private static final int TIMEOUT_SECONDS = 3;
    private static final int HTTP_PORT = 9000;

    // Настройки ротации логов
    private static final String LOG_PATTERN = "checker.log";
    private static final int LOG_LIMIT = 10 * 1024 * 1024; // 10MB
    private static final int LOG_COUNT = 5;

    private static List<String> SERVICES = List.of();
    // ==========================================

    private static final Logger LOGGER = Logger.getLogger(HealthChecker.class.getName());

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    private static final Map<String, ServiceStatus> STATUSES =
            new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        setupLogger();

        if (args.length > 0) {
            SERVICES = List.of(args);
        } else {
            LOGGER.warning("No services provided in arguments. Using empty list.");
        }

        LOGGER.info("Starting health checker");

        startScheduler();
        startHttpServer();

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                LOGGER.info("Shutdown")));
    }

    private static void setupLogger() throws Exception {
        LogManager.getLogManager().reset();

        // Настройка записи в файл с ротацией
        FileHandler fh = new FileHandler(LOG_PATTERN, LOG_LIMIT, LOG_COUNT, true);
        Formatter formatter = new SimpleFormatter() {
            @Override
            public synchronized String format(LogRecord lr) {
                return String.format("%s | %-7s | %s%n",
                        LocalDateTime.now(),
                        lr.getLevel(),
                        lr.getMessage());
            }
        };
        fh.setFormatter(formatter);
        LOGGER.addHandler(fh);

        // Настройка вывода в консоль
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(formatter);
        LOGGER.addHandler(ch);
    }

    // ================= HEALTH CHECK =================

    private static void startScheduler() {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(
                HealthChecker::checkAll,
                0,
                CHECK_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private static void checkAll() {
        for (String url : SERVICES) {
            checkService(url);
        }
    }

    private static void checkService(String url) {
        ServiceStatus status = new ServiceStatus();
        status.url = url;
        status.lastChecked = LocalDateTime.now();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            status.code = response.statusCode();
            status.up = response.statusCode() == 200;

        } catch (Exception e) {
            status.up = false;
            status.error = e.getClass().getSimpleName();
        }

        STATUSES.put(url, status);
    }

    // ================= HTTP SERVER =================

    private static void startHttpServer() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        server.createContext("/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String json = buildHealthJson();
            byte[] bytes = json.getBytes();

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        LOGGER.info("HTTP server started on port " + HTTP_PORT);
    }

    // ================= JSON =================

    private static String buildHealthJson() {
        boolean allUp = !STATUSES.isEmpty() && STATUSES.values().stream().allMatch(s -> s.up);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"").append(allUp ? "UP" : "DOWN").append("\",");
        sb.append("\"services\":[");

        boolean first = true;
        for (ServiceStatus s : STATUSES.values()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{")
                    .append("\"url\":\"").append(s.url).append("\",")
                    .append("\"status\":\"").append(s.up ? "UP" : "DOWN").append("\",")
                    .append("\"code\":").append(s.code).append(",")
                    .append("\"lastChecked\":\"").append(s.lastChecked).append("\"");

            if (s.error != null) {
                sb.append(",\"error\":\"").append(s.error).append("\"");
            }

            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    // ================= MODEL =================

    private static class ServiceStatus {
        String url;
        boolean up;
        int code;
        String error;
        LocalDateTime lastChecked;
    }
}