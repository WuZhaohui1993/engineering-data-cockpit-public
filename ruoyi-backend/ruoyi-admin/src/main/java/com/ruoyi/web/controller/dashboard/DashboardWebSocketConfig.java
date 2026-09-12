package com.ruoyi.web.controller.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import java.util.Arrays;

/** 注册大屏统一 WebSocket 入口。 */
@Configuration
@EnableWebSocket
public class DashboardWebSocketConfig implements WebSocketConfigurer
{
    @Autowired
    private DashboardWebSocketHandler dashboardWebSocketHandler;

    @Autowired
    private DashboardWebSocketHandshakeInterceptor dashboardWebSocketHandshakeInterceptor;

    @Autowired
    private Environment environment;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        String allowedOrigin = environment.getProperty("dashboard.websocket.allowed-origin", "http://127.0.0.1:5173");
        String[] allowedOrigins = Arrays.stream(allowedOrigin.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toArray(String[]::new);
        if (allowedOrigins.length == 0 || Arrays.stream(allowedOrigins).anyMatch("*"::equals))
        {
            throw new IllegalStateException("dashboard.websocket.allowed-origin 必须配置明确的 Origin 白名单");
        }
        registry.addHandler(dashboardWebSocketHandler, "/dashboard/runtime/ws", "/api/dashboard/runtime/ws")
                .addInterceptors(dashboardWebSocketHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
        registry.addHandler(dashboardWebSocketHandler, "/dashboard/runtime/share/ws", "/api/dashboard/runtime/share/ws")
                .addInterceptors(dashboardWebSocketHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
