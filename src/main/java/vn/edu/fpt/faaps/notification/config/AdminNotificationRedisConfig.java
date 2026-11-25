package vn.edu.fpt.faaps.notification.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Configuration
@ConditionalOnProperty(name = "faaps.websocket.redis-pubsub-enabled", havingValue = "true")
public class AdminNotificationRedisConfig {
    public static final String CHANNEL = "faaps:admin-notifications";
    private static final Logger log = LoggerFactory.getLogger(AdminNotificationRedisConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public RedisMessageListenerContainer adminNotificationRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            SimpMessagingTemplate messaging) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((Message message, byte[] pattern) -> {
            try {
                String payload = new String(message.getBody(), StandardCharsets.UTF_8);
                Map<String, Object> envelope = JSON.readValue(payload, new TypeReference<>() {
                });
                Object userId = envelope.getOrDefault("userId", envelope.get("adminId"));
                Object destination = envelope.getOrDefault("destination", "/queue/admin/notifications");
                Object event = envelope.get("event");
                if (userId instanceof String id && !id.isBlank()
                        && destination instanceof String dest && !dest.isBlank()
                        && event != null) {
                    messaging.convertAndSendToUser(id, dest, event);
                }
            } catch (Exception ex) {
                log.warn("Failed to relay Redis admin notification message: {}", ex.getMessage());
            }
        }, new ChannelTopic(CHANNEL));
        return container;
    }
}
