package com.ruoyi.web.controller.dashboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.system.service.DashboardIntegrationService;

/**
 * 面向外部系统的版本化推送入口。
 *
 * SecurityConfig 只为这些路径放行 Spring Security，真正的接入方、密钥、
 * 时间窗、签名、IP 和幂等校验全部由 DashboardIntegrationService 完成。
 */
@RestController
@RequestMapping({"/open-api/v1/integrations", "/api/open-api/v1/integrations"})
public class DashboardOpenIntegrationController
{
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    @Autowired
    private DashboardIntegrationService integrationService;
    @Autowired
    private com.ruoyi.system.service.DashboardIntegrationOperations operations;

    @PreAuthorize("permitAll()")
    @PostMapping("/{integrationCode}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable String integrationCode,
            HttpServletRequest request)
    {
        return receive(integrationCode, "events", request);
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/{integrationCode}/{endpointCode:[A-Za-z0-9._-]{3,64}}")
    public ResponseEntity<Map<String, Object>> endpoint(@PathVariable String integrationCode,
            @PathVariable String endpointCode, HttpServletRequest request)
    {
        return receive(integrationCode, endpointCode, request);
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/{integrationCode}/messages/{messageId}")
    public ResponseEntity<Map<String, Object>> messageStatus(@PathVariable String integrationCode,
            @PathVariable String messageId, @RequestParam(defaultValue = "events") String endpointCode,
            HttpServletRequest request)
    {
        return query(integrationCode, endpointCode, request, messageId, "");
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/{integrationCode}/requests/{platformRequestId}")
    public ResponseEntity<Map<String, Object>> requestStatus(@PathVariable String integrationCode,
            @PathVariable String platformRequestId, @RequestParam(defaultValue = "events") String endpointCode,
            HttpServletRequest request)
    {
        return query(integrationCode, endpointCode, request, "", platformRequestId);
    }

    private ResponseEntity<Map<String, Object>> receive(String integrationCode, String endpointCode,
            HttpServletRequest request)
    {
        try
        {
            byte[] body = readBody(request);
            Map<String, String> headers = readHeaders(request);
            DashboardIntegrationService.InboundResponse response = integrationService.receiveInbound(
                    integrationCode, endpointCode, request.getMethod(), requestTarget(request),
                    request.getContentType(), request.getRemoteAddr(), headers, body);
            operations.audit("INBOUND",integrationCode,String.valueOf(response.body().getOrDefault("requestId","")),
                    response.httpStatus()<300?"ACCEPTED":String.valueOf(response.body().getOrDefault("code","REJECTED")),"external",body.length,0);
            return response(response);
        }
        catch (BodyTooLargeException ex)
        {
            return error(HttpStatusCode.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", "请求体超过平台上限", "");
        }
        catch (DuplicateHeaderException ex)
        {
            return error(HttpStatusCode.BAD_REQUEST, "DUPLICATE_HEADER", "请求头存在重复值", "");
        }
        catch (IOException ex)
        {
            return error(HttpStatusCode.SERVICE_UNAVAILABLE, "RECEIVE_UNAVAILABLE", "平台暂时无法读取请求", "");
        }
    }

    private ResponseEntity<Map<String, Object>> query(String integrationCode, String endpointCode,
            HttpServletRequest request, String messageId, String platformRequestId)
    {
        try
        {
            Map<String, String> headers = readHeaders(request);
            DashboardIntegrationService.InboundResponse response = integrationService.queryInboundStatus(
                    integrationCode, endpointCode, request.getMethod(), requestTarget(request),
                    request.getRemoteAddr(), headers, messageId, platformRequestId);
            return response(response);
        }
        catch (DuplicateHeaderException ex)
        {
            return error(HttpStatusCode.BAD_REQUEST, "DUPLICATE_HEADER", "请求头存在重复值", "");
        }
    }

    private ResponseEntity<Map<String, Object>> response(DashboardIntegrationService.InboundResponse response)
    {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Cache-Control", "no-store");
        if (response.retryAfterSeconds() > 0)
            headers.set("Retry-After", String.valueOf(response.retryAfterSeconds()));
        return ResponseEntity.status(response.httpStatus()).headers(headers).body(response.body());
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message, String integrationCode)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", "");
        if (!integrationCode.isBlank()) body.put("integrationCode", integrationCode);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException
    {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) throw new BodyTooLargeException();
        try (InputStream input = request.getInputStream())
        {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    contentLength > 0 && contentLength < 65536 ? (int) contentLength : 8192);
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0)
            {
                if (read == 0) continue;
                if (total > MAX_BODY_BYTES - read) throw new BodyTooLargeException();
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    private Map<String, String> readHeaders(HttpServletRequest request)
    {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return headers;
        while (names.hasMoreElements())
        {
            String name = names.nextElement();
            Enumeration<String> values = request.getHeaders(name);
            if (values == null || !values.hasMoreElements()) continue;
            String value = values.nextElement();
            if (values.hasMoreElements()) throw new DuplicateHeaderException();
            headers.put(name.toLowerCase(java.util.Locale.ROOT), value);
        }
        return headers;
    }

    private String requestTarget(HttpServletRequest request)
    {
        String query = request.getQueryString();
        return request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
    }

    private static final class BodyTooLargeException extends RuntimeException { }
    private static final class DuplicateHeaderException extends RuntimeException { }

    private static final class HttpStatusCode
    {
        private static final int BAD_REQUEST = 400;
        private static final int PAYLOAD_TOO_LARGE = 413;
        private static final int SERVICE_UNAVAILABLE = 503;
    }
}
