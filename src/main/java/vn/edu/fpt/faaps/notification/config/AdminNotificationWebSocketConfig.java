package vn.edu.fpt.faaps.notification.config;

import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class AdminNotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String frontendUrl;

    public AdminNotificationWebSocketConfig(@Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(frontendUrl);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) return message;
                StompCommand command = accessor.getCommand();
                if (command == StompCommand.CONNECT) {
                    requireAuthenticated(accessor.getUser());
                } else if (command == StompCommand.SUBSCRIBE) {
                    String dest = accessor.getDestination();
                    if ("/user/queue/admin/notifications".equals(dest)) {
                        requireRole(accessor.getUser(), "ROLE_ADMIN");
                    } else if ("/user/queue/advisor/notifications".equals(dest)) {
                        requireRole(accessor.getUser(), "ROLE_ADVISOR");
                    } else if ("/user/queue/student/notifications".equals(dest)) {
                        requireRole(accessor.getUser(), "ROLE_STUDENT");
                    } else if ("/user/queue/notifications".equals(dest)) {
                        requireAuthenticated(accessor.getUser());
                    } else {
                        throw new IllegalArgumentException("Subscription is not allowed");
                    }
                } else if (command == StompCommand.SEND) {
                    throw new IllegalArgumentException("Client messages are not supported");
                }
                return message;
            }
        });
    }

    private void requireAuthenticated(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().isEmpty()) {
            throw new IllegalArgumentException("Authentication required");
        }
    }

    private void requireRole(Principal principal, String requiredRole) {
        if (!(principal instanceof Authentication authentication)
                || authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                        .noneMatch(requiredRole::equals)) {
            throw new IllegalArgumentException(requiredRole + " authentication required");
        }
    }
}

