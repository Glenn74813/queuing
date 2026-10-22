package ph.gov.philhealth.qms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP broker for live queue updates. Only server-to-client broadcast topics exist and the
 * payloads contain no personal data (ticket number, counter, service), so displays may subscribe
 * without a user session. Clients cannot publish (SEND frames are rejected).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final QmsProperties props;

    public WebSocketConfig(QmsProperties props) {
        this.props = props;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10000, 10000})
                .setTaskScheduler(heartbeatScheduler());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws");
        var origins = props.security().corsOrigins();
        if (!origins.isEmpty()) {
            endpoint.setAllowedOrigins(origins.toArray(String[]::new));
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (StompCommand.SEND.equals(accessor.getCommand())) {
                    throw new MessagingException("Clients may not publish messages");
                }
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String dest = accessor.getDestination();
                    if (dest == null || !dest.matches("^/topic/sites/[0-9a-fA-F-]{36}/queue$")) {
                        throw new MessagingException("Subscription not allowed");
                    }
                }
                return message;
            }
        });
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
