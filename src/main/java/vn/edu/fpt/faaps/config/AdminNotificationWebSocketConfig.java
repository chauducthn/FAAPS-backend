package vn.edu.fpt.faaps.config;

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
import org.springframework.security.core.GrantedAuthority;
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
                    requireAdmin(accessor.getUser());
                } else if (command == StompCommand.SUBSCRIBE) {
                    requireAdmin(accessor.getUser());
                    if (!"/user/queue/admin/notifications".equals(accessor.getDestination()))
                        throw new IllegalArgumentException("Subscription is not allowed");
                } else if (command == StompCommand.SEND) {
                    throw new IllegalArgumentException("Client messages are not supported");
                }
                return message;
            }
        });
    }

    private void requireAdmin(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new IllegalArgumentException("Admin authentication required");
        }
    }
}

