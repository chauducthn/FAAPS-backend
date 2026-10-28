package vn.edu.fpt.faaps.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GeminiGatewayTest {
    @Test
    void noKeyReturnsHonestFallback() {
        var gateway = new GeminiGateway("", new ObjectMapper());
        assertTrue(gateway.generate("gemini-2.5-flash", "policy", "question").fallback());
    }

    @Test
    void parsesProviderTextAndUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> {
            assertEquals("test-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] body = """
                    {"candidates":[{"content":{"parts":[{"text":"Academic answer"}]}}],
                     "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
            var gateway = new GeminiGateway("test-key", new ObjectMapper(), HttpClient.newHttpClient(), base);
            var reply = gateway.generate("gemini-2.5-flash", "Use academic policy", "What should I study?");
            assertFalse(reply.fallback());
            assertEquals("Academic answer", reply.text());
            assertEquals(7, reply.promptTokens());
            assertEquals(3, reply.completionTokens());
        } finally {
            server.stop(0);
        }
    }
}
