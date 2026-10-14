package com.safedocs.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPiiDetectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsResponsesApiSchemaAtTheDocumentedPath() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"entities\\\":[{\\\"type\\\":\\\"PERSON\\\",\\\"text\\\":\\\"Alice\\\"}]}\"}]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        assertEquals("Alice", detector().detect("Alice signed the form.").get(0).getMatchedText());
        JsonNode request = objectMapper.readTree(requestBody.get());
        JsonNode format = request.path("text").path("format");
        assertEquals("json_schema", format.path("type").asText());
        assertEquals("pii_entities", format.path("name").asText());
        assertTrue(format.path("strict").asBoolean());
        assertEquals("object", format.path("schema").path("type").asText());
        assertFalse(format.has("json_schema"));
    }

    @Test
    void doesNotReturnProviderErrorBodiesThatMayContainDocumentText() throws Exception {
        startServer(exchange -> {
            byte[] response = "{\"error\":{\"message\":\"Rejected: private-secret-from-document\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        AiDetectionException error = assertThrows(AiDetectionException.class,
                () -> detector().detect("private-secret-from-document"));
        assertFalse(error.getMessage().contains("private-secret-from-document"));
        assertEquals("AI provider returned HTTP 400. The model rejected the request format or requested model.",
                error.getMessage());
    }

    private AiPiiDetector detector() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AiPiiDetector(objectMapper, true, "custom", "test-key", "", "test-model", "",
                100_000, 7_000, 400, endpoint, endpoint, endpoint);
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", handler);
        server.start();
    }
}
