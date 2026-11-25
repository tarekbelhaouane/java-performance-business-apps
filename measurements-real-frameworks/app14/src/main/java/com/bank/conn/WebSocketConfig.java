package com.bank.conn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.concurrent.Semaphore;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final QuoteStreamHandler handler;
    private final int authMs;
    private final Semaphore entitlements;

    public WebSocketConfig(QuoteStreamHandler handler, @Value("${ws.auth-ms}") int authMs,
                           @Value("${ws.auth-permits}") int permits) {
        this.handler = handler;
        this.authMs = authMs;
        this.entitlements = new Semaphore(permits, true);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/quotes").setAllowedOrigins("*").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                                           Map<String, Object> attributes) throws Exception {
                if (authMs > 0) {
                    entitlements.acquire();
                    try { Thread.sleep(authMs); } finally { entitlements.release(); }
                }
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                                       Exception e) { }
        });
    }
}
