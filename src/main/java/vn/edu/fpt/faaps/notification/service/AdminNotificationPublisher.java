package vn.edu.fpt.faaps.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.fpt.faaps.notification.config.AdminNotificationRedisConfig;

@Component
public class AdminNotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(AdminNotificationPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisPubSubEnabled;

    @Autowired
    public AdminNotificationPublisher(
            SimpMessagingTemplate messaging,
            JdbcTemplate jdbc,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${faaps.websocket.redis-pubsub-enabled:false}") boolean redisPubSubEnabled) {
        this(messaging, jdbc, redisTemplateProvider.getIfAvailable(), redisPubSubEnabled);
    }

    public AdminNotificationPublisher(SimpMessagingTemplate messaging, JdbcTemplate jdbc) {
        this(messaging, jdbc, (StringRedisTemplate) null, false);
    }

    AdminNotificationPublisher(SimpMessagingTemplate messaging, JdbcTemplate jdbc,
            StringRedisTemplate redisTemplate, boolean redisPubSubEnabled) {
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.redisTemplate = redisTemplate;
        this.redisPubSubEnabled = redisPubSubEnabled;
    }

    public void advisorRequestCreated(String studentCode, String cohortId) {
        Object event = Map.of(
                "type", "ADVISOR_REQUEST", "studentCode", studentCode,
                "cohortId", cohortId, "createdAt", java.time.Instant.now().toString());
        publishAfterCommit(() -> {
            String adminId = jdbc.queryForObject(
                    "SELECT user_id FROM admin_account WHERE singleton_id = 1", String.class);
            dispatch(adminId, "/queue/admin/notifications", event);
        });
    }

    public void notifyAdvisor(String advisorUserId, Object payload) {
        publishAfterCommit(() -> {
            dispatch(advisorUserId, "/queue/advisor/notifications", payload);
            dispatch(advisorUserId, "/queue/notifications", payload);
        });
    }

    public void notifyStudent(String studentUserId, Object payload) {
        publishAfterCommit(() -> {
            dispatch(studentUserId, "/queue/student/notifications", payload);
            dispatch(studentUserId, "/queue/notifications", payload);
        });
    }

    private void dispatch(String userId, String destination, Object event) {
        if (redisPubSubEnabled && redisTemplate != null) {
            try {
                String payload = JSON.writeValueAsString(Map.of(
                        "adminId", userId,
                        "userId", userId,
                        "destination", destination,
                        "event", event));
                redisTemplate.convertAndSend(AdminNotificationRedisConfig.CHANNEL, payload);
                return;
            } catch (Exception ex) {
                log.warn("Redis Pub/Sub publish failed, falling back to local WebSocket delivery: {}", ex.getMessage());
            }
        }
        messaging.convertAndSendToUser(userId, destination, event);
    }

    private void publishAfterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
