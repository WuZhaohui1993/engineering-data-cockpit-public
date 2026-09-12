package com.ruoyi.web.controller.dashboard;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.service.DashboardService;
import com.ruoyi.common.exception.ServiceException;

/**
 * WebSocket 握手权限校验。
 *
 * 原生 WebSocket 不能自定义 Authorization 请求头，平台 JWT 过滤器允许握手
 * query 中携带短期 token；这里在升级连接前再次校验登录态和页面操作权限，避免
 * 仅依赖 REST 控制器上的 @PreAuthorize。
 */
@Component
public class DashboardWebSocketHandshakeInterceptor implements HandshakeInterceptor
{
    @Autowired
    private DashboardService dashboardService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes)
    {
        String path = request.getURI().getPath();
        String shareToken = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        if (path != null && path.endsWith("/runtime/share/ws") && shareToken != null && !shareToken.isBlank())
        {
            try
            {
                String pageCode = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("pageCode");
                Map<String, Object> runtime = pageCode == null || pageCode.isBlank()
                        ? dashboardService.getShareRuntime(shareToken)
                        : dashboardService.getShareRuntime(shareToken, pageCode);
                attributes.put("dashboardShare", true);
                attributes.put("dashboardShareToken", shareToken);
                attributes.put("dashboardSharePageId", runtime.get("pageId"));
                attributes.put("dashboardSharePageCode", runtime.get("pageCode"));
                return true;
            }
            catch (ServiceException ex)
            {
                return false;
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
        {
            return false;
        }
        String preview = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("preview");
        boolean isPreview = "1".equals(preview) || "true".equalsIgnoreCase(preview);
        Set<String> requiredPermissions = isPreview
                ? Set.of("dashboard:page:preview")
                : Set.of("dashboard:page:view", "dashboard:page:list");
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(item -> item.getAuthority()).collect(Collectors.toSet());
        if (!authorities.contains("*:*:*") && requiredPermissions.stream().noneMatch(authorities::contains))
        {
            return false;
        }
        attributes.put("dashboardUsername", authentication.getName());
        attributes.put("dashboardPreview", isPreview);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception)
    {
        // 无需保留握手上下文；连接生命周期中的页面/组件校验由 handler 完成。
    }
}
