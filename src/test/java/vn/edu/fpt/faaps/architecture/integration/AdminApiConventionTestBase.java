package vn.edu.fpt.faaps.architecture.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;
import vn.edu.fpt.faaps.auth.repository.AuthAccountStore;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-api-conv;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Import(AdminApiConventionTestBase.TestDbConfig.class)
abstract class AdminApiConventionTestBase {

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected FakeAccountStore accountStore;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected URI baseUrl;
    protected UUID semesterId;
    protected UUID curriculumVersionId;
    protected UUID categoryId;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        baseUrl = URI.create("http://localhost:" + port);
        AdminApiConventionSchema.SeedIds seeds = AdminApiConventionSchema.resetAndSeed(jdbcTemplate);
        semesterId = seeds.semesterId();
        curriculumVersionId = seeds.curriculumVersionId();
        categoryId = seeds.categoryId();
    }

    protected HttpClient loginAsAdmin() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@example.com", "Admin User",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(admin);
        HttpResponse<String> login = postJson(client, "/api/auth/admin/login",
                "{\"email\":\"admin@example.com\",\"password\":\"secret\"}");
        assertEquals(200, login.statusCode());
        return client;
    }

    protected HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder(baseUrl.resolve(path)).GET().build());
    }

    protected HttpResponse<String> postJson(HttpClient client, String path, String json) throws Exception {
        String csrf = getCsrfToken(client);
        return send(client, HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    protected HttpResponse<String> delete(HttpClient client, String path) throws Exception {
        String csrf = getCsrfToken(client);
        return send(client, HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE().build());
    }

    protected String getCsrfToken(HttpClient client) throws Exception {
        HttpResponse<String> resp = get(client, "/api/auth/csrf");
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(resp.body());
        assertTrue(m.find());
        return m.group(1);
    }

    protected HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    @TestConfiguration
    static class TestDbConfig {
        @Bean
        public FakeAccountStore fakeAccountStore() {
            return new FakeAccountStore();
        }
    }

    static class FakeAccountStore implements AuthAccountStore {
        final AtomicReference<AuthAccount> account = new AtomicReference<>();

        @Override
        public Optional<AuthAccount> findById(UUID id) {
            return Optional.ofNullable(account.get()).filter(c -> c.id().equals(id));
        }

        @Override
        public Optional<AuthAccount> findAdminByEmail(String email) {
            return Optional.ofNullable(account.get())
                    .filter(c -> "ADMIN".equals(c.role()) && c.email().equals(email));
        }

        @Override
        public Optional<AuthAccount> findLocalByEmail(String email) {
            return Optional.ofNullable(account.get())
                    .filter(c -> !"ADMIN".equals(c.role()) && c.email().equals(email));
        }

        @Override
        public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
            return account.get();
        }
    }
}
