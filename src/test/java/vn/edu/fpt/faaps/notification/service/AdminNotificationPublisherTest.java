package vn.edu.fpt.faaps.notification.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import vn.edu.fpt.faaps.notification.config.AdminNotificationRedisConfig;

class AdminNotificationPublisherTest {
    @Test
    void publishesToRedisChannelWhenPubSubEnabled() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("admin-1");

        AdminNotificationPublisher publisher = new AdminNotificationPublisher(messaging, jdbc, redis, true);
        publisher.advisorRequestCreated("SE180001", "cohort-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(AdminNotificationRedisConfig.CHANNEL), payload.capture());
        assertTrue(payload.getValue().contains("\"adminId\":\"admin-1\""));
        assertTrue(payload.getValue().contains("\"studentCode\":\"SE180001\""));
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void fallsBackToLocalWebSocketWhenRedisPublishFails() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("admin-1");
        doThrow(new RuntimeException("Redis down")).when(redis).convertAndSend(anyString(), anyString());

        AdminNotificationPublisher publisher = new AdminNotificationPublisher(messaging, jdbc, redis, true);
        publisher.advisorRequestCreated("SE180001", "cohort-1");

        verify(messaging).convertAndSendToUser(eq("admin-1"), eq("/queue/admin/notifications"), any());
    }
}
