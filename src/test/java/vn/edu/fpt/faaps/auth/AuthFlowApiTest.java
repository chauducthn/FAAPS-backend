package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.edu.fpt.faaps.auth.AuthFlowTestSupport.addRefreshCookie;
import static vn.edu.fpt.faaps.auth.AuthFlowTestSupport.get;
import static vn.edu.fpt.faaps.auth.AuthFlowTestSupport.getCsrfToken;
import static vn.edu.fpt.faaps.auth.AuthFlowTestSupport.send;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.edu.fpt.faaps.auth.AuthFlowTestSupport.FakeAccountStore;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.service.AuthPasswordService;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration" })
@Import(AuthFlowTestSupport.AccountStoreConfig.class)
class AuthFlowApiTest {
    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthRefreshTokenService refreshTokens;

    @MockitoBean
    private AuthPasswordService passwordService;

    @Autowired
    private Environment environment;

    @Autowired
    private FakeAccountStore accountStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminSessionSupportsMeRoleChecksLogoutAndSuspension() throws Exception {
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@example.com", "Admin",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(admin);
        URI baseUrl = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        String csrfToken = getCsrfToken(client, baseUrl);
        HttpResponse<String> login = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/admin/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .build());
        assertEquals(200, login.statusCode());
        assertTrue(login.body().contains("\"role\":\"ADMIN\""));
        assertEquals(200, get(client, baseUrl.resolve("/api/auth/me")).statusCode());
        assertEquals(403, get(client, baseUrl.resolve("/api/student/probe")).statusCode());

        csrfToken = getCsrfToken(client, baseUrl);
        HttpRequest logout = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/logout"))
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204, send(client, logout).statusCode());
        assertEquals(401, get(client, baseUrl.resolve("/api/auth/me")).statusCode());

        csrfToken = getCsrfToken(client, baseUrl);
        HttpRequest loginAgain = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/admin/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .build();
        assertEquals(200, send(client, loginAgain).statusCode());
        accountStore.account.set(new AuthAccount(admin.id(), admin.email(), admin.fullName(),
                "ADMIN", "SUSPENDED", admin.passwordHash()));
        assertEquals(401, get(client, baseUrl.resolve("/api/auth/me")).statusCode());
    }

    @Test
    void forwardedForCannotChangePasswordResetRateLimitAddress() throws Exception {
        URI baseUrl = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String csrfToken = getCsrfToken(client, baseUrl);
        when(passwordService.forgotPassword(any(), any()))
                .thenReturn(new AuthPasswordService.ForgotPasswordResult(true, "Accepted", null));

        for (String forwardedFor : new String[] {"198.51.100.1", "198.51.100.2"}) {
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/forgot-password"))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .header("X-Forwarded-For", forwardedFor)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"student@example.com\"}"))
                    .build();
            assertEquals(200, send(client, request).statusCode());
        }

        ArgumentCaptor<String> addresses = ArgumentCaptor.forClass(String.class);
        verify(passwordService, org.mockito.Mockito.times(2))
                .forgotPassword(eq("student@example.com"), addresses.capture());
        assertEquals(addresses.getAllValues().get(0), addresses.getAllValues().get(1));
        assertTrue(!addresses.getAllValues().get(0).startsWith("198.51.100."));
    }

    @Test
    void refreshEndpointRotatesRefreshCookieAndReturns401ForInvalidToken() throws Exception {
        URI baseUrl = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@example.com", "Admin",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(admin);
        when(refreshTokens.userIdFor("valid-refresh-token")).thenReturn(admin.id());
        when(refreshTokens.rotate(eq("valid-refresh-token"), eq(admin.id()), any()))
                .thenReturn("rotated-refresh-token");

        CookieManager cookieManager = new CookieManager();
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        String csrfToken = getCsrfToken(client, baseUrl);
        addRefreshCookie(cookieManager, baseUrl, "valid-refresh-token");
        HttpRequest refreshRequest = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/refresh"))
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> refreshed = send(client, refreshRequest);
        assertEquals(200, refreshed.statusCode(), refreshed.body());
        assertTrue(refreshed.body().contains("\"role\":\"ADMIN\""));
        assertEquals(200, get(client, baseUrl.resolve("/api/auth/me")).statusCode());

        CookieManager invalidCookieManager = new CookieManager();
        HttpClient invalidClient = HttpClient.newBuilder().cookieHandler(invalidCookieManager).build();
        String invalidCsrf = getCsrfToken(invalidClient, baseUrl);
        addRefreshCookie(invalidCookieManager, baseUrl, "expired-refresh-token");
        when(refreshTokens.userIdFor("expired-refresh-token"))
                .thenThrow(new AuthRefreshTokenService.InvalidRefreshTokenException());
        HttpRequest invalidRefresh = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/refresh"))
                .header("X-XSRF-TOKEN", invalidCsrf)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(401, send(invalidClient, invalidRefresh).statusCode());
    }
}
