package com.ruoyi.web.controller.dashboard;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 受控 WebSocket 数据集通道。
 *
 * 数据集的 sourceType 可以是 SQL、API 或 JSON，服务端按刷新周期拉取并向浏览器
 * 推送统一结果。受控 API/只读来源用于验证 WebSocket 场景，替换
 * 外部实时来源代理时不改变页面组件协议。
 */
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler
{
    private static final int MAX_OPEN_CONNECTIONS = 100;
    private static final long MIN_CLIENT_MESSAGE_INTERVAL_MS = 250L;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    private DashboardService dashboardService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception
    {
        String datasetCode = queryParam(session, "datasetCode");
        String pageId = queryParam(session, "pageId");
        String pageCode = queryParam(session, "pageCode");
        String widgetId = queryParam(session, "widgetId");
        String revisionId = queryParam(session, "revisionId");
        boolean preview = Boolean.TRUE.equals(session.getAttributes().get("dashboardPreview"));
        boolean shared = Boolean.TRUE.equals(session.getAttributes().get("dashboardShare"));
        String shareToken = String.valueOf(session.getAttributes().getOrDefault("dashboardShareToken", ""));
        if (datasetCode.isBlank() || widgetId.isBlank()
                || (!shared && pageId.isBlank())
                || (shared && pageId.isBlank() && pageCode.isBlank()))
        {
            close(session, CloseStatus.BAD_DATA, "pageId、widgetId 和 datasetCode 不能为空");
            return;
        }
        Map<String, Object> dataset;
        try
        {
            dataset = dashboardService.getDatasetByCode(datasetCode);
        }
        catch (ServiceException ex)
        {
            close(session, CloseStatus.NOT_ACCEPTABLE, "数据集不可用");
            return;
        }
        if (!"WEBSOCKET".equals(String.valueOf(dataset.get("dataType"))) || !"ACTIVE".equals(dataset.get("status")))
        {
            close(session, CloseStatus.NOT_ACCEPTABLE, "不是可用的 WebSocket 数据集");
            return;
        }
        if (activeSessions.size() >= MAX_OPEN_CONNECTIONS || !activeSessions.add(session.getId()))
        {
            close(session, new CloseStatus(1013, "实时连接数已达上限"), "实时连接数已达上限");
            return;
        }
        long pageNumber;
        try
        {
            Object sharedPageId = session.getAttributes().get("dashboardSharePageId");
            // 分享握手已经按令牌、pageCode 解析出唯一页面主键。优先使用握手结果，
            // 不信任前端随后拼接的 pageId（编码路由切换期间可能暂时为 0）。
            pageNumber = shared && sharedPageId != null
                    ? Long.parseLong(String.valueOf(sharedPageId))
                    : Long.parseLong(pageId);
        }
        catch (NumberFormatException ex)
        {
            activeSessions.remove(session.getId());
            close(session, CloseStatus.BAD_DATA, "pageId 不合法");
            return;
        }
        Long historyRevisionId = null;
        if (preview && !revisionId.isBlank())
        {
            try { historyRevisionId = Long.parseLong(revisionId); }
            catch (NumberFormatException ex)
            {
                activeSessions.remove(session.getId());
                close(session, CloseStatus.BAD_DATA, "revisionId 不合法");
                return;
            }
        }
        String sharedPageCode = shared
                ? String.valueOf(session.getAttributes().getOrDefault("dashboardSharePageCode", pageCode)) : "";
        Map<String, Object> firstResult = shared
                ? (sharedPageCode.isBlank()
                        ? dashboardService.executeSharePageDataset(shareToken, pageNumber, widgetId, datasetCode,
                                new HashMap<>(), java.util.Collections.emptyList())
                        : dashboardService.executeSharePageDataset(shareToken, sharedPageCode, pageNumber, widgetId,
                                datasetCode, new HashMap<>(), java.util.Collections.emptyList()))
                : dashboardService.executePageDataset(pageNumber, widgetId, datasetCode, new HashMap<>(),
                        java.util.Collections.emptyList(), preview, historyRevisionId);
        if ("FORBIDDEN".equals(firstResult.get("quality")))
        {
            activeSessions.remove(session.getId());
            close(session, CloseStatus.NOT_ACCEPTABLE, "数据集未绑定到已发布组件");
            return;
        }
        session.getAttributes().put("datasetCode", datasetCode);
        session.getAttributes().put("pageId", pageNumber);
        session.getAttributes().put("widgetId", widgetId);
        session.getAttributes().put("preview", preview);
        if (historyRevisionId != null) session.getAttributes().put("revisionId", historyRevisionId);
        session.getAttributes().put("dashboardShare", shared);
        if (shared && !sharedPageCode.isBlank()) session.getAttributes().put("dashboardSharePageCode", sharedPageCode);
        session.getAttributes().put("dashboardParams", new HashMap<String, Object>());
        session.getAttributes().put("dashboardFilters", new ArrayList<Map<String, Object>>());
        // 握手阶段已经完成一次页面/组件绑定校验和数据读取；把这次结果作为首帧
        // 发送，避免原生上游 WebSocket 在建立平台连接时被重复打开两次。
        session.getAttributes().put("dashboardInitialResult", firstResult);
        sendResult(session, pageNumber, widgetId, datasetCode);
        int refreshSeconds = number(dataset.get("refreshSeconds"), 10);
        long initialVersion=dashboardService.integrationDatasetVersion(datasetCode);
        session.getAttributes().put("integrationVersion",initialVersion);
        JsonNode datasetConfig=objectMapper.readTree(String.valueOf(dataset.get("configJson")));
        boolean nativeUpstream="WEBSOCKET".equals(datasetConfig.path("sourceType").asText());
        int interval=initialVersion>=0||nativeUpstream?1:refreshSeconds;
        session.getAttributes().put("lastDataSent",System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            long version=dashboardService.integrationDatasetVersion(datasetCode);
            long previous=((Number)session.getAttributes().getOrDefault("integrationVersion",-1L)).longValue();
            long last=((Number)session.getAttributes().getOrDefault("lastDataSent",0L)).longValue();
            if(nativeUpstream||version!=previous||System.currentTimeMillis()-last>=refreshSeconds*1000L) {
                sendResult(session,pageNumber,widgetId,datasetCode);
                session.getAttributes().put("integrationVersion",version);
                session.getAttributes().put("lastDataSent",System.currentTimeMillis());
            }
        },interval,interval,TimeUnit.SECONDS);
        tasks.put(session.getId(), future);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception
    {
        if (message.getPayloadLength() > 4096)
        {
            close(session, new CloseStatus(1009, "消息过大"), "消息过大");
            return;
        }
        Object previousMessageAt = session.getAttributes().get("dashboardLastMessageAt");
        long now = System.currentTimeMillis();
        if (previousMessageAt instanceof Long && now - (Long) previousMessageAt < MIN_CLIENT_MESSAGE_INTERVAL_MS)
        {
            close(session, new CloseStatus(1008, "订阅请求过于频繁"), "订阅请求过于频繁");
            return;
        }
        session.getAttributes().put("dashboardLastMessageAt", now);
        String datasetCode = String.valueOf(session.getAttributes().getOrDefault("datasetCode", ""));
        Object pageId = session.getAttributes().get("pageId");
        String widgetId = String.valueOf(session.getAttributes().getOrDefault("widgetId", ""));
        if (datasetCode.isBlank() || !(pageId instanceof Long)) return;
        JsonNode payload;
        try { payload = objectMapper.readTree(message.getPayload()); }
        catch (JsonProcessingException ex) { close(session, CloseStatus.BAD_DATA, "订阅消息格式不合法"); return; }
        if (payload == null || !payload.isObject()) { close(session, CloseStatus.BAD_DATA, "订阅消息必须是对象"); return; }
        String type = payload.path("type").asText("refresh");
        if (!"subscribe".equals(type) && !"refresh".equals(type)) { close(session, CloseStatus.BAD_DATA, "订阅消息类型不支持"); return; }
        Map<String, Object> params;
        List<Map<String, Object>> filters;
        try
        {
            params = readParams(payload.get("params"));
            filters = readFilters(payload.get("filters"));
        }
        catch (IllegalArgumentException ex)
        {
            close(session, CloseStatus.BAD_DATA, ex.getMessage() == null ? "订阅参数不合法" : ex.getMessage());
            return;
        }
        session.getAttributes().put("dashboardParams", params);
        session.getAttributes().put("dashboardFilters", filters);
        sendResult(session, (Long) pageId, widgetId, datasetCode);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
    {
        activeSessions.remove(session.getId());
        ScheduledFuture<?> future = tasks.remove(session.getId());
        if (future != null) future.cancel(true);
    }

    private void sendResult(WebSocketSession session, long pageId, String widgetId, String datasetCode)
    {
        if (!session.isOpen()) return;
        try
        {
            Object initial = session.getAttributes().remove("dashboardInitialResult");
            if (initial instanceof Map)
            {
                synchronized(session) { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initial))); }
                return;
            }
            boolean preview = Boolean.TRUE.equals(session.getAttributes().get("preview"));
            Long revisionId = session.getAttributes().get("revisionId") instanceof Long
                    ? (Long) session.getAttributes().get("revisionId") : null;
            boolean shared = Boolean.TRUE.equals(session.getAttributes().get("dashboardShare"));
            String shareToken = String.valueOf(session.getAttributes().getOrDefault("dashboardShareToken", ""));
            String sharePageCode = String.valueOf(session.getAttributes().getOrDefault("dashboardSharePageCode", ""));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) session.getAttributes().getOrDefault("dashboardParams", Collections.emptyMap());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filters = (List<Map<String, Object>>) session.getAttributes().getOrDefault("dashboardFilters", Collections.emptyList());
            Map<String, Object> result = shared
                    ? (sharePageCode.isBlank()
                            ? dashboardService.executeSharePageDataset(shareToken, pageId, widgetId, datasetCode,
                                    params, filters)
                            : dashboardService.executeSharePageDataset(shareToken, sharePageCode, pageId, widgetId,
                                    datasetCode, params, filters))
                    : dashboardService.executePageDataset(pageId, widgetId, datasetCode, params, filters, preview,
                            revisionId);
            synchronized(session) { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(result))); }
        }
        catch (IOException | RuntimeException ex)
        {
            try { close(session, CloseStatus.SERVER_ERROR, "数据集推送失败"); } catch (IOException ignored) { }
        }
    }

    private String queryParam(WebSocketSession session, String name)
    {
        if (session.getUri() == null || session.getUri().getRawQuery() == null) return "";
        for (String item : session.getUri().getRawQuery().split("&"))
        {
            String[] parts = item.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private int number(Object value, int fallback)
    {
        try { int result = Integer.parseInt(String.valueOf(value)); return result > 0 && result <= 3600 ? result : fallback; }
        catch (NumberFormatException ex) { return fallback; }
    }

    private Map<String, Object> readParams(JsonNode node)
    {
        Map<String, Object> params = new HashMap<>();
        if (node == null || node.isNull()) return params;
        if (!node.isObject() || node.size() > 50) throw new IllegalArgumentException("订阅参数过多");
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (name.length() > 128 || (!value.isValueNode() && !value.isNull()))
                throw new IllegalArgumentException("订阅参数不合法");
            params.put(name, value.isNull() ? null : value.isBoolean() ? value.asBoolean() : value.isNumber() ? value.numberValue() : value.asText());
        });
        return params;
    }

    private List<Map<String, Object>> readFilters(JsonNode node)
    {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (node == null || node.isNull()) return filters;
        if (!node.isArray() || node.size() > 50) throw new IllegalArgumentException("订阅过滤条件过多");
        for (JsonNode item : node)
        {
            if (!item.isObject() || item.size() > 4) throw new IllegalArgumentException("订阅过滤条件不合法");
            Map<String, Object> filter = new HashMap<>();
            item.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (!("field".equals(name) || "operator".equals(name) || "value".equals(name)))
                    throw new IllegalArgumentException("订阅过滤属性不支持");
                if (!value.isValueNode() && !value.isNull()) throw new IllegalArgumentException("订阅过滤值不合法");
                filter.put(name, value.isNull() ? null : value.isBoolean() ? value.asBoolean() : value.isNumber() ? value.numberValue() : value.asText());
            });
            filters.add(filter);
        }
        return filters;
    }

    private void close(WebSocketSession session, CloseStatus status, String reason) throws IOException
    {
        if (session.isOpen()) session.close(new CloseStatus(status.getCode(), reason));
    }
}
