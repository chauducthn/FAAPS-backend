package vn.edu.fpt.faaps.admin;

import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AdminNotificationPublisher {
    private final SimpMessagingTemplate messaging;

    public AdminNotificationPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void advisorRequestCreated(String studentCode, String cohortId) {
        Object event = Map.of(
                "type", "ADVISOR_REQUEST", "studentCode", studentCode,
                "cohortId", cohortId, "createdAt", java.time.Instant.now().toString());
        Runnable publish = () -> messaging.convertAndSend("/topic/admin/notifications", event);
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
