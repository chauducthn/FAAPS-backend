package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;
import vn.edu.fpt.faaps.auth.repository.AuthAccountStore;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;

final class AuthFlowTestSupport {

    private AuthFlowTestSupport() {
    }

    static void addRefreshCookie(CookieManager cookieManager, URI baseUrl, String value) {
        HttpCookie cookie = new HttpCookie(AuthRefreshTokenService.COOKIE_NAME, value);
        cookie.setPath("/api/auth");
        cookie.setDomain("localhost");
        cookie.setVersion(0);
        cookieManager.getCookieStore().add(baseUrl.resolve("/api/auth/refresh"), cookie);
    }

    static String getCsrfToken(HttpClient client, URI baseUrl) throws Exception {
        HttpResponse<String> response = get(client, baseUrl.resolve("/api/auth/csrf"));
        assertEquals(200, response.statusCode());
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    static HttpResponse<String> get(HttpClient client, URI url) throws Exception {
        return send(client, HttpRequest.newBuilder(url).GET().build());
    }

    static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class AccountStoreConfig {
        @Bean
        FakeAccountStore fakeAccountStore() {
            return new FakeAccountStore();
        }
    }

    static class FakeAccountStore implements AuthAccountStore {
        final AtomicReference<AuthAccount> account = new AtomicReference<>();

        @Override
        public Optional<AuthAccount> findById(UUID id) {
            return Optional.ofNullable(account.get()).filter(candidate -> candidate.id().equals(id));
        }

        @Override
        public Optional<AuthAccount> findAdminByEmail(String email) {
            return Optional.ofNullable(account.get()).filter(candidate -> candidate.email().equals(email));
        }

        @Override
        public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
            return account.get();
        }
    }
}
