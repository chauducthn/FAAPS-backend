package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import vn.edu.fpt.faaps.admin.AdminNotificationPublisher;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration" })
@Import(AdminNotificationWebSocketTest.AccountStoreConfig.class)
class AdminNotificationWebSocketTest {
    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private FakeAccountStore accountStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminNotificationPublisher publisher;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Test
    void onlyAdminSessionCanSubscribeAndReceiveRequestEvent() throws Exception {
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        URI httpUrl = URI.create("http://localhost:" + port);
        URI socketUrl = URI.create("ws://localhost:" + port + "/ws");
        HttpClient anonymous = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertThrows(CompletionException.class,
                () -> anonymous.newWebSocketBuilder().buildAsync(socketUrl, new Frames()).join());

        accountStore.account.set(new AuthAccount(UUID.randomUUID(), "admin@example.com", "Admin",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret")));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenReturn(accountStore.account.get().id().toString());
        HttpClient admin = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> csrf = admin.send(HttpRequest.newBuilder(httpUrl.resolve("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf.body());
        assertTrue(token.find());
        HttpResponse<String> login = admin.send(HttpRequest.newBuilder(httpUrl.resolve("/api/auth/admin/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token.group(1))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());

        Frames frames = new Frames();
        WebSocket socket = admin.newWebSocketBuilder().buildAsync(socketUrl, frames).join();
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0", true).join();
            assertTrue(frames.next().startsWith("CONNECTED"));
            socket.sendText("SUBSCRIBE\nid:admin-alerts\ndestination:/user/queue/admin/notifications\n\n\0", true).join();
            for (int attempt = 0; attempt < 100 && userRegistry.findSubscriptions(
                    subscription -> "/user/queue/admin/notifications".equals(subscription.getDestination())).isEmpty(); attempt++) {
                Thread.sleep(50);
            }
            assertTrue(!userRegistry.findSubscriptions(
                    subscription -> "/user/queue/admin/notifications".equals(subscription.getDestination())).isEmpty());
            publisher.advisorRequestCreated("SE0001", UUID.randomUUID().toString());
            String event = frames.next();
            assertTrue(event.startsWith("MESSAGE"));
            assertTrue(event.contains("\"type\":\"ADVISOR_REQUEST\""));
            assertTrue(event.contains("\"studentCode\":\"SE0001\""));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
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

    static class Frames implements WebSocket.Listener {
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final StringBuilder pending = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            pending.append(text);
            int end;
            while ((end = pending.indexOf("\0")) >= 0) {
                received.add(pending.substring(0, end));
                pending.delete(0, end + 1);
            }
            socket.request(1);
            return null;
        }

        String next() throws InterruptedException {
            String frame = received.poll(10, TimeUnit.SECONDS);
            if (frame == null) throw new AssertionError("No STOMP frame received");
            return frame;
        }
    }
}
