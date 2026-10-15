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

        HttpResponse<String> currentUser = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/me")).GET().build());
        assertEquals(401, currentUser.statusCode());

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
        assertEquals(503, send(client, login).statusCode());

        HttpRequest logout = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/logout"))
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204, send(client, logout).statusCode());
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
