package vn.edu.fpt.faaps.notification.service;

import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AdminNotificationPublisher {
    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;

    public AdminNotificationPublisher(SimpMessagingTemplate messaging, JdbcTemplate jdbc) {
        this.messaging = messaging;
        this.jdbc = jdbc;
    }

    public void advisorRequestCreated(String studentCode, String cohortId) {
        Object event = Map.of(
                "type", "ADVISOR_REQUEST", "studentCode", studentCode,
                "cohortId", cohortId, "createdAt", java.time.Instant.now().toString());
        Runnable publish = () -> {
            String adminId = jdbc.queryForObject(
                    "SELECT user_id FROM admin_account WHERE singleton_id = 1", String.class);
            messaging.convertAndSendToUser(adminId, "/queue/admin/notifications", event);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }
}

