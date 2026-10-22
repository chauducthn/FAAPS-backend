package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration" })
class AuthApiTest {
    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void anonymousRequestsNeedAuthenticationAndAdminLoginNeedsAccountStore() throws Exception {
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        URI baseUrl = URI.create("http://localhost:" + port);
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> health = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/health")).GET().build());
        assertEquals(200, health.statusCode());

        HttpResponse<String> apiDocs = send(client, HttpRequest.newBuilder(baseUrl.resolve("/v3/api-docs")).GET().build());
        assertEquals(200, apiDocs.statusCode());
        assertTrue(apiDocs.body().contains("/api/auth/admin/login"));
        assertTrue(apiDocs.body().contains("/api/admin/majors"));

        HttpResponse<String> swaggerUi = send(client, HttpRequest.newBuilder(baseUrl.resolve("/swagger-ui/index.html")).GET().build());
        assertEquals(200, swaggerUi.statusCode());

        HttpResponse<String> currentUser = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/me")).GET().build());
        assertEquals(401, currentUser.statusCode());
        assertTrue(currentUser.body().contains("\"success\":false"));
        assertTrue(currentUser.body().contains("\"code\":\"AUTHENTICATION_REQUIRED\""));

        HttpResponse<String> csrf = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/csrf")).GET().build());
        assertEquals(200, csrf.statusCode());
        Matcher tokenMatcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf.body());
        assertTrue(tokenMatcher.find());
        String csrfToken = tokenMatcher.group(1);
        assertTrue(!csrfToken.isBlank());

        HttpRequest login = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/admin/login"))
                .header("X-XSRF-TOKEN", csrfToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .build();
        HttpResponse<String> unavailable = send(client, login);
        assertEquals(503, unavailable.statusCode());
        assertTrue(unavailable.body().contains("\"success\":false"));
        assertTrue(unavailable.body().contains("\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\""));

        HttpRequest logout = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/logout"))
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204, send(client, logout).statusCode());
    }

    @Test
    void corsPreflightAllowsOnlyConfiguredOriginsAndSupportsPatch() throws Exception {
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        URI url = URI.create("http://localhost:" + port + "/api/admin/notifications/read-all");
        HttpClient client = HttpClient.newHttpClient();

        for (String origin : new String[] {"http://localhost:5173", "http://56.10.8.193"}) {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "PATCH")
                    .header("Access-Control-Request-Headers", "content-type,x-xsrf-token")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = send(client, request);
            assertEquals(200, response.statusCode());
            assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
            assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("PATCH"));
        }

        HttpRequest denied = HttpRequest.newBuilder(url)
                .header("Origin", "http://untrusted.example")
                .header("Access-Control-Request-Method", "PATCH")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = send(client, denied);
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
