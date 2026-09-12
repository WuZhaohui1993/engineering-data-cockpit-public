package com.ruoyi.system.service;

import com.github.pagehelper.PageHelper;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.DashboardIntegrationMapper;
import com.ruoyi.system.mapper.DashboardMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DashboardIntegrationService
{
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final Pattern SAFE_EXTERNAL = Pattern.compile("[A-Za-z0-9._:@/+\\-]{1,256}");
    private static final Pattern SAFE_HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/?{}\\-]*");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$" + "\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern JSON_PATH_PART = Pattern.compile("[A-Za-z0-9_-]{1,64}(?:\\[[0-9]{1,6}\\])?");
    private static final Set<String> ENDPOINT_METHODS = Set.of("GET", "POST");
    private static final Set<String> REQUEST_TYPES = Set.of("NONE", "JSON", "FORM_URLENCODED");
    private static final Set<String> RESPONSE_TYPES = Set.of("JSON", "BINARY_MEDIA");
    private static final Set<String> AUTH_PROVIDERS = Set.of("INHERIT", "NO_AUTH", "BEARER", "API_KEY",
            "HMAC_V1", "HMAC_APPKEY_TIMESTAMP_V1");
    private static final Set<String> INBOUND_PROVIDERS = Set.of("API_KEY", "BEARER", "HMAC_V1");
    private static final Set<String> PROFILES = Set.of("EVENT", "SNAPSHOT", "RECORD", "FILE_NOTIFICATION");
    private static final Set<String> INTEGRATION_STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "REVOKED");
    private static final Set<String> QUALITY_SUCCESS = Set.of("SUCCESS", "NO_DATA", "PARTIAL", "TRUNCATED");
    private static final Set<String> ENDPOINT_TEST_PASSED = Set.of("SUCCESS", "NO_DATA");
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROWS = 1000;
    private static final int MAX_BATCH_ITEMS = 500;
    private static final int MAX_INBOUND_BODY = 16 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_FIELDS = 4000;
    private static final int MAX_TEXT = 4096;
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");
    private static final DateTimeFormatter LOCAL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private DashboardIntegrationMapper integrationMapper;
    @Autowired
    private DashboardMapper dashboardMapper;
    @Autowired
    private DashboardDataFolderService dataFolders;
    @Autowired
    private Environment environment;
    @Autowired
    private DashboardIntegrationOperations operations;
    @Autowired
    private DashboardIntegrationRetentionService retentionService;
    @Autowired(required = false)
    @Qualifier("threadPoolTaskExecutor")
    private Executor taskExecutor;
    @Autowired(required = false)
    @Qualifier("scheduledExecutorService")
    private ScheduledExecutorService scheduledExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, WindowCounter> inboundRateCounters = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> inboundPermits = new ConcurrentHashMap<>();
    private final Map<String, CachedMedia> mediaCache = new ConcurrentHashMap<>();
    private final Set<Long> processingBatches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean maintenanceStarted = new AtomicBoolean();

    /* ----------------------------- endpoint 管理 ----------------------------- */

    public List<Map<String, Object>> listEndpoints(String sourceCode)
    {
        String code = requireCode(sourceCode, "数据源编码");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : integrationMapper.selectEndpointList(code))
            result.add(safeEndpoint(item));
        return result;
    }

    public List<Map<String, Object>> listEndpointsPage(String sourceCode, String keyword, String status,
            Integer pageNum, Integer pageSize)
    {
        // 来源存在性和类型校验须在分页开始前完成。
        Map<String, Object> source = requireHttpSource(sourceCode, false);
        startManagementPage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = integrationMapper.selectEndpointPage(text(source.get("sourceCode")),
                    limitText(text(keyword), 80), text(status).toUpperCase(Locale.ROOT));
            rows.replaceAll(this::safeEndpoint);
            return rows;
        }
        finally { PageHelper.clearPage(); }
    }

    public Map<String, Object> getEndpoint(Long endpointId)
    {
        Map<String, Object> endpoint = integrationMapper.selectEndpointById(endpointId);
        if (endpoint == null) throw new ServiceException("endpoint 不存在");
        return safeEndpoint(endpoint);
    }

    public Map<String,Object> getEndpointConfiguration(Long endpointId)
    {
        Map<String,Object> endpoint=integrationMapper.selectEndpointById(endpointId);
        if(endpoint==null) throw new ServiceException("接口不存在");
        Map<String,Object> result=safeEndpoint(endpoint);
        result.put("configJson",endpoint.get("configJson"));
        result.put("secret",DashboardIntegrationCrypto.decrypt(text(endpoint.get("credentialCiphertext")),environment));
        try {
            EndpointContext effective=resolveEndpointContext(Map.of("sourceCode",endpoint.get("sourceCode"),"endpointCode",endpoint.get("endpointCode")),true);
            result.put("effectiveSecret",effective.secret());result.put("effectiveProvider",effective.authProvider());result.put("credentialResolved",true);
        } catch(ServiceException ex) {result.put("credentialResolved",false);}
        return result;
    }

    public Map<String,Object> getIntegrationKeyConfiguration(Long keyId)
    {
        Map<String,Object> key=integrationMapper.selectIntegrationKeyById(keyId);
        if(key==null) throw new ServiceException("密钥不存在");
        Map<String,Object> result=safeIntegrationKey(key);
        result.put("secret",DashboardIntegrationCrypto.decrypt(text(key.get("secretCiphertext")),environment));
        return result;
    }

    @Transactional
    public Map<String, Object> createEndpoint(String sourceCode, Map<String, Object> body, String username)
    {
        Map<String, Object> source = requireHttpSource(sourceCode, false);
        Map<String, Object> endpoint = normalizeEndpoint(body, username, false);
        String code = text(endpoint.get("endpointCode"));
        if (integrationMapper.selectEndpointByCode(text(source.get("sourceCode")), code) != null)
            throw new ServiceException("endpoint 编码在该数据源下已存在");
        if ("ACTIVE".equals(endpoint.get("status")))
            throw new ServiceException("endpoint 必须先测试通过后启用");
        endpoint.put("dataSourceId", source.get("dataSourceId"));
        try
        {
            integrationMapper.insertEndpoint(endpoint);
        }
        catch (DuplicateKeyException ex)
        {
            throw new ServiceException("endpoint 编码在该数据源下已存在");
        }
        return getEndpoint(toLong(endpoint.get("endpointId")));
    }

    @Transactional
    public Map<String, Object> updateEndpoint(Map<String, Object> body, String username)
    {
        Long endpointId = toLong(body == null ? null : body.get("endpointId"));
        Map<String, Object> existing = endpointId == null ? null : integrationMapper.selectEndpointById(endpointId);
        if (existing == null) throw new ServiceException("endpoint 不存在");
        if (body.containsKey("endpointCode")
                && !text(body.get("endpointCode")).equalsIgnoreCase(text(existing.get("endpointCode"))))
            throw new ServiceException("endpoint 编码创建后不可修改");
        Map<String, Object> endpoint = normalizeEndpoint(body, username, true);
        boolean contractChanged = body.containsKey("path") || body.containsKey("method")
                || body.containsKey("requestContentType") || body.containsKey("responseType")
                || body.containsKey("configJson") || body.containsKey("authProvider")
                || body.containsKey("credentialRef") || body.containsKey("secret");
        String requestedStatus = text(endpoint.get("status"));
        String effectiveStatus = requestedStatus.isBlank() ? text(existing.get("status")) : requestedStatus;
        if ("ACTIVE".equals(effectiveStatus) && contractChanged)
            throw new ServiceException("endpoint 契约变更前请先停用，变更后必须重新测试");
        if ("ACTIVE".equals(effectiveStatus)
                && !ENDPOINT_TEST_PASSED.contains(text(existing.get("lastTestStatus")).toUpperCase(Locale.ROOT)))
            throw new ServiceException("endpoint 契约变更后必须重新测试通过才能启用");
        if (contractChanged)
        {
            endpoint.put("clearLastTest", true);
            operations.circuitSuccess(text(existing.get("sourceCode"))+":"+text(existing.get("endpointCode")));
        }
        endpoint.put("endpointId", endpointId);
        integrationMapper.updateEndpoint(endpoint);
        return getEndpoint(endpointId);
    }

    @Transactional
    public int deleteEndpoint(Long endpointId)
    {
        Map<String, Object> endpoint = integrationMapper.selectEndpointById(endpointId);
        if (endpoint == null) throw new ServiceException("endpoint 不存在");
        if (integrationMapper.countEndpointReferences(text(endpoint.get("sourceCode")),
                text(endpoint.get("endpointCode"))) > 0)
            throw new ServiceException("endpoint 仍被数据集引用，停用后再删除");
        return integrationMapper.deleteEndpoint(endpointId);
    }

    public Map<String, Object> testEndpoint(String sourceCode, String endpointCode,
            Map<String, Object> testParams)
    {
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(text(sourceCode), text(endpointCode));
        if (endpoint == null) throw new ServiceException("endpoint 不存在");
        Map<String, Object> endpointConfig = parseObject(text(endpoint.get("configJson")), "endpoint 配置");
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("datasetCode", "endpoint-test");
        dataset.put("dataType", "API");
        dataset.put("fieldSchemaJson", "[]");
        Map<String, Object> params = testParams == null ? Collections.emptyMap() : castMap(testParams);
        List<Map<String, Object>> declarations = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (String name : stringList(endpointConfig.get("formFields"), 32, 64))
        {
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", name);
            declaration.put("in", "form");
            declaration.put("type", "STRING");
            declaration.put("required", true);
            declarations.add(declaration);
            declared.add(name);
        }
        Matcher pathMatcher = PATH_PARAMETER.matcher(text(endpoint.get("path")));
        while (pathMatcher.find())
        {
            String name = pathMatcher.group(1) == null ? pathMatcher.group(2) : pathMatcher.group(1);
            if (!declared.add(name)) continue;
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", name);
            declaration.put("in", "path");
            declaration.put("type", "STRING");
            declaration.put("required", true);
            declarations.add(declaration);
        }
        for (String name : params.keySet())
        {
            if (!declared.add(name)) continue;
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", name);
            declaration.put("in", "JSON".equalsIgnoreCase(text(endpoint.get("requestContentType")))
                    ? "json" : "query");
            declaration.put("type", "STRING");
            declarations.add(declaration);
        }
        dataset.put("paramSchemaJson", json(declarations));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceCode", sourceCode);
        config.put("endpointCode", endpointCode);
        config.put("response", endpointConfig);
        long started = System.nanoTime();
        Map<String, Object> result;
        try
        {
            if ("BINARY_MEDIA".equalsIgnoreCase(text(endpoint.get("responseType"))))
                result = testMediaEndpoint(config, params);
            else
                result = executeApiDataset("endpoint-test", dataset, config, params, true);
        }
        catch (Exception ex)
        {
            result = errorResult("endpoint-test", classifyOutboundException(ex), safeMessage(ex));
        }
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000L);
        integrationMapper.updateEndpointTest(toLong(endpoint.get("endpointId")), text(result.get("quality")));
        return result;
    }

    private Map<String, Object> testMediaEndpoint(Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        EndpointContext context = resolveEndpointContext(config, true);
        String requestId = UUID.randomUUID().toString();
        byte[] bytes = requestBinary(context, params == null ? Collections.emptyMap() : params,
                context.endpointConfig(), requestId);
        MediaPayload payload;
        try
        {
            payload = prepareMediaPayload(DashboardIntegrationSecurity.randomToken(48),
                    bytes, context.endpointConfig());
        }
        catch (ServiceException ex)
        {
            throw new OutboundFailure("INVALID_DATA", limitText(ex.getMessage(), 200), false, 0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetCode", "endpoint-test");
        result.put("quality", "SUCCESS");
        result.put("sourceStatus", "CONNECTED");
        result.put("sourceCode", context.sourceCode());
        result.put("endpointCode", context.endpointCode());
        result.put("requestId", requestId);
        result.put("contentType", payload.contentType());
        result.put("byteLength", payload.bytes().length);
        result.put("width", payload.width());
        result.put("height", payload.height());
        result.put("frameCount", payload.frameCount());
        result.put("message", "媒体内容校验通过");
        return result;
    }

    public void validateDatasetReference(String sourceCode, String endpointCode, boolean requireActive)
    {
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(
                requireCode(sourceCode, "sourceCode"), requireCode(endpointCode, "endpointCode"));
        if (endpoint == null) throw new ServiceException("endpoint 不存在");
        if (requireActive && (!"ACTIVE".equalsIgnoreCase(text(endpoint.get("status")))
                || !"ACTIVE".equalsIgnoreCase(text(endpoint.get("sourceStatus")))
                || !ENDPOINT_TEST_PASSED.contains(text(endpoint.get("lastTestStatus")).toUpperCase(Locale.ROOT))))
            throw new ServiceException("数据集只能引用已启用且测试通过的 endpoint");
    }

    public void validateMediaEndpointReference(String sourceCode, String endpointCode, boolean requireActive)
    {
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(
                requireCode(sourceCode, "sourceCode"), requireCode(endpointCode, "媒体 endpointCode"));
        if (endpoint == null || !"BINARY_MEDIA".equalsIgnoreCase(text(endpoint.get("responseType")))
                || !"FORM_URLENCODED".equalsIgnoreCase(text(endpoint.get("requestContentType"))))
            throw new ServiceException("媒体投影必须引用同一来源下的表单媒体 endpoint");
        if (requireActive && (!"ACTIVE".equalsIgnoreCase(text(endpoint.get("status")))
                || !"ACTIVE".equalsIgnoreCase(text(endpoint.get("sourceStatus")))
                || !ENDPOINT_TEST_PASSED.contains(text(endpoint.get("lastTestStatus")).toUpperCase(Locale.ROOT))))
            throw new ServiceException("媒体投影只能引用已启用且测试通过的媒体 endpoint");
    }

    private Map<String, Object> safeEndpoint(Map<String, Object> endpoint)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("endpointId", "dataSourceId", "sourceCode", "sourceName", "sourceType",
                "sourceStatus", "endpointCode", "endpointName", "path", "method", "requestContentType",
                "responseType", "authProvider", "credentialRef", "contractVersion", "status", "lastTestAt",
                "lastTestStatus", "lastRequestId", "lastSuccessAt", "consecutiveFailures", "lastErrorCode",
                "createBy", "createTime", "updateBy", "updateTime", "remark"))
            if (endpoint.containsKey(key)) result.put(key, endpoint.get(key));
        result.put("hasCredential", !text(endpoint.get("credentialCiphertext")).isBlank()
                || !text(endpoint.get("sourceSecretCiphertext")).isBlank());
        result.put("configJson", safeEndpointConfig(endpoint));
        return result;
    }

    private String safeEndpointConfig(Map<String, Object> endpoint)
    {
        Map<String, Object> config = parseObjectQuiet(text(endpoint.get("configJson")));
        for (String key : List.of("secret", "token", "password", "credential", "secretCiphertext"))
            config.remove(key);
        Map<String, Object> safe = new LinkedHashMap<>();
        Set<String> allowed = Set.of("envelopeMode", "successPath", "successValue", "codePath", "successCodes",
                "messagePath", "errorCodePath", "retryableCodes", "retryAfterPath", "partialPath",
                "truncatedPath", "rowsPath", "totalPath", "businessTimePath", "requiredPaths", "rowLimit",
                "maxResponseBytes", "maxAttempts", "backoffMs", "cacheSeconds", "idempotent", "headers",
                "formFields", "mediaProfile", "media", "canonicalProfile", "timestampUnit",
                "clockSkewSeconds", "nonceRequired", "headerMapping", "signatureEncoding",
                "identity", "keyId", "allowedRedirectHosts",
                "rowErrorMode", "circuitFailureThreshold", "circuitRecoverySeconds", "pagination", "sharedFetchSeconds", "staleIfErrorSeconds");
        for (String key : allowed) if (config.containsKey(key)) safe.put(key, config.get(key));
        return json(safe);
    }

    private Map<String, Object> requireHttpSource(String sourceCode, boolean active)
    {
        Map<String, Object> source = dashboardMapper.selectDataSourceByCode(requireCode(sourceCode, "数据源编码"));
        if (source == null) throw new ServiceException("数据源不存在");
        if (!"HTTP".equalsIgnoreCase(text(source.get("sourceType"))))
            throw new ServiceException("endpoint 只能登记在 HTTP 数据源下");
        if (active && !"ACTIVE".equalsIgnoreCase(text(source.get("status"))))
            throw new ServiceException("HTTP 数据源未启用");
        return source;
    }

    private Map<String, Object> normalizeEndpoint(Map<String, Object> body, String username, boolean partial)
    {
        if (body == null) throw new ServiceException("endpoint 配置不能为空");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!partial || body.containsKey("endpointCode"))
            result.put("endpointCode", requireCode(body.get("endpointCode"), "endpoint 编码"));
        if (!partial || body.containsKey("endpointName"))
        {
            String name = text(body.get("endpointName"));
            if (name.isBlank() || name.length() > 100) throw new ServiceException("endpoint 名称不能为空且不能超过 100 个字符");
            result.put("endpointName", name);
        }
        if (!partial || body.containsKey("path"))
        {
            String path = text(body.get("path"));
            if (path.isBlank() || path.length() > 512 || !SAFE_PATH.matcher(path).matches()
                    || path.startsWith("//") || path.contains("\\") || path.contains("://")
                    || path.contains("..") || path.contains("\r") || path.contains("\n"))
                throw new ServiceException("endpoint 路径必须是受控相对路径");
            result.put("path", path);
        }
        if (!partial || body.containsKey("method"))
        {
            String method = text(body.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
            if (!ENDPOINT_METHODS.contains(method)) throw new ServiceException("endpoint 只允许 GET 或 POST");
            result.put("method", method);
        }
        if (!partial || body.containsKey("requestContentType"))
        {
            String type = text(body.getOrDefault("requestContentType", "NONE")).toUpperCase(Locale.ROOT);
            if (!REQUEST_TYPES.contains(type)) throw new ServiceException("请求类型不支持");
            result.put("requestContentType", type);
        }
        if (!partial || body.containsKey("responseType"))
        {
            String type = text(body.getOrDefault("responseType", "JSON")).toUpperCase(Locale.ROOT);
            if (!RESPONSE_TYPES.contains(type)) throw new ServiceException("响应类型不支持");
            result.put("responseType", type);
        }
        if (!partial || body.containsKey("authProvider"))
        {
            String provider = text(body.getOrDefault("authProvider", "INHERIT")).toUpperCase(Locale.ROOT);
            if (!AUTH_PROVIDERS.contains(provider)) throw new ServiceException("认证 provider 不支持");
            result.put("authProvider", provider);
        }
        if (body.containsKey("credentialRef"))
        {
            String ref = text(body.get("credentialRef"));
            if (!ref.isBlank() && !SAFE_EXTERNAL.matcher(ref).matches()) throw new ServiceException("credentialRef 不合法");
            result.put("credentialRef", ref);
        }
        if (!partial || body.containsKey("configJson"))
        {
            Map<String, Object> config = parseObject(jsonString(body.get("configJson"), "{}"), "endpoint 配置");
            validateEndpointConfig(config);
            result.put("configJson", json(config));
        }
        if (body.containsKey("secret") && !text(body.get("secret")).isBlank())
            result.put("credentialCiphertext", DashboardIntegrationCrypto.encrypt(text(body.get("secret"), false), environment));
        if (!partial || body.containsKey("contractVersion"))
        {
            String version = text(body.getOrDefault("contractVersion", "1.0"));
            if (version.isBlank() || version.length() > 64 || !SAFE_EXTERNAL.matcher(version).matches())
                throw new ServiceException("契约版本不合法");
            result.put("contractVersion", version);
        }
        if (!partial || body.containsKey("status"))
        {
            String status = text(body.getOrDefault("status", "DRAFT")).toUpperCase(Locale.ROOT);
            if (!Set.of("DRAFT", "ACTIVE", "DISABLED").contains(status)) throw new ServiceException("endpoint 状态不合法");
            result.put("status", status);
        }
        if (!partial || body.containsKey("remark"))
        {
            String remark = text(body.get("remark"));
            if (remark.length() > 500) throw new ServiceException("endpoint 备注不能超过 500 个字符");
            result.put("remark", remark);
        }
        if (!partial) result.put("createBy", username);
        result.put("updateBy", username);
        return result;
    }

    private void validateEndpointConfig(Map<String, Object> config)
    {
        Set<String> allowed = Set.of("envelopeMode", "successPath", "successValue", "codePath", "successCodes",
                "messagePath", "errorCodePath", "retryableCodes", "retryAfterPath", "partialPath",
                "truncatedPath", "rowsPath", "totalPath", "businessTimePath", "requiredPaths", "rowLimit",
                "maxResponseBytes", "maxAttempts", "backoffMs", "cacheSeconds", "headers", "formFields",
                "mediaProfile", "media", "canonicalProfile", "timestampUnit", "clockSkewSeconds",
                "nonceRequired", "headerMapping", "signatureEncoding", "identity", "keyId",
                "idempotent", "allowedRedirectHosts", "rowErrorMode",
                "circuitFailureThreshold", "circuitRecoverySeconds", "pagination", "sharedFetchSeconds", "staleIfErrorSeconds");
        for (String key : config.keySet())
            if (!allowed.contains(key)) throw new ServiceException("endpoint 配置属性不支持: " + key);
        for (String key : List.of("successPath", "codePath", "messagePath", "errorCodePath", "retryAfterPath",
                "partialPath", "truncatedPath", "rowsPath", "totalPath", "businessTimePath"))
            validateJsonPath(config.get(key), "endpoint " + key);
        String envelope = text(config.getOrDefault("envelopeMode", "HTTP_ONLY")).toUpperCase(Locale.ROOT);
        if (!Set.of("HTTP_ONLY", "JSON_BOOLEAN", "JSON_CODE", "OPTIONAL").contains(envelope))
            throw new ServiceException("业务信封模式不支持: " + envelope);
        if ("JSON_BOOLEAN".equals(envelope) && text(config.get("successPath")).isBlank())
            throw new ServiceException("JSON_BOOLEAN 必须配置 successPath");
        if ("JSON_CODE".equals(envelope) && text(config.get("codePath")).isBlank())
            throw new ServiceException("JSON_CODE 必须配置 codePath");
        if ("JSON_BOOLEAN".equals(envelope) && !(config.get("successValue") instanceof Boolean))
            throw new ServiceException("JSON_BOOLEAN 必须配置布尔 successValue");
        validateBoundedInt(config, "rowLimit", 1, MAX_ROWS, 1000);
        validateBoundedInt(config, "maxResponseBytes", 1024, MAX_RESPONSE_BYTES, DEFAULT_MAX_RESPONSE_BYTES);
        validateBoundedInt(config, "maxAttempts", 1, 4, 2);
        validateBoundedInt(config, "backoffMs", 0, 10000, 100);
        DashboardIntegrationCachePolicy.validate(config);
        validateBoundedInt(config, "clockSkewSeconds", 1, 3600, 300);
        validateBoundedInt(config, "circuitFailureThreshold", 1, 20, 5);
        validateBoundedInt(config, "circuitRecoverySeconds", 1, 3600, 60);
        if(config.get("pagination") instanceof Map<?,?> raw) {
            Map<String,Object> p=castMap(raw);
            String mode=text(p.getOrDefault("mode","NONE"));
            if(!Set.of("NONE","PAGE","OFFSET","CURSOR").contains(mode)) throw new ServiceException("分页模式不支持");
            for(String key:p.keySet()) if(!Set.of("mode","pageParam","sizeParam","pageSize","startPage","maxPages","maxRows","nextCursorPath","initialCursor","in").contains(key)) throw new ServiceException("分页配置不支持: "+key);
            validateBoundedInt(p,"pageSize",1,1000,100); validateBoundedInt(p,"maxPages",1,100,10);
            validateBoundedInt(p,"maxRows",1,1000,1000); validateBoundedInt(p,"startPage",0,1000000,1);
            for(String key:List.of("pageParam","sizeParam")) if(p.containsKey(key)&&!text(p.get(key)).matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new ServiceException("分页参数名不合法");
            if(text(p.get("pageParam")).equals(text(p.get("sizeParam")))&&p.containsKey("pageParam")) throw new ServiceException("分页参数名不能重复");
            if(!Set.of("query","json","form").contains(text(p.getOrDefault("in","query")))) throw new ServiceException("分页参数位置不支持");
            if("CURSOR".equals(mode)&&text(p.get("nextCursorPath")).isBlank()) throw new ServiceException("游标分页必须配置 nextCursorPath");
            validateJsonPath(p.get("nextCursorPath"),"游标路径");
            if(!"NONE".equals(mode)&&text(config.get("rowsPath")).contains("[")) throw new ServiceException("分页数组路径仅支持对象字段路径");
        } else if(config.containsKey("pagination")) throw new ServiceException("分页配置必须为对象");
        validateHeaders(config.get("headers"));
        if (config.containsKey("formFields")) validateStringArray(config.get("formFields"), "formFields", 32, 64);
        if (config.containsKey("requiredPaths")) validateStringArray(config.get("requiredPaths"), "requiredPaths", 32, 128);
        String profile = text(config.getOrDefault("canonicalProfile", ""));
        if (!profile.isBlank() && !Set.of("APP_KEY_TIMESTAMP_V1",
                "METHOD_PATH_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1",
                "METHOD_PATH_TIMESTAMP_NONCE_BODY_V1").contains(profile))
            throw new ServiceException("签名原文 profile 不支持");
        String signatureEncoding = text(config.getOrDefault("signatureEncoding", "BASE64")).toUpperCase(Locale.ROOT);
        if (!Set.of("BASE64", "HEX").contains(signatureEncoding))
            throw new ServiceException("签名编码不支持");
        for (String key : List.of("identity", "keyId"))
        {
            String value = text(config.get(key));
            if (!value.isBlank() && !DashboardIntegrationSecurity.isSafeExternalValue(value))
                throw new ServiceException(key + " 不合法");
        }
        String rowErrorMode = text(config.getOrDefault("rowErrorMode", "PARTIAL")).toUpperCase(Locale.ROOT);
        if (!Set.of("PARTIAL", "REJECT").contains(rowErrorMode))
            throw new ServiceException("行错误处理策略不支持");
        String mediaProfile = text(config.getOrDefault("mediaProfile", "IMAGE")).toUpperCase(Locale.ROOT);
        if (config.containsKey("mediaProfile") && !Set.of("IMAGE", "DOCUMENT").contains(mediaProfile))
            throw new ServiceException("媒体 profile 不支持");
        Object media = config.get("media");
        if (media != null && !(media instanceof Map<?, ?>)) throw new ServiceException("媒体策略必须是对象");
        if (media instanceof Map<?, ?> map)
        {
            for (Object key : map.keySet())
                if (!Set.of("allowedMimeTypes", "maxBytes", "ttlSeconds", "allowRange", "inline",
                        "maxWidth", "maxHeight", "maxPixels", "maxAnimationFrames", "stripMetadata",
                        "firstByteTimeoutSeconds", "downloadTimeoutSeconds", "concurrencyLimit", "scanRequired")
                        .contains(String.valueOf(key)))
                    throw new ServiceException("媒体策略属性不支持: " + key);
            if (map.containsKey("allowedMimeTypes"))
            {
                validateStringArray(map.get("allowedMimeTypes"), "allowedMimeTypes", 16, 80);
                for (String mime : stringList(map.get("allowedMimeTypes"), 16, 80))
                    if (!SUPPORTED_MEDIA_TYPES.contains(mime.toLowerCase(Locale.ROOT)))
                        throw new ServiceException("媒体 MIME 不支持: " + mime);
            }
            Map<String, Object> policy = castMap(map);
            validateBoundedInt(policy, "maxBytes", 1024, MAX_RESPONSE_BYTES, DEFAULT_MAX_RESPONSE_BYTES);
            validateBoundedInt(policy, "ttlSeconds", 1, 86400, 300);
            validateBoundedInt(policy, "maxWidth", 1, 32768, 8192);
            validateBoundedInt(policy, "maxHeight", 1, 32768, 8192);
            validateBoundedInt(policy, "maxPixels", 1, 25_000_000, 12_000_000);
            validateBoundedInt(policy, "maxAnimationFrames", 1, 100, 1);
            validateBoundedInt(policy, "firstByteTimeoutSeconds", 1, 120, 15);
            validateBoundedInt(policy, "downloadTimeoutSeconds", 1, 300, 30);
            validateBoundedInt(policy, "concurrencyLimit", 1, 100, 10);
            validateBoolean(policy, "scanRequired");
            validateBoolean(policy, "inline");
            validateBoolean(policy, "stripMetadata");
            validateBoolean(policy, "allowRange");
            if (Boolean.TRUE.equals(policy.get("allowRange")))
                throw new ServiceException("当前媒体代理未启用 Range，请保持 allowRange=false");
        }
        validateHeaders(config.get("headerMapping"));
    }

    /* ----------------------------- 出站执行 ----------------------------- */

    public Map<String, Object> executeApiDataset(String datasetCode, Map<String, Object> dataset,
            Map<String, Object> config, Map<String, Object> params) throws Exception
    {
        return executeApiDataset(datasetCode, dataset, config, params, false);
    }

    private Map<String, Object> executeApiDataset(String datasetCode, Map<String, Object> dataset,
            Map<String, Object> config, Map<String, Object> params, boolean allowDraft) throws Exception
    {
        EndpointContext context = resolveEndpointContext(config, allowDraft);
        Map<String,Object> normalized = normalizeOutboundParams(dataset,params);
        Map<String,Object> responseConfig = new LinkedHashMap<>(context.endpointConfig());
        if(config.get("response") instanceof Map<?,?> nested)
            for(String key:List.of("rowsPath","totalPath","businessTimePath","rowErrorMode","requiredPaths"))
                if(nested.containsKey(key)) responseConfig.put(key,nested.get(key));
        for(String key:List.of("rowsPath","totalPath","businessTimePath","rowErrorMode","mediaProjections"))
            if(config.containsKey(key)) responseConfig.put(key,config.get(key));
        LinkedHashSet<String> required=new LinkedHashSet<>(stringList(context.endpointConfig().get("requiredPaths"),32,128));
        required.addAll(stringList(responseConfig.get("requiredPaths"),32,128));
        responseConfig.put("requiredPaths",new ArrayList<>(required));
        try
        {
            Map<String,Object> raw = fetchPagedRaw(context,dataset,normalized,allowDraft);
            byte[] body=Base64.getDecoder().decode(text(raw.get("body")));
            int rowLimit=Math.min(integer(config.get("rowLimit"),integer(context.endpointConfig().get("rowLimit"),MAX_ROWS)),MAX_ROWS);
            Map<String,Object> result=parseOutboundJson(datasetCode,dataset,responseConfig,context,body,
                    text(raw.get("requestId")),text(raw.get("sourceRequestId")),rowLimit);
            result.put("fetchedAt",raw.get("fetchedAt"));
            result.put("cacheHit",raw.getOrDefault("cacheHit",false));
            result.put("pageCount",raw.getOrDefault("pageCount",1));
            if(Boolean.TRUE.equals(raw.get("truncated"))) { result.put("quality","TRUNCATED"); result.put("truncated",true); }
            if(Boolean.TRUE.equals(raw.get("snapshotStale"))) {
                result.put("stale",true); result.put("quality","STALE"); result.put("sourceStatus","NOT_CONNECTED");
                result.put("sourceErrorCode",raw.get("sourceErrorCode"));
            }
            updateEndpointHealth(context.endpointId(),result);
            return result;
        }
        catch(OutboundFailure ex)
        {
            Map<String,Object> result=errorWithSource(datasetCode,ex.quality(),ex.getMessage(),context.sourceCode(),context.endpointCode());
            result.put("requestId",UUID.randomUUID().toString());
            if(!ex.sourceErrorCode().isBlank()) result.put("sourceErrorCode",ex.sourceErrorCode());
            if(ex.retryAfterSeconds()>0) result.put("retryAfterSeconds",ex.retryAfterSeconds());
            updateEndpointHealth(context.endpointId(),result);
            return result;
        }
    }

    private Map<String,Object> fetchPagedRaw(EndpointContext context,Map<String,Object> dataset,
            Map<String,Object> params,boolean testing) throws Exception
    {
        Map<String,Object> pagination=context.endpointConfig().get("pagination") instanceof Map<?,?> p?castMap(p):Map.of();
        String mode=text(pagination.getOrDefault("mode","NONE"));
        if("NONE".equals(mode)) return fetchSharedRaw(context,dataset,params,testing);
        if(!"GET".equals(context.method())&&!booleanValue(context.endpointConfig().get("idempotent"),false))
            throw new ServiceException("分页接口必须声明幂等");
        int maxPages=integer(pagination.get("maxPages"),10),size=integer(pagination.get("pageSize"),100);
        int maxRows=integer(pagination.get("maxRows"),1000);
        String pageParam=text(pagination.getOrDefault("pageParam", "CURSOR".equals(mode)?"cursor":"pageNum"));
        String sizeParam=text(pagination.getOrDefault("sizeParam","pageSize"));
        List<Map<String,Object>> schema=new ArrayList<>(parseParamSchema(dataset.get("paramSchemaJson")));
        schema.removeIf(item -> pageParam.equals(text(item.get("name")))||sizeParam.equals(text(item.get("name"))));
        String location=text(pagination.getOrDefault("in","query"));
        schema.add(Map.of("name",pageParam,"type","CURSOR".equals(mode)?"STRING":"INTEGER","in",location));
        schema.add(Map.of("name",sizeParam,"type","INTEGER","in",location));
        Map<String,Object> requestDataset=new LinkedHashMap<>(dataset);
        requestDataset.put("paramSchemaJson",json(schema));
        Map<String,Object> requestParams=new LinkedHashMap<>(params); requestParams.put(sizeParam,size);
        String rowsPath=text(context.endpointConfig().get("rowsPath"));
        Set<String> visited=new HashSet<>(),pageHashes=new HashSet<>();
        com.fasterxml.jackson.databind.node.ArrayNode all=objectMapper.createArrayNode();
        JsonNode first=null; Map<String,Object> aggregate=null;
        Object cursor=pagination.getOrDefault("startPage",1);
        if("CURSOR".equals(mode)) cursor=String.valueOf(pagination.getOrDefault("initialCursor",""));
        boolean ended=false; int bytes=0;
        for(int page=0;page<maxPages;page++)
        {
            if(!visited.add(String.valueOf(cursor))) break;
            if("CURSOR".equals(mode)&&text(cursor).isBlank()) requestParams.remove(pageParam);
            else requestParams.put(pageParam,cursor);
            Map<String,Object> raw=fetchSharedRaw(context,requestDataset,requestParams,testing);
            byte[] body=Base64.getDecoder().decode(text(raw.get("body"))); bytes+=body.length;
            if(bytes>MAX_RESPONSE_BYTES) break;
            JsonNode root=objectMapper.readTree(body);
            JsonNode rows=rowsPath.isBlank()?root:atPathStrict(root,rowsPath);
            if(rows==null||!rows.isArray()) throw new OutboundFailure("INVALID_DATA","分页结果必须为数组",false,0);
            if(first==null) { first=root.deepCopy(); aggregate=new LinkedHashMap<>(raw); }
            aggregate.put("pageCount",page+1);
            if(Boolean.TRUE.equals(raw.get("snapshotStale"))) {
                aggregate.put("snapshotStale",true); aggregate.put("sourceErrorCode",raw.get("sourceErrorCode"));
            }
            if(rows.size()==0) { ended=true; break; }
            String hash=DashboardIntegrationSecurity.sha256(rows.toString().getBytes(StandardCharsets.UTF_8));
            if(!pageHashes.add(hash)) break;
            int previous=all.size();
            for(JsonNode item:rows) { if(all.size()>=maxRows) break; all.add(item); }
            if("CURSOR".equals(mode))
            {
                JsonNode next=atPathStrict(root,text(pagination.get("nextCursorPath")));
                if(next==null||next.isNull()||(next.isTextual()&&next.asText().isBlank())) { ended=true; break; }
                if(!next.isValueNode()||next.asText().length()>256) throw new OutboundFailure("INVALID_DATA","分页游标不合法",false,0);
                cursor=next.asText();
            }
            else
            {
                String totalPath=text(context.endpointConfig().get("totalPath"));
                JsonNode total=totalPath.isBlank()?null:atPathStrict(root,totalPath);
                if(rows.size()<size||(total!=null&&total.isNumber()&&all.size()>=total.asLong())) { ended=true; break; }
                cursor=integer(cursor,1)+("OFFSET".equals(mode)?size:1);
            }
            if(all.size()>=maxRows||all.size()-previous<rows.size()) break;
        }
        if(aggregate==null) throw new OutboundFailure("INVALID_DATA","分页结果超限或为空",false,0);
        if(rowsPath.isBlank()) first=all;
        else {
            String[] parts=rowsPath.split("\\."); JsonNode parent=first;
            for(int i=0;i<parts.length-1;i++) parent=parent.path(parts[i]);
            if(!(parent instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) throw new ServiceException("分页数组路径必须为对象字段路径");
            object.set(parts[parts.length-1],all);
        }
        aggregate.put("body",Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(first)));
        aggregate.put("truncated",!ended);
        return aggregate;
    }

    private Map<String,Object> fetchSharedRaw(EndpointContext context,Map<String,Object> dataset,
            Map<String,Object> params,boolean testing)
    {
        // 缓存原始抓取，不缓存投影和一次性媒体候选。每个数据集重新校验并独立投影。
        Map<String,Object> identity=new LinkedHashMap<>();
        identity.put("source",context.sourceCode()); identity.put("endpoint",context.endpointCode());
        identity.put("sourceConfig",context.sourceConfig()); identity.put("contract",context.endpointConfig());
        identity.put("contractVersion",context.endpoint().get("contractVersion"));
        identity.put("method",context.method()); identity.put("path",context.path());
        identity.put("provider",context.authProvider()); identity.put("secretHash",DashboardIntegrationSecurity.sha256(context.secret().getBytes(StandardCharsets.UTF_8)));
        identity.put("schemaVersion",dataset.get("schemaVersion"));
        identity.put("params",new java.util.TreeMap<>(params)); identity.put("schema",parseParamSchema(dataset.get("paramSchemaJson")));
        identity.put("environment",environment.getProperty("spring.profiles.active","default"));
        String key=DashboardIntegrationSecurity.sha256(json(canonicalJsonValue(objectMapper.valueToTree(identity))).getBytes(StandardCharsets.UTF_8));
        int fresh=testing?0:Math.max(integer(context.endpointConfig().get("cacheSeconds"),0),integer(context.endpointConfig().get("sharedFetchSeconds"),1));
        int stale=testing?0:integer(context.endpointConfig().get("staleIfErrorSeconds"),0);
        if(fresh==0 && stale==0) return fetchRaw(context,dataset,params,testing);
        Map<String,Object> cached=testing?null:operations.snapshot(key,false);
        if(cached!=null) { cached.put("cacheHit",true); return cached; }
        try
        {
            Map<String,Object> result=operations.withLease("fetch:"+key,300,()-> {
                Map<String,Object> second=testing?null:operations.snapshot(key,false);
                if(second!=null) { second.put("cacheHit",true); return second; }
                Map<String,Object> raw=fetchRaw(context,dataset,params,testing);
                if((fresh>0||stale>0) && identity.get("secretHash").equals(raw.get("credentialFingerprint"))) operations.saveSnapshot(key,context.sourceCode(),context.endpointCode(),raw,fresh,stale);
                return raw;
            });
            if(result==null) {
                // 另一实例正在取数；有界等待其持久快照，不发出第二份上游请求。
                for(int n=0;n<100;n++) {
                    try { Thread.sleep(100); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                    Map<String,Object> shared=operations.snapshot(key,false);
                    if(shared!=null) { shared.put("cacheHit",true); return shared; }
                }
                throw new OutboundFailure("RATE_LIMITED","来源抓取正在进行",true,1);
            }
            return result;
        }
        catch(OutboundFailure ex)
        {
            if(stale>0 && Set.of("TIMEOUT","SOURCE_ERROR","RATE_LIMITED","CIRCUIT_OPEN").contains(ex.quality())) {
                Map<String,Object> previous=operations.snapshot(key,true);
                if(previous!=null) { previous.put("snapshotStale",true); previous.put("sourceErrorCode",ex.quality()); return previous; }
            }
            throw ex;
        }
    }

    private Map<String,Object> fetchRaw(EndpointContext context,Map<String,Object> dataset,Map<String,Object> params,boolean testing)
    {
        Map<String,Object> config=context.endpointConfig();
        String circuitKey=context.sourceCode()+":"+context.endpointCode();
        long remaining=operations.circuitRemaining(circuitKey);
        if(remaining>0) throw new OutboundFailure("CIRCUIT_OPEN","来源断路器暂时开启",true,remaining);
        String source=context.sourceCode();
        int timeout=Math.max(1,Math.min(integer(context.sourceConfig().get("timeoutSeconds"),10),Math.min(integer(dataset.get("timeoutSeconds"),120),120)));
        int attempts=("GET".equals(context.method())||booleanValue(config.get("idempotent"),false))?integer(config.get("maxAttempts"),2):1;
        String permit=operations.acquirePermit("out:"+source,integer(context.sourceConfig().get("concurrencyLimit"),20),attempts*(timeout+30)+5);
        if(permit==null) throw new OutboundFailure("RATE_LIMITED","来源并发达到上限",true,1);
        String requestId=UUID.randomUUID().toString(),idempotency=DashboardIntegrationSecurity.randomToken(24);
        long started=System.nanoTime(); OutboundFailure last=null;
        try
        {
            for(int attempt=1;attempt<=attempts;attempt++) {
                try {
                    if(!operations.rateAllowed("out:"+source,integer(context.sourceConfig().get("rateLimit"),20)))
                        throw new OutboundFailure("RATE_LIMITED","来源请求速率达到上限",true,1);
                    // 每次重试均重建请求，签名和时钟使用当次凭证。
                    EndpointContext current=resolveEndpointContext(Map.of("sourceCode",context.sourceCode(),"endpointCode",context.endpointCode()),testing);
                    HttpRequest request=buildOutboundRequest(current,dataset,config,params,requestId,idempotency,timeout);
                    HttpResponse<InputStream> response=DashboardPinnedHttp.send(request,validateOutboundTarget(request.uri().toString(),current.sourceConfig()));
                    try(InputStream body=response.body()) {
                        int status=response.statusCode();
                        if(status==401||status==403) throw new OutboundFailure("AUTH_ERROR","来源鉴权失败",false,0);
                        if(status==408) throw new OutboundFailure("TIMEOUT","来源请求超时",true,retryAfter(response));
                        if(status==429) throw new OutboundFailure("RATE_LIMITED","来源请求受限",true,retryAfter(response));
                        if(status<200||status>=300) throw new OutboundFailure("SOURCE_ERROR","来源返回 HTTP "+status,status>=500,retryAfter(response));
                        if(!isJsonContentType(response.headers().firstValue("Content-Type").orElse(""))) throw new OutboundFailure("INVALID_DATA","来源响应不是 JSON",false,0);
                        byte[] bytes=readBoundedWithTimeout(body,integer(config.get("maxResponseBytes"),DEFAULT_MAX_RESPONSE_BYTES),timeout);
                        JsonNode root=objectMapper.readTree(bytes); validateJsonShape(root,0,new int[]{0});
                        EnvelopeResult envelope=evaluateEnvelope(root,config);
                        if(!envelope.success()) throw new OutboundFailure(envelope.quality(),envelope.message(),envelope.retryable(),envelope.retryAfterSeconds(),envelope.errorCode());
                        Map<String,Object> raw=new LinkedHashMap<>();
                        raw.put("body",Base64.getEncoder().encodeToString(bytes)); raw.put("requestId",requestId);
                        raw.put("credentialFingerprint",DashboardIntegrationSecurity.sha256(current.secret().getBytes(StandardCharsets.UTF_8)));
                        raw.put("sourceRequestId",limitText(response.headers().firstValue("X-Request-Id").orElse(""),128));
                        raw.put("fetchedAt",Instant.now().toString()); raw.put("cacheHit",false);
                        operations.circuitSuccess(circuitKey);
                        operations.audit("OUTBOUND",source+"/"+context.endpointCode(),requestId,"SUCCESS","system",bytes.length,(System.nanoTime()-started)/1000000);
                        return raw;
                    }
                }
                catch(OutboundFailure ex) { last=ex; }
                catch(java.net.http.HttpTimeoutException|java.net.SocketTimeoutException|ConnectException ex) { last=new OutboundFailure("TIMEOUT","来源连接或读取超时",true,0); }
                catch(com.fasterxml.jackson.core.JsonProcessingException ex) { last=new OutboundFailure("INVALID_DATA","来源 JSON 无效",false,0); }
                catch(IOException ex) { last=new OutboundFailure("SOURCE_ERROR","来源读取失败",true,0); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new ServiceException("来源请求被中断"); }
                if(!last.retryable()||attempt>=attempts) break;
                sleepBackoff(config,attempt,last.retryAfterSeconds());
            }
            operations.circuitFailure(circuitKey,integer(config.get("circuitFailureThreshold"),5),integer(config.get("circuitRecoverySeconds"),60));
            operations.audit("OUTBOUND",source+"/"+context.endpointCode(),requestId,last.quality(),"system",0,(System.nanoTime()-started)/1000000);
            throw last;
        }
        finally { operations.releasePermit("out:"+source,permit); }
    }

    private HttpRequest buildOutboundRequest(EndpointContext context, Map<String, Object> dataset,
            Map<String, Object> responseConfig, Map<String, Object> params, String requestId,
            String idempotencyKey, int timeoutSeconds)
    {
        Map<String, Object> safeParams = normalizeOutboundParams(dataset, params);
        String resolvedPath = resolvePath(context.path(), safeParams);
        String target = joinUrl(text(context.sourceConfig().get("baseUrl")), resolvedPath);
        validateOutboundTarget(target, context.sourceConfig());
        URI uri = URI.create(target);
        String method = context.method();
        String requestType = context.requestContentType();
        Map<String, Object> bodyValues = new LinkedHashMap<>();
        Map<String, String> parameterHeaders = new LinkedHashMap<>();
        List<Map<String, Object>> declarations = parseParamSchema(dataset.get("paramSchemaJson"));
        for (Map<String, Object> declaration : declarations)
        {
            String name = text(declaration.get("name"));
            if (name.isBlank() || !safeParams.containsKey(name)) continue;
            String location = text(declaration.getOrDefault("in", declaration.getOrDefault("location", "query")))
                    .toLowerCase(Locale.ROOT);
            Object value = safeParams.get(name);
            if ("query".equals(location))
            {
                String separator = target.contains("?") ? "&" : "?";
                target += separator + encode(name) + "=" + encode(scalarText(value, name));
            }
            else if ("header".equals(location))
            {
                String headerName = text(declaration.getOrDefault("header", name));
                if (!SAFE_HEADER.matcher(headerName).matches() || isForbiddenHeader(headerName))
                    throw new ServiceException("参数请求头不受支持: " + headerName);
                parameterHeaders.put(headerName, scalarText(value, name));
            }
            else if ("json".equals(location) || "form".equals(location))
            {
                if ("NONE".equals(requestType) || ("JSON".equals(requestType) && !"json".equals(location))
                        || ("FORM_URLENCODED".equals(requestType) && !"form".equals(location)))
                    throw new ServiceException("参数位置与 endpoint 请求类型不匹配: " + name);
                bodyValues.put(name, value);
            }
            else if (!"path".equals(location))
                throw new ServiceException("参数位置不支持: " + location);
        }
        // 查询参数会改变最终 request-target，需要再次做 DNS/主机校验。
        validateOutboundTarget(target, context.sourceConfig());
        URI uriWithQuery = URI.create(target);
        byte[] body = new byte[0];
        String contentType = "";
        if ("JSON".equals(requestType))
        {
            body = json(bodyValues).getBytes(StandardCharsets.UTF_8);
            contentType = "application/json";
        }
        else if ("FORM_URLENCODED".equals(requestType))
        {
            body = formBody(bodyValues).getBytes(StandardCharsets.UTF_8);
            contentType = "application/x-www-form-urlencoded";
        }
        if ("GET".equals(method) && body.length > 0)
            throw new ServiceException("GET endpoint 不能提交请求体");
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        String accept = "BINARY_MEDIA".equals(context.responseType())
                ? "application/octet-stream,image/png,image/jpeg" : "application/json";
        HttpRequest.Builder builder = HttpRequest.newBuilder(uriWithQuery)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", accept)
                .header("Accept-Encoding", "identity")
                .header("X-Request-Id", requestId)
                .header("X-Requested-By", "engineering-data-cockpit");
        if (!idempotencyKey.isBlank()) builder.header("Idempotency-Key", idempotencyKey);
        if (!contentType.isBlank()) builder.header("Content-Type", contentType);
        if (responseConfig.get("headers") instanceof Map<?, ?> headers)
            headers.forEach((key, value) -> {
                String name = text(key);
                if (!SAFE_HEADER.matcher(name).matches() || isForbiddenHeader(name))
                    throw new ServiceException("静态请求头不受支持: " + name);
                builder.header(name, scalarText(value, name));
            });
        parameterHeaders.forEach(builder::header);
        applyOutboundAuthentication(builder, context, method,
                uriWithQuery.getRawPath() + (uriWithQuery.getRawQuery() == null ? "" : "?" + uriWithQuery.getRawQuery()),
                body, requestId, idempotencyKey, dataset);
        if ("GET".equals(method)) builder.GET();
        else if ("POST".equals(method)) builder.POST(publisher);
        else throw new ServiceException("endpoint 方法不支持");
        return builder.build();
    }

    private void applyOutboundAuthentication(HttpRequest.Builder builder, EndpointContext context, String method,
            String requestTarget, byte[] body, String requestId, String idempotencyKey,
            Map<String, Object> dataset)
    {
        String provider = context.authProvider();
        String secret = context.secret();
        if ("NO_AUTH".equals(provider)) return;
        if (secret.isBlank()) throw new ServiceException("endpoint 未配置认证凭证");
        if ("BEARER".equals(provider))
        {
            builder.header("Authorization", secret.startsWith("Bearer ") ? secret : "Bearer " + secret);
            return;
        }
        if ("API_KEY".equals(provider))
        {
            String header = text(context.headerMapping().getOrDefault("apiKey", "X-Api-Key"));
            if (!SAFE_HEADER.matcher(header).matches() || isForbiddenHeader(header))
                throw new ServiceException("API Key 请求头不受支持");
            putSafeHeader(builder, header, secret);
            return;
        }
        if (!Set.of("HMAC_V1", "HMAC_APPKEY_TIMESTAMP_V1").contains(provider))
            throw new ServiceException("认证 provider 未实现: " + provider);
        String identity = context.identity();
        if (identity.isBlank()) throw new ServiceException("HMAC 未配置身份标识");
        String timestamp = switch (context.timestampUnit())
        {
            case "MILLISECONDS" -> String.valueOf(System.currentTimeMillis());
            case "RFC3339", "ISO8601" -> Instant.now().toString();
            default -> String.valueOf(System.currentTimeMillis() / 1000);
        };
        String nonce = context.nonceRequired() ? DashboardIntegrationSecurity.randomToken(18) : "";
        String bodyHash = DashboardIntegrationSecurity.sha256(body);
        String canonical;
        if ("APP_KEY_TIMESTAMP_V1".equals(context.canonicalProfile()))
            canonical = identity + timestamp;
        else if ("METHOD_PATH_TIMESTAMP_NONCE_BODY_V1".equals(context.canonicalProfile()))
            canonical = method + "\n" + requestTarget + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
        else
            canonical = method + "\n" + requestTarget + "\n" + identity + "\n" + timestamp + "\n"
                    + context.keyId() + "\n" + nonce + "\n" + idempotencyKey + "\n"
                    + text(dataset.get("schemaVersion")) + "\n" + bodyHash;
        Map<String, String> mapping = context.headerMapping();
        putSafeHeader(builder, mapping.getOrDefault("identity", "X-App-Key"), identity);
        putSafeHeader(builder, mapping.getOrDefault("timestamp", "X-Timestamp"), timestamp);
        if (!context.keyId().isBlank()) putSafeHeader(builder, mapping.getOrDefault("keyId", "X-Key-Id"), context.keyId());
        if (!nonce.isBlank()) putSafeHeader(builder, mapping.getOrDefault("nonce", "X-Nonce"), nonce);
        String signature = DashboardIntegrationSecurity.hmacSha256Base64(secret, canonical);
        if ("HEX".equals(context.signatureEncoding())) signature = hexHmac(secret, canonical);
        putSafeHeader(builder, mapping.getOrDefault("signature", "X-Signature"), signature);
    }

    private Map<String, Object> parseOutboundJson(String datasetCode, Map<String, Object> dataset,
            Map<String, Object> responseConfig, EndpointContext context, byte[] body, String requestId,
            String sourceRequestId, int rowLimit)
    {
        JsonNode root;
        try
        {
            root = objectMapper.readTree(body);
            validateJsonShape(root, 0, new int[] { 0 });
        }
        catch (Exception ex)
        {
            throw new OutboundFailure("INVALID_DATA", "来源响应不是合法 JSON", false, 0);
        }
        EnvelopeResult envelope = evaluateEnvelope(root, responseConfig);
        if (!envelope.success())
        {
            if (envelope.retryable())
                throw new OutboundFailure(envelope.quality(), envelope.message(), true,
                        envelope.retryAfterSeconds(), envelope.errorCode());
            Map<String, Object> failure = errorWithSource(datasetCode, envelope.quality(), envelope.message(),
                    context.sourceCode(), context.endpointCode());
            failure.put("sourceStatus", "CONNECTED");
            failure.put("requestId", requestId);
            if (!envelope.errorCode().isBlank()) failure.put("sourceErrorCode", envelope.errorCode());
            if (envelope.retryAfterSeconds() > 0)
                failure.put("retryAfterSeconds", envelope.retryAfterSeconds());
            return failure;
        }
        for (String requiredPath : stringList(responseConfig.get("requiredPaths"), 32, 128))
            if (!hasPath(root, requiredPath))
                throw new OutboundFailure("INVALID_DATA", "来源响应缺少必需路径", false, 0);
        String rowsPath = text(responseConfig.get("rowsPath"));
        JsonNode rowsNode = rowsPath.isBlank() ? root : atPathStrict(root, rowsPath);
        if (rowsNode == null || rowsNode.isMissingNode() || rowsNode.isNull())
            throw new OutboundFailure("INVALID_DATA", "来源响应缺少数据路径", false, 0);
        List<JsonNode> sourceRows = toRowNodes(rowsNode);
        ProjectionResult projection = projectRows(sourceRows, dataset, responseConfig, context,
                text(context.sourceConfig().getOrDefault("timezone", "Asia/Shanghai")),
                "endpoint-test".equals(datasetCode));
        int effectiveLimit = Math.max(1, Math.min(rowLimit, MAX_ROWS));
        List<Map<String, Object>> rows = projection.rows();
        boolean sourcePartial = responseQualityFlag(root, responseConfig, "partialPath");
        boolean sourceTruncated = responseQualityFlag(root, responseConfig, "truncatedPath");
        boolean truncated = sourceTruncated || sourceRows.size() > effectiveLimit || rows.size() > effectiveLimit;
        if (rows.size() > effectiveLimit) rows = new ArrayList<>(rows.subList(0, effectiveLimit));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetCode", datasetCode);
        result.put("rows", rows);
        result.put("quality", truncated ? "TRUNCATED"
                : projection.partial() || sourcePartial ? "PARTIAL" : rows.isEmpty() ? "NO_DATA" : "SUCCESS");
        result.put("stale", false);
        result.put("sourceCode", context.sourceCode());
        result.put("endpointCode", context.endpointCode());
        result.put("sourceStatus", "CONNECTED");
        result.put("requestId", requestId);
        result.put("rowCount", rows.size());
        result.put("truncated", truncated);
        result.put("partial", projection.partial() || sourcePartial);
        if (!projection.errors().isEmpty()) result.put("rowErrors", projection.errors());
        if (!sourceRequestId.isBlank()) result.put("sourceRequestId", limitText(sourceRequestId, 128));
        String totalPath = text(responseConfig.get("totalPath"));
        if (!totalPath.isBlank())
        {
            JsonNode total = atPathStrict(root, totalPath);
            if (total == null || !total.isNumber() || total.decimalValue().signum() < 0)
                throw new OutboundFailure("INVALID_DATA", "总数路径不是合法数字", false, 0);
            result.put("total", total.numberValue());
        }
        String businessTimePath = text(responseConfig.get("businessTimePath"));
        if (!businessTimePath.isBlank())
        {
            JsonNode time = atPathStrict(root, businessTimePath);
            if (time == null || !time.isValueNode() || time.asText().isBlank())
                throw new OutboundFailure("INVALID_DATA", "业务时间路径缺失", false, 0);
            result.put("businessTime", normalizeDateTime(time.asText(),
                    text(context.sourceConfig().getOrDefault("timezone", "Asia/Shanghai"))));
        }
        result.put("fetchedAt", Instant.now().toString());
        return result;
    }

    private EndpointContext resolveEndpointContext(Map<String, Object> config, boolean allowDraft)
    {
        String sourceCode = requireCode(config.get("sourceCode"), "sourceCode");
        String endpointCode = requireCode(config.get("endpointCode"), "endpointCode");
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(sourceCode, endpointCode);
        if (endpoint == null) throw new ServiceException("endpoint 不存在");
        if (!allowDraft && !"ACTIVE".equalsIgnoreCase(text(endpoint.get("status"))))
            throw new ServiceException("endpoint 未启用");
        if (!allowDraft && !"ACTIVE".equalsIgnoreCase(text(endpoint.get("sourceStatus"))))
            throw new ServiceException("数据源未启用");
        Map<String, Object> sourceConfig = parseObject(text(endpoint.get("sourceConfigJson")), "HTTP 数据源配置");
        Map<String, Object> endpointConfig = parseObject(text(endpoint.get("configJson")), "endpoint 配置");
        String baseUrl = text(sourceConfig.get("baseUrl"));
        if (baseUrl.isBlank()) throw new ServiceException("HTTP 数据源未配置基础地址");
        String provider = text(endpoint.get("authProvider")).toUpperCase(Locale.ROOT);
        if (provider.isBlank() || "INHERIT".equals(provider))
            provider = text(sourceConfig.get("authProvider")).toUpperCase(Locale.ROOT);
        if (provider.isBlank())
            throw new ServiceException("endpoint 必须显式配置认证 provider 或继承已登记的来源 provider");
        if (!AUTH_PROVIDERS.contains(provider)) throw new ServiceException("认证 provider 不支持");
        String encryptedSecret = !text(endpoint.get("credentialCiphertext")).isBlank()
                ? text(endpoint.get("credentialCiphertext")) : text(endpoint.get("sourceSecretCiphertext"));
        String credentialRef = text(endpoint.get("credentialRef"));
        if (credentialRef.isBlank()) credentialRef = text(sourceConfig.get("credentialRef"));
        String secret = "NO_AUTH".equals(provider) ? "" : DashboardIntegrationCredentials.resolve(credentialRef, encryptedSecret, environment);
        Map<String, String> mapping = stringMap(endpointConfig.get("headerMapping"));
        String identity = text(endpointConfig.getOrDefault("identity", sourceConfig.get("identity")));
        String keyId = text(endpointConfig.getOrDefault("keyId", endpoint.get("credentialRef")));
        String timestampUnit = text(endpointConfig.getOrDefault("timestampUnit", "SECONDS")).toUpperCase(Locale.ROOT);
        if (!Set.of("SECONDS", "MILLISECONDS", "RFC3339", "ISO8601").contains(timestampUnit))
            throw new ServiceException("签名时间戳单位不支持");
        String canonicalProfile = text(endpointConfig.getOrDefault("canonicalProfile",
                "METHOD_PATH_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1"));
        String signatureEncoding = text(endpointConfig.getOrDefault("signatureEncoding", "BASE64"))
                .toUpperCase(Locale.ROOT);
        boolean nonceRequired = booleanValue(endpointConfig.get("nonceRequired"), true);
        return new EndpointContext(toLong(endpoint.get("endpointId")), sourceCode, endpointCode, endpoint,
                sourceConfig, endpointConfig, text(endpoint.get("path")),
                text(endpoint.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT),
                text(endpoint.getOrDefault("requestContentType", "NONE")).toUpperCase(Locale.ROOT),
                text(endpoint.getOrDefault("responseType", "JSON")).toUpperCase(Locale.ROOT), provider,
                secret, identity, keyId, timestampUnit, nonceRequired, canonicalProfile,
                signatureEncoding, mapping);
    }

    private DashboardIntegrationNetwork.Target validateOutboundTarget(String target, Map<String, Object> sourceConfig)
    {
        List<String> hosts = stringList(sourceConfig.get("allowedHosts"), 32, 255);
        String profile = text(sourceConfig.getOrDefault("networkProfile", "PUBLIC_HTTPS"));
        if (hosts.isEmpty() && !"PUBLIC_HTTP".equalsIgnoreCase(profile.trim()))
            hosts = splitList(environment.getProperty("dashboard.api.allowed-hosts", ""));
        List<String> cidrs = stringList(sourceConfig.get("allowedCidrs"), 32, 64);
        List<Integer> ports = integerList(sourceConfig.get("allowedPorts"), 16);
        boolean local = booleanProperty("dashboard.integration.allow-local-development-targets", false);
        boolean allowHttp = booleanProperty("dashboard.integration.allow-plain-http", false);
        try
        {
            return DashboardIntegrationNetwork.validate(target, profile, hosts, cidrs, ports, allowHttp, local);
        }
        catch (IllegalArgumentException ex)
        {
            throw new ServiceException("来源地址未通过网络安全校验");
        }
    }

    private Map<String, Object> normalizeOutboundParams(Map<String, Object> dataset, Map<String, Object> params)
    {
        Map<String, Object> incoming = params == null ? Collections.emptyMap() : params;
        List<Map<String, Object>> declarations = parseParamSchema(dataset.get("paramSchemaJson"));
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> declaration : declarations)
        {
            String name = text(declaration.get("name"));
            if (name.isBlank() || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !names.add(name))
                throw new ServiceException("数据集参数定义不合法");
            if (declaration.containsKey("default") && declaration.get("default") != null)
                result.put(name, convertParameterValue(declaration.get("default"), text(declaration.get("type")), name));
        }
        for (String name : incoming.keySet())
        {
            if (!names.contains(name)) throw new ServiceException("未声明的数据集参数: " + name);
            if (incoming.get(name) != null)
                result.put(name, convertParameterValue(incoming.get(name), declarationType(declarations, name), name));
        }
        for (Map<String, Object> declaration : declarations)
        {
            String name = text(declaration.get("name"));
            Object value = result.get(name);
            if ((value == null || value instanceof String && ((String) value).isBlank())
                    && booleanValue(declaration.get("required"), false))
                throw new ServiceException("缺少数据集参数: " + name);
            if (value != null) validateParameterBounds(value, declaration, name);
        }
        return result;
    }

    private String declarationType(List<Map<String, Object>> declarations, String name)
    {
        for (Map<String, Object> declaration : declarations)
            if (name.equals(text(declaration.get("name")))) return text(declaration.get("type"));
        return "STRING";
    }

    private void validateParameterBounds(Object value, Map<String, Object> declaration, String name)
    {
        String valueText = scalarText(value, name);
        if (valueText.length() > 512) throw new ServiceException("数据集参数过长: " + name);
        if (declaration.get("min") != null || declaration.get("max") != null)
        {
            try
            {
                BigDecimal number = new BigDecimal(valueText);
                if (declaration.get("min") != null
                        && number.compareTo(new BigDecimal(String.valueOf(declaration.get("min")))) < 0)
                    throw new ServiceException("数据集参数超出范围: " + name);
                if (declaration.get("max") != null
                        && number.compareTo(new BigDecimal(String.valueOf(declaration.get("max")))) > 0)
                    throw new ServiceException("数据集参数超出范围: " + name);
            }
            catch (NumberFormatException ignored)
            {
                // 非数值参数的范围由来源契约表达，平台不猜测其含义。
            }
        }
        Object values = declaration.getOrDefault("enum", declaration.get("values"));
        if (values instanceof Collection<?> collection && !collection.isEmpty()
                && collection.stream().noneMatch(item -> scalarText(item, name).equals(valueText)))
            throw new ServiceException("数据集参数不在允许枚举内: " + name);
    }

    private Object convertParameterValue(Object value, String type, String name)
    {
        if (value == null) return null;
        String normalized = text(type).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) normalized = "STRING";
        String valueText = scalarText(value, name);
        try
        {
            return switch (normalized)
            {
                case "INTEGER" -> new BigDecimal(valueText).longValueExact();
                case "DECIMAL", "NUMBER" -> new BigDecimal(valueText);
                case "DATE" -> { LocalDate.parse(valueText); yield valueText; }
                case "DATETIME" -> { parseInstant(valueText, ZoneId.of("Asia/Shanghai")); yield valueText; }
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(valueText) && !"false".equalsIgnoreCase(valueText))
                        throw new IllegalArgumentException();
                    yield Boolean.parseBoolean(valueText);
                }
                case "STRING", "ENUM" -> valueText;
                default -> throw new ServiceException("参数类型不支持: " + normalized);
            };
        }
        catch (ServiceException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ServiceException("数据集参数格式不合法: " + name); }
    }

    private String resolvePath(String path, Map<String, Object> params)
    {
        Matcher matcher = PATH_PARAMETER.matcher(path);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
        {
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (!params.containsKey(name) || params.get(name) == null)
                throw new ServiceException("缺少路径参数: " + name);
            matcher.appendReplacement(result, Matcher.quoteReplacement(encode(scalarText(params.get(name), name))));
        }
        matcher.appendTail(result);
        if (result.toString().contains("{") || result.toString().contains("}"))
            throw new ServiceException("endpoint 路径包含未解析变量");
        return result.toString();
    }

    private String joinUrl(String baseUrl, String path)
    {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private String formBody(Map<String, Object> values)
    {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet())
        {
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=')
                    .append(encode(scalarText(entry.getValue(), entry.getKey())));
        }
        return result.toString();
    }

    private String encode(String value)
    {
        try
        {
            return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }
        catch (RuntimeException ex) { throw new ServiceException("请求参数编码失败"); }
    }

    private EnvelopeResult evaluateEnvelope(JsonNode root, Map<String, Object> config)
    {
        String mode = text(config.getOrDefault("envelopeMode", "HTTP_ONLY")).toUpperCase(Locale.ROOT);
        if ("HTTP_ONLY".equals(mode)) return EnvelopeResult.ok();
        String successPath = text(config.get("successPath"));
        String codePath = text(config.get("codePath"));
        boolean hasSuccess = !successPath.isBlank() && hasPath(root, successPath);
        boolean hasCode = !codePath.isBlank() && hasPath(root, codePath);
        if ("OPTIONAL".equals(mode) && !hasSuccess && !hasCode) return EnvelopeResult.ok();
        if ("JSON_BOOLEAN".equals(mode) || hasSuccess)
        {
            JsonNode value = atPathStrict(root, successPath);
            Object expected = config.get("successValue");
            if (value == null || !value.isBoolean() || !(expected instanceof Boolean))
                return EnvelopeResult.invalid("业务成功标识格式不合法", "");
            if (value.booleanValue() == ((Boolean) expected)) return EnvelopeResult.ok();
            return envelopeFailure(config, root);
        }
        if ("JSON_CODE".equals(mode) || hasCode)
        {
            JsonNode value = atPathStrict(root, codePath);
            Object codes = config.get("successCodes");
            if (value == null || !(codes instanceof Collection<?> collection))
                return EnvelopeResult.invalid("业务状态码缺失", "");
            for (Object code : collection)
            {
                if (value.equals(objectMapper.valueToTree(code))) return EnvelopeResult.ok();
            }
            return envelopeFailure(config, root);
        }
        return EnvelopeResult.invalid("业务信封配置不完整", "");
    }

    private EnvelopeResult envelopeFailure(Map<String, Object> config, JsonNode root)
    {
        String errorCode = textNode(root, text(config.get("errorCodePath")));
        if (errorCode.isBlank()) errorCode = textNode(root, text(config.get("codePath")));
        String message = limitText(textNode(root, text(config.get("messagePath"))), 200);
        if (message.isBlank()) message = "来源业务返回失败";
        boolean retryable = stringList(config.get("retryableCodes"), 32, 128).contains(errorCode);
        long retryAfterSeconds = responseRetryAfter(root, config);
        return new EnvelopeResult(false, "SOURCE_ERROR", message, errorCode, retryable,
                retryAfterSeconds);
    }

    private boolean responseQualityFlag(JsonNode root, Map<String, Object> config, String name)
    {
        String path = text(config.get(name));
        if (path.isBlank()) return false;
        JsonNode value = atPathStrict(root, path);
        if (value == null || !value.isBoolean())
            throw new OutboundFailure("INVALID_DATA", name + " 不是合法布尔值", false, 0);
        return value.booleanValue();
    }

    private long responseRetryAfter(JsonNode root, Map<String, Object> config)
    {
        String path = text(config.get("retryAfterPath"));
        if (path.isBlank()) return 0;
        JsonNode value = atPathStrict(root, path);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0)
            return 0;
        return Math.min(value.longValue(), 30L);
    }

    private ProjectionResult projectRows(List<JsonNode> sourceRows, Map<String, Object> dataset,
            Map<String, Object> responseConfig, EndpointContext context, String timezone, boolean endpointTest)
    {
        List<FieldMapping> fields = fieldMappings(dataset, responseConfig);
        if (fields.isEmpty())
        {
            if (!endpointTest) throw new OutboundFailure("INVALID_CONFIG", "数据集未声明服务端字段投影", false, 0);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : sourceRows)
            {
                if (!row.isObject()) throw new OutboundFailure("INVALID_DATA", "来源行不是对象", false, 0);
                rows.add(objectMapper.convertValue(row, new TypeReference<Map<String, Object>>() {}));
            }
            return new ProjectionResult(rows, false, Collections.emptyList());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        boolean partial = false;
        String rowErrorMode = text(responseConfig.getOrDefault("rowErrorMode", "PARTIAL")).toUpperCase(Locale.ROOT);
        for (int index = 0; index < sourceRows.size(); index++)
        {
            JsonNode source = sourceRows.get(index);
            try
            {
                if (!source.isObject()) throw new IllegalArgumentException("行不是对象");
                Map<String, Object> row = new LinkedHashMap<>();
                for (FieldMapping field : fields)
                {
                    JsonNode value = atPathStrict(source, field.sourcePath());
                    if (value == null || value.isMissingNode() || value.isNull())
                    {
                        if (field.defaultValue() != null) value = objectMapper.valueToTree(field.defaultValue());
                        else if (field.required()) throw new IllegalArgumentException("缺少必填字段");
                        else { row.put(field.name(), null); continue; }
                    }
                    Object converted = convertField(value, field, timezone);
                    row.put(field.name(), maskValue(converted, field.mask()));
                }
                if (!endpointTest) applyMediaProjections(row, source, dataset, context, responseConfig);
                rows.add(row);
            }
            catch (RuntimeException ex)
            {
                if ("REJECT".equals(rowErrorMode))
                    throw new OutboundFailure("INVALID_DATA", "来源行字段校验失败", false, 0);
                partial = true;
                if (errors.size() < 100)
                {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("index", index);
                    error.put("code", "INVALID_DATA");
                    error.put("message", limitText(ex.getMessage(), 120));
                    errors.add(error);
                }
            }
        }
        return new ProjectionResult(rows, partial, errors);
    }

    private void applyMediaProjections(Map<String, Object> projectedRow, JsonNode sourceRow,
            Map<String, Object> dataset, EndpointContext dataEndpoint, Map<String, Object> responseConfig)
    {
        Object configured = responseConfig.get("mediaProjections");
        if (!(configured instanceof Collection<?> projections) || projections.isEmpty()) return;
        String datasetCode = requireCode(dataset.get("datasetCode"), "数据集编码");
        for (Object raw : projections)
        {
            if (!(raw instanceof Map<?, ?> sourceProjection))
                throw new ServiceException("媒体引用投影配置不合法");
            Map<String, Object> projection = castMap(sourceProjection);
            String outputField = text(projection.get("outputField"));
            String mediaEndpointCode = requireCode(projection.get("endpointCode"), "媒体 endpointCode");
            String businessKeyPath = text(projection.get("businessKeyPath"));
            if (!outputField.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
                    || !ArraysSupport.allJsonPathParts(businessKeyPath))
                throw new ServiceException("媒体引用投影字段不合法");
            validateMediaEndpointReference(dataEndpoint.sourceCode(), mediaEndpointCode, true);
            Map<String, Object> mediaEndpoint = integrationMapper.selectEndpointByCode(
                    dataEndpoint.sourceCode(), mediaEndpointCode);
            Map<String, Object> mediaEndpointConfig = parseObject(
                    text(mediaEndpoint.get("configJson")), "媒体 endpoint 配置");
            Object mappingsValue = projection.get("formFieldMappings");
            if (!(mappingsValue instanceof Map<?, ?> mappings) || mappings.isEmpty())
                throw new ServiceException("媒体表单字段映射不能为空");
            Map<String, Object> values = new LinkedHashMap<>();
            boolean absent=false;
            for (Map.Entry<?, ?> entry : mappings.entrySet())
            {
                String formField = text(entry.getKey());
                String sourcePath = text(entry.getValue());
                JsonNode value = atPathStrict(sourceRow, sourcePath);
                if(value==null||value.isNull()||value.isMissingNode()||value.isTextual()&&value.asText().isBlank()) {absent=true;break;}
                if(!value.isValueNode()) throw new ServiceException("媒体参数必须是标量");
                String scalar = value.asText();
                validateMediaParameterValue(scalar);
                values.put(formField, scalar);
            }
            if(absent) {projectedRow.put(outputField,"");continue;}
            values = normalizeMediaForm(values, mediaEndpointConfig);
            JsonNode businessValue = atPathStrict(sourceRow, businessKeyPath);
            String businessKey = businessValue == null || !businessValue.isValueNode()
                    ? "" : businessValue.asText();
            if (!DashboardIntegrationSecurity.isSafeExternalValue(businessKey))
                throw new ServiceException("媒体业务主键不合法");
            Map<String, Object> policy = mediaPolicy(mediaEndpointConfig);
            int ttl = Math.max(1, Math.min(integer(policy.get("ttlSeconds"), 300), 86400));
            String candidateRef = DashboardIntegrationSecurity.randomToken(48);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("mediaRef", candidateRef);
            candidate.put("sourceCode", dataEndpoint.sourceCode());
            candidate.put("endpointCode", mediaEndpointCode);
            candidate.put("datasetCode", datasetCode);
            candidate.put("mediaProfile", text(mediaEndpointConfig.getOrDefault("mediaProfile", "IMAGE")));
            candidate.put("businessKey", businessKey);
            candidate.put("formParamsCiphertext", DashboardIntegrationCrypto.encrypt(json(values), environment));
            candidate.put("pageId", null);
            candidate.put("revisionId", null);
            candidate.put("shareId", null);
            candidate.put("expiresAt", TimestampValue.afterSeconds(ttl));
            candidate.put("status", "CANDIDATE");
            integrationMapper.insertMediaRef(candidate);
            projectedRow.put(outputField, candidateRef);
        }
    }

    private List<FieldMapping> fieldMappings(Map<String, Object> dataset, Map<String, Object> config)
    {
        List<FieldMapping> result = new ArrayList<>();
        JsonNode schema = readTreeQuiet(text(dataset.get("fieldSchemaJson")));
        if (schema != null && schema.isArray())
        {
            for (JsonNode item : schema)
            {
                if (!item.isObject()) continue;
                String name = item.path("name").asText("");
                if (name.isBlank()) continue;
                String source = item.path("sourcePath").asText(item.path("path").asText(name));
                if (source.isBlank()) source = name;
                Object defaultValue = item.has("default")
                        ? objectMapper.convertValue(item.get("default"), Object.class) : null;
                result.add(new FieldMapping(name, source, item.path("type").asText("STRING"),
                        item.path("required").asBoolean(false), defaultValue, enumValues(item),
                        item.path("mask").asText(""), item.path("scale").isInt() ? item.path("scale").asInt() : null));
            }
        }
        Object configured = config.getOrDefault("projection", config.get("fieldMappings"));
        if (result.isEmpty() && configured instanceof Map<?, ?> map)
        {
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                String name = text(entry.getKey());
                String source = text(entry.getValue());
                if (name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}") && !source.isBlank())
                    result.add(new FieldMapping(name, source, "STRING", false, null,
                            Collections.emptySet(), "", null));
            }
        }
        return result;
    }

    private Set<String> enumValues(JsonNode item)
    {
        JsonNode values = item.get("enum");
        if (values == null) values = item.get("values");
        if (values == null || !values.isArray()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) result.add(value.asText());
        return result;
    }

    private Object convertField(JsonNode value, FieldMapping field, String timezone)
    {
        String type = field.type().toUpperCase(Locale.ROOT);
        try
        {
            return switch (type)
            {
                case "STRING" -> {
                    if (!value.isValueNode()) throw new IllegalArgumentException();
                    String text = value.asText();
                    if (text.length() > MAX_TEXT) throw new IllegalArgumentException();
                    yield text;
                }
                case "INTEGER" -> value.isIntegralNumber() ? value.longValue()
                        : new BigDecimal(value.asText()).longValueExact();
                case "DECIMAL", "NUMBER" -> {
                    BigDecimal number = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
                    if (field.scale() != null) number = number.setScale(field.scale(), RoundingMode.UNNECESSARY);
                    yield number;
                }
                case "BOOLEAN" -> {
                    if (value.isBoolean()) yield value.booleanValue();
                    String text = value.asText();
                    if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text))
                        throw new IllegalArgumentException();
                    yield Boolean.parseBoolean(text);
                }
                case "DATE" -> { LocalDate date = LocalDate.parse(value.asText()); yield date.toString(); }
                case "DATETIME" -> normalizeDateTime(value.asText(), timezone);
                case "ENUM" -> {
                    String text = value.asText();
                    if (!field.enumValues().isEmpty() && !field.enumValues().contains(text))
                        throw new IllegalArgumentException();
                    yield text;
                }
                case "JSON" -> objectMapper.convertValue(value, Object.class);
                default -> throw new IllegalArgumentException();
            };
        }
        catch (ArithmeticException | DateTimeParseException | NumberFormatException ex)
        {
            throw new IllegalArgumentException("字段类型转换失败");
        }
    }

    private Object maskValue(Object value, String mask)
    {
        if (value == null || mask == null || mask.isBlank()) return value;
        String kind = mask.toUpperCase(Locale.ROOT);
        String valueText = String.valueOf(value);
        if ("PHONE".equals(kind) && valueText.length() >= 7)
            return valueText.substring(0, 3) + "****" + valueText.substring(valueText.length() - 4);
        if ("EMAIL".equals(kind))
        {
            int at = valueText.indexOf('@');
            if (at > 1) return valueText.charAt(0) + "***" + valueText.substring(at);
        }
        if ("PARTIAL".equals(kind) && valueText.length() > 2)
            return valueText.charAt(0) + "***" + valueText.charAt(valueText.length() - 1);
        return value;
    }

    private List<JsonNode> toRowNodes(JsonNode node)
    {
        if (node.isArray())
        {
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode item : node) result.add(item);
            return result;
        }
        if (node.isObject()) return List.of(node);
        throw new OutboundFailure("INVALID_DATA", "数据路径必须是对象或数组", false, 0);
    }

    private JsonNode atPathStrict(JsonNode root, String path)
    {
        if (path == null || path.isBlank()) return root;
        JsonNode current = root;
        for (String rawPart : path.split("\\."))
        {
            if (!JSON_PATH_PART.matcher(rawPart).matches()) return null;
            Matcher matcher = Pattern.compile("([A-Za-z0-9_-]+)(?:\\[([0-9]+)\\])?").matcher(rawPart);
            if (!matcher.matches() || current == null || !current.isObject()) return null;
            current = current.get(matcher.group(1));
            if (current == null) return null;
            if (matcher.group(2) != null)
            {
                if (!current.isArray()) return null;
                int index;
                try { index = Integer.parseInt(matcher.group(2)); } catch (NumberFormatException ex) { return null; }
                current = index < current.size() ? current.get(index) : null;
            }
        }
        return current;
    }

    private boolean hasPath(JsonNode root, String path)
    {
        JsonNode value = atPathStrict(root, path);
        return value != null && !value.isNull() && !value.isMissingNode();
    }

    private void validateJsonShape(JsonNode node, int depth, int[] fields)
    {
        if (node == null) throw new IllegalArgumentException("空 JSON");
        if (depth > MAX_JSON_DEPTH) throw new IllegalArgumentException("JSON 嵌套过深");
        if (node.isObject())
        {
            fields[0] += node.size();
            if (fields[0] > MAX_JSON_FIELDS) throw new IllegalArgumentException("JSON 字段过多");
            node.fields().forEachRemaining(entry -> validateJsonShape(entry.getValue(), depth + 1, fields));
        }
        else if (node.isArray())
        {
            if (node.size() > MAX_ROWS * 5) throw new IllegalArgumentException("JSON 数组过大");
            for (JsonNode child : node) validateJsonShape(child, depth + 1, fields);
        }
        else if (node.isTextual() && node.textValue().length() > MAX_TEXT)
            throw new IllegalArgumentException("JSON 文本过长");
        else if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue()))
            throw new IllegalArgumentException("JSON 数值不合法");
    }

    private void updateEndpointHealth(Long endpointId, Map<String, Object> result)
    {
        if (endpointId == null) return;
        Map<String, Object> health = new HashMap<>();
        health.put("endpointId", endpointId);
        health.put("lastRequestId", limitText(text(result.get("requestId")), 64));
        health.put("quality", text(result.get("quality")));
        boolean success = QUALITY_SUCCESS.contains(text(result.get("quality")));
        health.put("success", success);
        health.put("errorCode", success ? null : text(result.get("quality")));
        integrationMapper.updateEndpointHealth(health);
    }

    private void sleepBackoff(Map<String, Object> config, int attempt, long retryAfterSeconds)
    {
        long configured = Math.max(0, Math.min(integer(config.get("backoffMs"), 100), 10000));
        long exponential = Math.min(10000, configured * (1L << Math.min(attempt - 1, 6)));
        long retry = Math.min(30000, Math.max(0, retryAfterSeconds * 1000));
        long jitter = exponential == 0 ? 0
                : java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, exponential / 4 + 1));
        try { Thread.sleep(Math.min(30000, Math.max(exponential + jitter, retry))); }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("来源请求被中断");
        }
    }

    private long retryAfter(HttpResponse<?> response)
    {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.max(0, Math.min(Long.parseLong(value), 30)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException
    {
        int limit = Math.max(1, Math.min(maxBytes, MAX_RESPONSE_BYTES));
        byte[] buffer = new byte[8192];
        int total = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        int read;
        while ((read = input.read(buffer)) >= 0)
        {
            if (read == 0) continue;
            if (total > limit - read)
                throw new OutboundFailure("INVALID_DATA", "来源响应超过字节上限", false, 0);
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private boolean isJsonContentType(String contentType)
    {
        String value = text(contentType).toLowerCase(Locale.ROOT);
        return value.isBlank() || value.startsWith("application/json") || value.contains("+json");
    }

    private String safeContentType(HttpResponse<?> response)
    {
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("application/octet-stream");
        int separator = contentType.indexOf(';');
        return (separator >= 0 ? contentType.substring(0, separator) : contentType)
                .trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> errorResult(String datasetCode, String quality, String message)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetCode", datasetCode);
        result.put("rows", new ArrayList<>());
        result.put("rowCount", 0);
        result.put("quality", quality);
        result.put("message", limitText(message, 200));
        result.put("stale", false);
        return result;
    }

    private Map<String, Object> errorWithSource(String datasetCode, String quality, String message,
            String sourceCode, String endpointCode)
    {
        Map<String, Object> result = errorResult(datasetCode, quality, message);
        result.put("sourceCode", sourceCode);
        result.put("endpointCode", endpointCode);
        result.put("sourceStatus", "NOT_CONNECTED");
        result.put("requestId", UUID.randomUUID().toString());
        return result;
    }

    private Map<String, Object> copyResult(Map<String, Object> source)
    {
        return objectMapper.convertValue(source, new TypeReference<Map<String, Object>>() {});
    }

    private String classifyOutboundException(Throwable ex)
    {
        Throwable cause = rootCause(ex);
        if (cause instanceof OutboundFailure failure) return failure.quality();
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
        if (cause instanceof ConnectException) return "NOT_CONNECTED";
        if (cause instanceof ServiceException) return "INVALID_CONFIG";
        return "SOURCE_ERROR";
    }

    private String safeMessage(Throwable ex)
    {
        Throwable cause = rootCause(ex);
        if (cause instanceof OutboundFailure failure) return limitText(failure.getMessage(), 200);
        if (cause instanceof java.sql.SQLException) return "数据源执行失败";
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "来源请求失败" : limitText(message, 200);
    }

    private Throwable rootCause(Throwable value)
    {
        Throwable current = value;
        while ((current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private record EndpointContext(Long endpointId, String sourceCode, String endpointCode,
            Map<String, Object> endpoint, Map<String, Object> sourceConfig,
            Map<String, Object> endpointConfig, String path, String method,
            String requestContentType, String responseType, String authProvider,
            String secret, String identity, String keyId, String timestampUnit,
            boolean nonceRequired, String canonicalProfile, String signatureEncoding,
            Map<String, String> headerMapping) { }

    private record FieldMapping(String name, String sourcePath, String type, boolean required,
            Object defaultValue, Set<String> enumValues, String mask, Integer scale) { }

    private record ProjectionResult(List<Map<String, Object>> rows, boolean partial,
            List<Map<String, Object>> errors) { }

    private record EnvelopeResult(boolean success, String quality, String message,
            String errorCode, boolean retryable, long retryAfterSeconds)
    {
        static EnvelopeResult ok() { return new EnvelopeResult(true, "SUCCESS", "", "", false, 0); }
        static EnvelopeResult invalid(String message, String code)
        {
            return new EnvelopeResult(false, "INVALID_DATA", message, code, false, 0);
        }
    }

    private static final class OutboundFailure extends RuntimeException
    {
        private final String quality;
        private final boolean retryable;
        private final long retryAfterSeconds;
        private final String sourceErrorCode;

        private OutboundFailure(String quality, String message, boolean retryable, long retryAfterSeconds)
        {
            this(quality, message, retryable, retryAfterSeconds, "");
        }

        private OutboundFailure(String quality, String message, boolean retryable,
                long retryAfterSeconds, String sourceErrorCode)
        {
            super(message);
            this.quality = quality;
            this.retryable = retryable;
            this.retryAfterSeconds = retryAfterSeconds;
            this.sourceErrorCode = sourceErrorCode == null ? "" : sourceErrorCode;
        }

        String quality() { return quality; }
        boolean retryable() { return retryable; }
        long retryAfterSeconds() { return retryAfterSeconds; }
        String sourceErrorCode() { return sourceErrorCode; }
    }


    /* ----------------------------- 入站接入方管理 ----------------------------- */

    public long integrationVersion(String code)
    {
        Map<String,Object> integration=integrationMapper.selectIntegrationByCode(code);
        if(integration==null) return -1;
        return operations.version(toLong(integration.get("integrationId")));
    }

    public List<Map<String, Object>> listIntegrations(String keyword, String environmentName, String status)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : integrationMapper.selectIntegrationList(
                limitText(text(keyword), 80), text(environmentName).toUpperCase(Locale.ROOT),
                text(status).toUpperCase(Locale.ROOT)))
            result.add(safeIntegration(item));
        return result;
    }

    public List<Map<String, Object>> listIntegrations(String keyword, String environmentName, String status,
            Long folderId, boolean includeChildren)
    {
        List<Long> folderIds = dataFolders.filterIds("integration", folderId, includeChildren);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : integrationMapper.selectIntegrationListByFolders(
                limitText(text(keyword), 80), text(environmentName).toUpperCase(Locale.ROOT),
                text(status).toUpperCase(Locale.ROOT), folderIds))
            result.add(safeIntegration(item));
        return result;
    }

    public List<Map<String, Object>> listIntegrationsPage(String keyword, String environmentName, String status,
            Long folderId, boolean includeChildren, Integer pageNum, Integer pageSize)
    {
        List<Long> folderIds = dataFolders.filterIds("integration", folderId, includeChildren);
        startManagementPage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = integrationMapper.selectIntegrationListByFolders(
                    limitText(text(keyword), 80), text(environmentName).toUpperCase(Locale.ROOT),
                    text(status).toUpperCase(Locale.ROOT), folderIds);
            rows.replaceAll(this::safeIntegration);
            return rows;
        }
        finally { PageHelper.clearPage(); }
    }

    private void startManagementPage(Integer pageNum, Integer pageSize)
    {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 1000)
            throw new ServiceException("分页参数不合法，页码至少为 1，每页条数须在 1-1000 之间");
        PageHelper.startPage(pageNum, pageSize).setReasonable(false);
    }

    public Map<String, Object> getIntegration(Long integrationId)
    {
        Map<String, Object> item = integrationMapper.selectIntegrationById(integrationId);
        if (item == null) throw new ServiceException("接入方不存在");
        Map<String, Object> result = safeIntegration(item);
        List<Map<String, Object>> keys = integrationMapper.selectIntegrationKeys(integrationId);
        result.put("keyCount", keys.size());
        result.put("activeKeyCount", keys.stream().filter(key -> "ACTIVE".equals(text(key.get("status")))).count());
        return result;
    }

    public Map<String, Object> getIntegrationByCode(String integrationCode)
    {
        Map<String, Object> item = integrationMapper.selectIntegrationByCode(requireCode(integrationCode, "integrationCode"));
        if (item == null) throw new ServiceException("接入方不存在");
        return safeIntegration(item);
    }

    @Transactional
    public Map<String, Object> createIntegration(Map<String, Object> body, String username)
    {
        Map<String, Object> integration = normalizeIntegration(body, username, false);
        String code = text(integration.get("integrationCode"));
        if (integrationMapper.selectIntegrationByCode(code) != null)
            throw new ServiceException("integrationCode 已存在");
        if ("ACTIVE".equals(integration.get("status")))
            throw new ServiceException("接入方必须先登记并启用密钥后才能启用");
        try
        {
            integrationMapper.insertIntegration(integration);
        }
        catch (DuplicateKeyException ex)
        {
            throw new ServiceException("integrationCode 已存在");
        }
        return getIntegration(toLong(integration.get("integrationId")));
    }

    @Transactional
    public Map<String, Object> updateIntegration(Map<String, Object> body, String username)
    {
        Long id = toLong(body == null ? null : body.get("integrationId"));
        Map<String, Object> existing = id == null ? null : integrationMapper.selectIntegrationById(id);
        if (existing == null) throw new ServiceException("接入方不存在");
        if (body.containsKey("integrationCode")
                && !text(body.get("integrationCode")).equalsIgnoreCase(text(existing.get("integrationCode"))))
            throw new ServiceException("integrationCode 创建后不可修改");
        Map<String, Object> integration = normalizeIntegration(body, username, true);
        if (integration.containsKey("status"))
            requireIntegrationStatusPermission(text(existing.get("status")), text(integration.get("status")));
        if ("ACTIVE".equals(integration.get("status"))
                && integrationMapper.selectIntegrationKeys(id).stream()
                    .noneMatch(key -> "ACTIVE".equals(text(key.get("status")))))
            throw new ServiceException("接入方至少需要一个启用密钥");
        integration.put("integrationId", id);
        integrationMapper.updateIntegration(integration);
        if (body.containsKey("concurrencyLimit")) resetInboundLimiter(id);
        return getIntegration(id);
    }

    @Transactional
    public int deleteIntegration(Long integrationId)
    {
        Map<String, Object> existing = integrationMapper.selectIntegrationById(integrationId);
        if (existing == null) throw new ServiceException("接入方不存在");
        if (!Set.of("DRAFT", "REVOKED").contains(text(existing.get("status"))))
            throw new ServiceException("只有草稿或已吊销接入方可以删除");
        return integrationMapper.deleteIntegration(integrationId);
    }

    @Transactional
    public Map<String, Object> updateIntegrationStatus(Long integrationId, String status, String username)
    {
        Map<String, Object> existing = integrationMapper.selectIntegrationById(integrationId);
        if (existing == null) throw new ServiceException("接入方不存在");
        String normalized = text(status).toUpperCase(Locale.ROOT);
        if (!INTEGRATION_STATUSES.contains(normalized)) throw new ServiceException("接入方状态不合法");
        requireIntegrationStatusPermission(text(existing.get("status")), normalized);
        if ("ACTIVE".equals(normalized)
                && integrationMapper.selectIntegrationKeys(integrationId).stream()
                    .noneMatch(key -> "ACTIVE".equals(text(key.get("status")))))
            throw new ServiceException("接入方至少需要一个启用密钥");
        integrationMapper.updateIntegrationStatus(integrationId, normalized, username);
        return getIntegration(integrationId);
    }

    private void requireIntegrationStatusPermission(String currentStatus, String targetStatus)
    {
        if (currentStatus.equals(targetStatus)) return;
        String action = switch (targetStatus)
        {
            case "PAUSED" -> "pause";
            case "REVOKED" -> "revoke";
            default -> "edit";
        };
        if (!SecurityUtils.hasPermi("dashboard:integration:" + action))
            throw new AccessDeniedException("没有修改接入方目标状态的权限");
    }

    public List<Map<String, Object>> listIntegrationKeys(Long integrationId)
    {
        requireIntegration(integrationId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> key : integrationMapper.selectIntegrationKeys(integrationId))
            result.add(safeIntegrationKey(key));
        return result;
    }

    @Transactional
    public Map<String, Object> createIntegrationKey(Long integrationId, Map<String, Object> body, String username)
    {
        requireIntegration(integrationId);
        if (body == null) throw new ServiceException("密钥配置不能为空");
        String keyId = requireExternal(body.get("keyId"), "keyId");
        String provider = text(body.getOrDefault("provider", "HMAC_V1")).toUpperCase(Locale.ROOT);
        if (!INBOUND_PROVIDERS.contains(provider)) throw new ServiceException("入站认证方式不支持");
        String secret = text(body.get("secret"), false);
        if (secret.isBlank() || secret.length() > 512) throw new ServiceException("密钥不能为空且不能超过 512 个字符");
        String identity = text(body.get("identityValue"));
        if ("HMAC_V1".equals(provider) && (identity.isBlank() || !DashboardIntegrationSecurity.isSafeExternalValue(identity)))
            throw new ServiceException("HMAC 身份标识不合法");
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("integrationId", integrationId);
        key.put("keyId", keyId);
        key.put("provider", provider);
        key.put("identityValue", identity);
        key.put("credentialRef", safeOptionalExternal(body.get("credentialRef")));
        key.put("secretCiphertext", DashboardIntegrationCrypto.encrypt(secret, environment));
        java.sql.Timestamp validFrom = parseOptionalTimestamp(body.get("validFrom"));
        java.sql.Timestamp validTo = parseOptionalTimestamp(body.get("validTo"));
        if (validFrom != null && validTo != null && !validTo.after(validFrom))
            throw new ServiceException("密钥失效时间必须晚于生效时间");
        key.put("validFrom", validFrom);
        key.put("validTo", validTo);
        key.put("status", text(body.getOrDefault("status", "ACTIVE")).toUpperCase(Locale.ROOT));
        if (!Set.of("ACTIVE", "REVOKED").contains(text(key.get("status"))))
            throw new ServiceException("密钥状态不合法");
        key.put("createBy", username);
        key.put("remark", limitText(text(body.get("remark")), 500));
        try
        {
            integrationMapper.insertIntegrationKey(key);
        }
        catch (DuplicateKeyException ex)
        {
            throw new ServiceException("keyId 已存在");
        }
        Map<String, Object> result = safeIntegrationKey(key);
        result.put("secret", secret);
        result.put("secretNotice", "有密钥管理权限的用户可在管理窗口再次查看明文");
        return result;
    }

    @Transactional
    public int revokeIntegrationKey(Long integrationKeyId, String username)
    {
        Map<String, Object> key = integrationMapper.selectIntegrationKeyById(integrationKeyId);
        if (key == null) throw new ServiceException("密钥不存在");
        return integrationMapper.revokeIntegrationKey(integrationKeyId, username);
    }

    private Map<String, Object> normalizeIntegration(Map<String, Object> body, String username, boolean partial)
    {
        if (body == null) throw new ServiceException("接入方配置不能为空");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!partial || body.containsKey("folderId"))
            result.put("folderId", body.containsKey("folderId") ? dataFolders.validateTarget("integration", body.get("folderId")) : 0L);
        if (!partial || body.containsKey("integrationCode"))
            result.put("integrationCode", requireCode(body.get("integrationCode"), "integrationCode"));
        if (!partial || body.containsKey("integrationName"))
        {
            String name = text(body.get("integrationName"));
            if (name.isBlank() || name.length() > 100) throw new ServiceException("接入方名称不能为空且不能超过 100 个字符");
            result.put("integrationName", name);
        }
        if (!partial || body.containsKey("environment"))
        {
            String value = text(body.getOrDefault("environment", "TEST")).toUpperCase(Locale.ROOT);
            if (!Set.of("SANDBOX", "TEST", "PRODUCTION").contains(value))
                throw new ServiceException("接入环境不支持");
            result.put("environment", value);
        }
        if (!partial || body.containsKey("profile"))
        {
            String value = text(body.getOrDefault("profile", "EVENT")).toUpperCase(Locale.ROOT);
            if (!PROFILES.contains(value)) throw new ServiceException("消息 profile 不支持");
            result.put("profile", value);
        }
        if (!partial || body.containsKey("schemaVersion"))
        {
            String value = text(body.getOrDefault("schemaVersion", "1.0"));
            if (!DashboardIntegrationSecurity.isSafeExternalValue(value) || value.length() > 64)
                throw new ServiceException("schemaVersion 不合法");
            result.put("schemaVersion", value);
        }
        if (!partial || body.containsKey("endpointCode"))
        {
            String value = text(body.getOrDefault("endpointCode", "events"));
            if (!SAFE_CODE.matcher(value).matches()) throw new ServiceException("入站 endpointCode 不合法");
            result.put("endpointCode", value);
        }
        if (!partial || body.containsKey("projectScopeJson") || body.containsKey("projectScope"))
        {
            Object value = body.containsKey("projectScope") ? body.get("projectScope") : body.get("projectScopeJson");
            result.put("projectScopeJson", normalizeStringArrayJson(value, "项目范围", 100, 128));
        }
        if (!partial || body.containsKey("configJson"))
        {
            Map<String, Object> config = parseObject(jsonString(body.get("configJson"), "{}"), "接入方配置");
            validateIntegrationConfig(config);
            result.put("configJson", json(config));
        }
        if (!partial || body.containsKey("networkProfile"))
        {
            String value = text(body.getOrDefault("networkProfile", "PUBLIC_HTTPS")).toUpperCase(Locale.ROOT);
            if (!Set.of("PUBLIC_HTTPS", "PRIVATE_LINK").contains(value))
                throw new ServiceException("入站网络策略不支持");
            result.put("networkProfile", value);
        }
        if (!partial || body.containsKey("allowedIpJson") || body.containsKey("allowedIps"))
        {
            Object value = body.containsKey("allowedIps") ? body.get("allowedIps") : body.get("allowedIpJson");
            result.put("allowedIpJson", normalizeStringArrayJson(value, "IP 白名单", 100, 64));
        }
        if (!partial || body.containsKey("timezone"))
        {
            String value = text(body.getOrDefault("timezone", "Asia/Shanghai"));
            try { ZoneId.of(value); } catch (RuntimeException ex) { throw new ServiceException("时区不合法"); }
            result.put("timezone", value);
        }
        if (!partial || body.containsKey("maxBatchItems"))
            result.put("maxBatchItems", boundedInt(body.get("maxBatchItems"), 1, MAX_BATCH_ITEMS, 500, "单批条数"));
        if (!partial || body.containsKey("maxBodyBytes"))
            result.put("maxBodyBytes", boundedInt(body.get("maxBodyBytes"), 1024, MAX_INBOUND_BODY, 1024 * 1024, "请求体上限"));
        if (!partial || body.containsKey("rateLimit"))
            result.put("rateLimit", boundedInt(body.get("rateLimit"), 1, 1000, 10, "限流值"));
        if (!partial || body.containsKey("concurrencyLimit"))
            result.put("concurrencyLimit", boundedInt(body.get("concurrencyLimit"), 1, 200, 20, "并发上限"));
        if (!partial || body.containsKey("rawRetentionDays"))
            result.put("rawRetentionDays", boundedInt(body.get("rawRetentionDays"), 0, 3650, 30, "原始数据保留天数"));
        if (!partial || body.containsKey("status"))
        {
            String value = text(body.getOrDefault("status", "DRAFT")).toUpperCase(Locale.ROOT);
            if (!INTEGRATION_STATUSES.contains(value)) throw new ServiceException("接入方状态不合法");
            result.put("status", value);
        }
        if (!partial || body.containsKey("remark"))
            result.put("remark", limitText(text(body.get("remark")), 500));
        if (!partial) result.put("createBy", username);
        result.put("updateBy", username);
        return result;
    }

    private void validateIntegrationConfig(Map<String, Object> config)
    {
        Set<String> allowed = Set.of("timestampUnit", "clockSkewSeconds", "nonceRequired",
                "idempotencyRequired", "allowDerivedIdempotency", "requireSchemaHeader",
                "processingMode", "itemErrorMode", "maxAttempts", "deadLetterAfter",
                "retainRawBody", "canonicalProfile", "signatureEncoding", "apiKeyHeader",
                "allowedOrigins", "unknownFieldMode", "businessKeyField", "allowSingleItem");
        allowed = new LinkedHashSet<>(allowed);
        allowed.addAll(Set.of("previousSchemaVersion", "messageIdRequired", "businessTimeRequired",
                "projectTenantMustMatch", "sourceCode", "successRetentionDays", "recordRetentionDays"));
        for (String key : config.keySet())
            if (!allowed.contains(key)) throw new ServiceException("接入方配置属性不支持: " + key);
        String unit = text(config.getOrDefault("timestampUnit", "SECONDS")).toUpperCase(Locale.ROOT);
        if (!Set.of("SECONDS", "MILLISECONDS", "RFC3339", "ISO8601").contains(unit))
            throw new ServiceException("时间戳单位不支持");
        validateBoundedInt(config, "clockSkewSeconds", 1, 3600, 300);
        validateBoundedInt(config, "maxAttempts", 1, 5, 3);
        validateBoundedInt(config, "deadLetterAfter", 1, 5, 5);
        DashboardIntegrationRetentionService.days(config, "successRetentionDays");
        DashboardIntegrationRetentionService.days(config, "recordRetentionDays");
        String processing = text(config.getOrDefault("processingMode", "ASYNC")).toUpperCase(Locale.ROOT);
        if (!"ASYNC".equals(processing)) throw new ServiceException("当前入站处理模式只支持 ASYNC");
        String itemMode = text(config.getOrDefault("itemErrorMode", "PARTIAL")).toUpperCase(Locale.ROOT);
        if (!Set.of("PARTIAL", "REJECT").contains(itemMode)) throw new ServiceException("逐项错误策略不支持");
        String canonical = text(config.getOrDefault("canonicalProfile",
                "METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1"));
        if (!Set.of("METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1",
                "METHOD_PATH_TIMESTAMP_NONCE_BODY_V1").contains(canonical))
            throw new ServiceException("入站签名原文 profile 不支持");
        String encoding = text(config.getOrDefault("signatureEncoding", "BASE64")).toUpperCase(Locale.ROOT);
        if (!Set.of("BASE64", "HEX").contains(encoding)) throw new ServiceException("签名编码不支持");
        String header = text(config.getOrDefault("apiKeyHeader", "X-Api-Key"));
        if (!SAFE_HEADER.matcher(header).matches() || isForbiddenHeader(header))
            throw new ServiceException("API Key 请求头不支持");
        validateStringArray(config.get("allowedOrigins"), "allowedOrigins", 32, 255);
        String unknown = text(config.getOrDefault("unknownFieldMode", "REJECT")).toUpperCase(Locale.ROOT);
        if (!Set.of("REJECT", "IGNORE").contains(unknown)) throw new ServiceException("未知字段策略不支持");
        String keyField = text(config.getOrDefault("businessKeyField", ""));
        if (!keyField.isBlank() && !keyField.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new ServiceException("业务主键字段不合法");
        for (String key : List.of("previousSchemaVersion", "sourceCode"))
        {
            String value = text(config.get(key));
            if (!value.isBlank() && !DashboardIntegrationSecurity.isSafeExternalValue(value))
                throw new ServiceException(key + " 不合法");
        }
        for (String key : List.of("nonceRequired", "idempotencyRequired", "allowDerivedIdempotency",
                "requireSchemaHeader", "retainRawBody", "allowSingleItem", "messageIdRequired",
                "businessTimeRequired", "projectTenantMustMatch"))
        {
            if (config.containsKey(key) && !(config.get(key) instanceof Boolean))
                throw new ServiceException(key + " 必须是布尔值");
        }
    }

    private Map<String, Object> safeIntegration(Map<String, Object> item)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("integrationId", "integrationCode", "integrationName", "environment", "folderId", "folderName",
                "profile", "schemaVersion", "endpointCode", "networkProfile", "timezone", "maxBatchItems",
                "maxBodyBytes", "rateLimit", "concurrencyLimit", "rawRetentionDays", "status", "createBy",
                "createTime", "updateBy", "updateTime", "remark"))
            if (item.containsKey(key)) result.put(key, item.get(key));
        result.put("projectScope", parseArrayQuiet(text(item.get("projectScopeJson"))));
        result.put("allowedIps", parseArrayQuiet(text(item.get("allowedIpJson"))));
        result.put("configJson", safeIntegrationConfig(item.get("configJson")));
        result.put("hasAllowedIp", !parseArrayQuiet(text(item.get("allowedIpJson"))).isEmpty());
        if (item.containsKey("keyCount")) result.put("keyCount", item.get("keyCount"));
        if (item.containsKey("activeKeyCount")) result.put("activeKeyCount", item.get("activeKeyCount"));
        return result;
    }

    private String safeIntegrationConfig(Object raw)
    {
        Map<String, Object> config = parseObjectQuiet(text(raw));
        Set<String> allowed = new LinkedHashSet<>(Set.of("timestampUnit", "clockSkewSeconds", "nonceRequired",
                "idempotencyRequired", "allowDerivedIdempotency", "requireSchemaHeader", "processingMode",
                "itemErrorMode", "maxAttempts", "deadLetterAfter", "retainRawBody", "canonicalProfile",
                "signatureEncoding", "apiKeyHeader", "allowedOrigins", "unknownFieldMode", "businessKeyField",
                "allowSingleItem", "previousSchemaVersion", "messageIdRequired", "businessTimeRequired",
                "projectTenantMustMatch", "sourceCode", "successRetentionDays", "recordRetentionDays"));
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : allowed) if (config.containsKey(key)) safe.put(key, config.get(key));
        return json(safe);
    }

    private Map<String, Object> safeIntegrationKey(Map<String, Object> key)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("integrationKeyId", "integrationId", "keyId", "provider",
                "identityValue", "credentialRef", "validFrom", "validTo", "status", "lastUsedAt",
                "createBy", "createTime", "revokeBy", "revokeTime", "remark"))
            if (key.containsKey(name)) result.put(name, key.get(name));
        result.put("hasSecret", !text(key.get("secretCiphertext")).isBlank());
        return result;
    }

    private Map<String, Object> requireIntegration(Long integrationId)
    {
        if (integrationId == null) throw new ServiceException("接入方编号不能为空");
        Map<String, Object> item = integrationMapper.selectIntegrationById(integrationId);
        if (item == null) throw new ServiceException("接入方不存在");
        return item;
    }

    /* ----------------------------- 入站接收 ----------------------------- */

    /**
     * 入站控制器传入已经按全局上限读取的原始 JSON 字节。方法返回稳定的 HTTP
     * 状态和回执，调用方不需要解析异常文本来决定是否重试。
     */
    @Transactional
    public InboundResponse receiveInbound(String integrationCode, String endpointCode,
            String method, String requestTarget, String contentType, String remoteAddress,
            Map<String, String> headers, byte[] rawBody)
    {
        String platformRequestId = UUID.randomUUID().toString();
        Semaphore inboundPermit = null;
        boolean permitAcquired = false;
        String sharedPermit = null;
        try
        {
            Map<String, Object> integration = loadActiveIntegration(integrationCode);
            String expectedEndpoint = text(integration.get("endpointCode"));
            if (!expectedEndpoint.equals(text(endpointCode)))
                throw new InboundReject(404, "ENDPOINT_NOT_FOUND", "入站接口不存在", 0);
            validateInboundRequest(integration, method, contentType, remoteAddress, headers, rawBody);
            sharedPermit=operations.acquirePermit("in:"+integrationCode,integer(integration.get("concurrencyLimit"),20),60);
            if(sharedPermit==null) throw new InboundReject(429,"CONCURRENCY_LIMITED","接入方并发达到上限",1);
            inboundPermit = inboundSemaphore(integration);
            if (!inboundPermit.tryAcquire())
                throw new InboundReject(429, "CONCURRENCY_LIMITED", "接入方并发请求达到上限", 1);
            permitAcquired = true;
            AuthContext auth = authenticateInbound(integration, method, requestTarget,
                    headers, rawBody);
            JsonNode envelope = parseInboundBody(rawBody);
            InboundEnvelope parsed = validateInboundEnvelope(integration, envelope, headers);
            validateRetryReference(integration, parsed.retryOf());
            String idempotencyKey = normalizeIdempotencyKey(headers.get("idempotency-key"),
                    parsed.messageId(), integration);
            String bodyHash = DashboardIntegrationSecurity.sha256(rawBody);
            Map<String, Object> existing = integrationMapper.selectBatchByIdempotency(
                    toLong(integration.get("integrationId")), expectedEndpoint, idempotencyKey);
            if (existing != null)
            {
                if (!bodyHash.equals(text(existing.get("bodyHash"))))
                    throw new InboundReject(409, "IDEMPOTENCY_CONFLICT", "幂等标识对应的报文摘要不一致", 0);
                return replayStoredResponse(existing, platformRequestId);
            }

            List<ValidatedItem> items = validateItems(integration, parsed.items(),
                    bodyHash, parsed.projectCode());
            String itemMode = integrationConfig(integration, "itemErrorMode", "PARTIAL").toUpperCase(Locale.ROOT);
            if ("REJECT".equals(itemMode) && items.stream().anyMatch(item -> !item.valid()))
                throw new InboundReject(422, "ITEM_INVALID", "批次包含不符合契约的记录", 0);

            Map<String, Object> batch = new LinkedHashMap<>();
            Long integrationId = toLong(integration.get("integrationId"));
            batch.put("integrationId", integrationId);
            batch.put("endpointCode", expectedEndpoint);
            batch.put("normalizedIdempotencyKey", idempotencyKey);
            batch.put("messageId", nullableExternal(parsed.messageId()));
            batch.put("externalRequestId", nullableExternal(parsed.externalRequestId()));
            batch.put("bodyHash", bodyHash);
            batch.put("platformRequestId", platformRequestId);
            batch.put("schemaVersion", parsed.schemaVersion());
            batch.put("status", "RECEIVED");
            batch.put("acceptedCount", 0);
            batch.put("processingCount", 0);
            batch.put("duplicateCount", 0);
            batch.put("rejectedCount", 0);
            batch.put("retryableCount", 0);
            batch.put("rawBodyCiphertext", retainedRawBody(integration, rawBody));
            batch.put("expiresAt", TimestampValue.afterDays(
                    integer(integration.get("rawRetentionDays"), 30)));
            try
            {
                integrationMapper.insertBatch(batch);
            }
            catch (DuplicateKeyException race)
            {
                Map<String, Object> raced = integrationMapper.selectBatchByIdempotencyCurrent(
                        integrationId, expectedEndpoint, idempotencyKey);
                if (raced != null && bodyHash.equals(text(raced.get("bodyHash"))))
                    return replayStoredResponse(raced, platformRequestId);
                throw new InboundReject(409, "IDEMPOTENCY_CONFLICT", "幂等标识已被其他报文占用", 0);
            }
            Long batchId = toLong(batch.get("batchId"));

            List<Map<String, Object>> itemResults = new ArrayList<>();
            int accepted = 0;
            int processing = 0;
            int duplicates = 0;
            int rejected = 0;
            int conflicts = 0;
            int index = 0;
            for (ValidatedItem item : items)
            {
                Map<String, Object> itemResult = new LinkedHashMap<>();
                itemResult.put("index", index);
                itemResult.put("businessKey", item.businessKey());
                if (!item.valid())
                {
                    String syntheticKey = "__rejected__" + index + "_" + bodyHash.substring(0, 16);
                    Map<String, Object> message = messageRow(batchId, integration, parsed, item,
                            syntheticKey, index, "REJECTED", item.errorCode(), item.errorMessage());
                    integrationMapper.insertMessage(message);
                    itemResult.put("status", "REJECTED");
                    itemResult.put("code", item.errorCode());
                    rejected++;
                }
                else
                {
                    Map<String, Object> duplicateMessage = integrationMapper.selectMessageByBusinessKey(
                            integrationId, parsed.profile(), item.businessKey());
                    if (duplicateMessage != null)
                    {
                        String incomingHash = itemPayloadHash(item.item());
                        if (incomingHash.equals(text(duplicateMessage.get("payloadHash"))))
                        {
                            itemResult.put("status", "DUPLICATE");
                            itemResult.put("code", "DUPLICATE");
                            duplicates++;
                        }
                        else
                        {
                            itemResult.put("status", "CONFLICT");
                            itemResult.put("code", "BUSINESS_KEY_CONFLICT");
                            rejected++;
                            conflicts++;
                        }
                    }
                    else
                    {
                        Map<String, Object> message = messageRow(batchId, integration, parsed, item,
                                item.businessKey(), index, "RECEIVED", "", "");
                        try
                        {
                            integrationMapper.insertMessage(message);
                            itemResult.put("status", "PROCESSING");
                            accepted++;
                            processing++;
                        }
                        catch (DuplicateKeyException duplicateRace)
                        {
                            Map<String, Object> racedMessage = integrationMapper.selectMessageByBusinessKeyCurrent(
                                    integrationId, parsed.profile(), item.businessKey());
                            String incomingHash = itemPayloadHash(item.item());
                            if (racedMessage != null && incomingHash.equals(text(racedMessage.get("payloadHash"))))
                            {
                                itemResult.put("status", "DUPLICATE");
                                itemResult.put("code", "DUPLICATE");
                                duplicates++;
                            }
                            else
                            {
                                itemResult.put("status", "CONFLICT");
                                itemResult.put("code", "BUSINESS_KEY_CONFLICT");
                                rejected++;
                                conflicts++;
                            }
                        }
                    }
                }
                itemResults.add(itemResult);
                index++;
            }
            String batchStatus = processing > 0 ? "PROCESSING"
                    : conflicts > 0 && conflicts == rejected && accepted == 0 && duplicates == 0 ? "CONFLICT"
                    : rejected > 0 && accepted == 0 && duplicates == 0 ? "FAILED"
                    : rejected > 0 ? "PARTIAL" : "ACCEPTED";
            Map<String, Object> receipt = receipt(platformRequestId, parsed.messageId(),
                    text(integration.get("integrationCode")), accepted, processing, duplicates, rejected, 0,
                    itemResults, batchStatus);
            batchUpdate(batchId, batchStatus, accepted, processing, duplicates, rejected, 0,
                    json(receipt), "", "");
            if (processing > 0)
            {
                registerAfterCommit(() -> scheduleProcess(batchId));
                return new InboundResponse(202, receipt, 0);
            }
            if ("CONFLICT".equals(batchStatus)) return new InboundResponse(409, receipt, 0);
            if ("FAILED".equals(batchStatus)) return new InboundResponse(422, receipt, 0);
            return new InboundResponse(200, receipt, 0);
        }
        catch (InboundReject reject)
        {
            return new InboundResponse(reject.httpStatus(), errorReceipt(platformRequestId,
                    text(integrationCode), reject.code(), reject.getMessage()), reject.retryAfterSeconds());
        }
        catch (ServiceException ex)
        {
            markCurrentTransactionRollbackOnly();
            return new InboundResponse(500, errorReceipt(platformRequestId, text(integrationCode),
                    "INTEGRATION_CONFIG", "接入服务配置不可用"), 5);
        }
        catch (Exception ex)
        {
            markCurrentTransactionRollbackOnly();
            return new InboundResponse(503, errorReceipt(platformRequestId, text(integrationCode),
                    "RECEIVE_UNAVAILABLE", "平台暂时无法可靠接收"), 5);
        }
        finally
        {
            if (permitAcquired && inboundPermit != null) inboundPermit.release();
            operations.releasePermit("in:"+integrationCode,sharedPermit);
        }
    }

    private Semaphore inboundSemaphore(Map<String, Object> integration)
    {
        String integrationId = text(integration.get("integrationId"));
        int limit = Math.max(1, Math.min(integer(integration.get("concurrencyLimit"), 20), 200));
        return inboundPermits.computeIfAbsent(integrationId + "|" + limit,
                ignored -> new Semaphore(limit));
    }

    private void resetInboundLimiter(Long integrationId)
    {
        if (integrationId == null) return;
        String prefix = integrationId + "|";
        inboundPermits.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void markCurrentTransactionRollbackOnly()
    {
        try { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
        catch (RuntimeException ignored) { }
    }

    private Map<String, Object> loadActiveIntegration(String code)
    {
        String normalized = requireCode(code, "integrationCode");
        Map<String, Object> integration = integrationMapper.selectIntegrationByCode(normalized);
        if (integration == null || !"ACTIVE".equalsIgnoreCase(text(integration.get("status"))))
            throw new InboundReject(403, "INTEGRATION_INACTIVE", "接入方未启用", 0);
        return integration;
    }

    private void validateInboundRequest(Map<String, Object> integration, String method,
            String contentType, String remoteAddress, Map<String, String> headers, byte[] body)
    {
        if (!"POST".equalsIgnoreCase(text(method)))
            throw new InboundReject(405, "METHOD_NOT_ALLOWED", "入站接口只允许 POST", 0);
        String normalizedContentType = text(contentType).toLowerCase(Locale.ROOT);
        if (!normalizedContentType.startsWith("application/json")
                && !normalizedContentType.contains("+json"))
            throw new InboundReject(415, "UNSUPPORTED_MEDIA_TYPE", "入站接口只接受 JSON", 0);
        String encoding = header(headers, "content-encoding");
        if (!encoding.isBlank() && !"identity".equalsIgnoreCase(encoding))
            throw new InboundReject(415, "CONTENT_ENCODING_NOT_ALLOWED", "当前接口未启用压缩报文", 0);
        int maxBody = Math.min(Math.max(1024, integer(integration.get("maxBodyBytes"), 1024 * 1024)),
                MAX_INBOUND_BODY);
        if (body == null || body.length == 0)
            throw new InboundReject(400, "EMPTY_BODY", "请求体不能为空", 0);
        if (body.length > maxBody)
            throw new InboundReject(413, "BODY_TOO_LARGE", "请求体超过接入方上限", 0);
        String headerIntegration = header(headers, "x-integration-id");
        if (!headerIntegration.isBlank()
                && !DashboardIntegrationSecurity.constantTimeEquals(
                        text(integration.get("integrationCode")), headerIntegration))
            throw new InboundReject(403, "INTEGRATION_MISMATCH", "接入方标识不一致", 0);
        if (!isAllowedIp(integration, remoteAddress))
            throw new InboundReject(403, "IP_NOT_ALLOWED", "请求来源未授权", 0);
        if (!allowedOrigin(integration, header(headers, "origin")))
            throw new InboundReject(403, "ORIGIN_NOT_ALLOWED", "请求来源未授权", 0);
        String key = text(integration.get("integrationCode"));
        int limit=Math.max(1,integer(integration.get("rateLimit"),10));
        if(!operations.rateAllowed("in:"+key,limit))
            throw new InboundReject(429, "RATE_LIMITED", "接入方请求频率超过上限", 1);
    }

    private AuthContext authenticateInbound(Map<String, Object> integration, String method,
            String requestTarget, Map<String, String> headers, byte[] rawBody)
    {
        Long integrationId = toLong(integration.get("integrationId"));
        String requestedKeyId = header(headers, "x-key-id");
        List<Map<String, Object>> available = integrationMapper.selectIntegrationKeys(integrationId);
        List<Map<String, Object>> candidates = available.stream()
                .filter(key -> "ACTIVE".equalsIgnoreCase(text(key.get("status"))))
                .filter(this::keyInValidityWindow).toList();
        if (requestedKeyId.isBlank())
        {
            if (candidates.size() != 1)
                throw new InboundReject(401, "KEY_ID_REQUIRED", "认证密钥标识缺失", 0);
            requestedKeyId = text(candidates.get(0).get("keyId"));
        }
        if (!DashboardIntegrationSecurity.isSafeExternalValue(requestedKeyId))
            throw new InboundReject(401, "KEY_INVALID", "认证失败", 0);
        Map<String, Object> key = integrationMapper.selectActiveIntegrationKey(integrationId, requestedKeyId);
        if (key == null || !keyInValidityWindow(key))
            throw new InboundReject(401, "KEY_INVALID", "认证失败", 0);
        String provider = text(key.get("provider")).toUpperCase(Locale.ROOT);
        if (!INBOUND_PROVIDERS.contains(provider))
            throw new InboundReject(401, "AUTH_PROVIDER_INVALID", "认证失败", 0);
        String secret = DashboardIntegrationCrypto.decrypt(text(key.get("secretCiphertext")), environment);
        if ("API_KEY".equals(provider))
        {
            String supplied = header(headers, integrationConfig(integration, "apiKeyHeader", "x-api-key"));
            if (!DashboardIntegrationSecurity.constantTimeEquals(secret, supplied))
                throw new InboundReject(401, "AUTH_FAILED", "认证失败", 0);
        }
        else if ("BEARER".equals(provider))
        {
            String supplied = header(headers, "authorization");
            if (!supplied.regionMatches(true, 0, "Bearer ", 0, 7)
                    || !DashboardIntegrationSecurity.constantTimeEquals(secret, supplied.substring(7).trim()))
                throw new InboundReject(401, "AUTH_FAILED", "认证失败", 0);
        }
        else
        {
            verifyInboundHmac(integration, key, method, requestTarget, headers, rawBody, secret);
            String nonce = header(headers, "x-nonce");
            Map<String, Object> nonceRow = new LinkedHashMap<>();
            nonceRow.put("integrationId", integrationId);
            nonceRow.put("keyId", requestedKeyId);
            nonceRow.put("nonce", nonce);
            nonceRow.put("expiresAt", TimestampValue.afterSeconds(
                    Math.max(600, integer(integrationConfigMap(integration).get("clockSkewSeconds"), 300) + 60)));
            try
            {
                integrationMapper.insertNonce(nonceRow);
            }
            catch (DuplicateKeyException ex)
            {
                throw new InboundReject(401, "REPLAYED_NONCE", "请求已被重复使用", 0);
            }
        }
        integrationMapper.touchIntegrationKey(toLong(key.get("integrationKeyId")));
        return new AuthContext(key, provider);
    }

    private void verifyInboundHmac(Map<String, Object> integration, Map<String, Object> key,
            String method, String requestTarget, Map<String, String> headers, byte[] body, String secret)
    {
        Map<String, Object> config = integrationConfigMap(integration);
        String timestamp = header(headers, "x-timestamp");
        String unit = text(config.getOrDefault("timestampUnit", "SECONDS"));
        long timestampMillis;
        try { timestampMillis = DashboardIntegrationSecurity.parseTimestampMillis(timestamp, unit); }
        catch (IllegalArgumentException ex) { throw new InboundReject(401, "TIMESTAMP_INVALID", "认证失败", 0); }
        if (!DashboardIntegrationSecurity.withinClockSkew(timestampMillis, System.currentTimeMillis(),
                integer(config.get("clockSkewSeconds"), 300)))
            throw new InboundReject(401, "TIMESTAMP_EXPIRED", "请求已过期", 0);
        String nonce = header(headers, "x-nonce");
        if (nonce.isBlank() || !DashboardIntegrationSecurity.isSafeExternalValue(nonce))
            throw new InboundReject(401, "NONCE_INVALID", "认证失败", 0);
        String signature = header(headers, "x-signature");
        if (signature.isBlank() || signature.length() > 512)
            throw new InboundReject(401, "SIGNATURE_INVALID", "认证失败", 0);
        String integrationCode = text(integration.get("integrationCode"));
        String keyId = text(key.get("keyId"));
        String idempotency = header(headers, "idempotency-key");
        String schema = header(headers, "x-schema-version");
        String bodyHash = DashboardIntegrationSecurity.sha256(body);
        String profile = text(config.getOrDefault("canonicalProfile",
                "METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1"));
        String canonical;
        if ("METHOD_PATH_TIMESTAMP_NONCE_BODY_V1".equals(profile))
            canonical = text(method).toUpperCase(Locale.ROOT) + "\n" + text(requestTarget) + "\n"
                    + timestamp + "\n" + nonce + "\n" + bodyHash;
        else
            canonical = text(method).toUpperCase(Locale.ROOT) + "\n" + text(requestTarget) + "\n"
                    + integrationCode + "\n" + keyId + "\n" + timestamp + "\n" + nonce + "\n"
                    + idempotency + "\n" + schema + "\n" + bodyHash;
        String expected = "HEX".equalsIgnoreCase(text(config.getOrDefault("signatureEncoding", "BASE64")))
                ? hexHmac(secret, canonical) : DashboardIntegrationSecurity.hmacSha256Base64(secret, canonical);
        if (!DashboardIntegrationSecurity.constantTimeEquals(expected, signature))
            throw new InboundReject(401, "SIGNATURE_INVALID", "认证失败", 0);
        String identity = header(headers, "x-app-key");
        if (!identity.isBlank() && !DashboardIntegrationSecurity.constantTimeEquals(
                identity, text(key.get("identityValue"))))
            throw new InboundReject(401, "IDENTITY_INVALID", "认证失败", 0);
    }

    private JsonNode parseInboundBody(byte[] body)
    {
        try
        {
            JsonNode root = objectMapper.readTree(body);
            validateJsonShape(root, 0, new int[] { 0 });
            if (root == null || !root.isObject())
                throw new InboundReject(400, "INVALID_JSON", "请求体必须是 JSON 对象", 0);
            return root;
        }
        catch (InboundReject ex) { throw ex; }
        catch (Exception ex)
        {
            throw new InboundReject(400, "INVALID_JSON", "请求体不是合法 JSON", 0);
        }
    }

    private InboundEnvelope validateInboundEnvelope(Map<String, Object> integration,
            JsonNode root, Map<String, String> headers)
    {
        Set<String> allowed = Set.of("messageId", "retryOf", "requestId", "sourceCode", "tenantCode",
                "projectCode", "schemaVersion", "sentAt", "items");
        String unknownMode = integrationConfig(integration, "unknownFieldMode", "REJECT").toUpperCase(Locale.ROOT);
        if ("REJECT".equals(unknownMode))
        {
            root.fieldNames().forEachRemaining(name -> {
                if (!allowed.contains(name))
                    throw new InboundReject(400, "UNKNOWN_FIELD", "请求体包含未登记字段", 0);
            });
        }
        String profile = text(integration.get("profile")).toUpperCase(Locale.ROOT);
        String schema = root.path("schemaVersion").asText("");
        String headerSchema = header(headers, "x-schema-version");
        if (!headerSchema.isBlank() && !schema.isBlank() && !headerSchema.equals(schema))
            throw new InboundReject(422, "SCHEMA_VERSION_MISMATCH", "schemaVersion 不一致", 0);
        if (schema.isBlank()) schema = headerSchema;
        if (schema.isBlank()) schema = text(integration.get("schemaVersion"));
        String current = text(integration.get("schemaVersion"));
        String previous = integrationConfig(integration, "previousSchemaVersion", "");
        if (!schema.equals(current) && !schema.equals(previous))
            throw new InboundReject(422, "SCHEMA_VERSION_UNSUPPORTED", "schemaVersion 不受支持", 0);
        if (booleanConfig(integration, "requireSchemaHeader", false) && headerSchema.isBlank())
            throw new InboundReject(400, "SCHEMA_VERSION_REQUIRED", "缺少 schema 版本", 0);
        String messageId = inboundExternal(root, "messageId", "MESSAGE_ID_INVALID");
        if (messageId.isBlank() && booleanConfig(integration, "messageIdRequired", true))
            throw new InboundReject(400, "MESSAGE_ID_REQUIRED", "messageId 不能为空", 0);
        String projectCode = inboundExternal(root, "projectCode", "PROJECT_INVALID");
        String tenantCode = inboundExternal(root, "tenantCode", "TENANT_INVALID");
        String bodyRequestId = inboundExternal(root, "requestId", "REQUEST_ID_INVALID");
        String headerRequestId = inboundExternal(header(headers, "x-request-id"),
                "requestId", "REQUEST_ID_INVALID");
        if (!bodyRequestId.isBlank() && !headerRequestId.isBlank()
                && !bodyRequestId.equals(headerRequestId))
            throw new InboundReject(422, "REQUEST_ID_MISMATCH", "外部请求标识不一致", 0);
        String externalRequestId = headerRequestId.isBlank() ? bodyRequestId : headerRequestId;
        if (!projectCode.isBlank() && !tenantCode.isBlank()
                && !projectCode.equals(tenantCode) && booleanConfig(integration, "projectTenantMustMatch", false))
            throw new InboundReject(422, "SCOPE_MISMATCH", "项目范围不一致", 0);
        String sentAt = root.path("sentAt").asText("");
        if (!sentAt.isBlank())
        {
            try
            {
                parseInstant(sentAt, ZoneId.of(text(integration.getOrDefault("timezone", "Asia/Shanghai"))));
            }
            catch (RuntimeException ex)
            {
                throw new InboundReject(422, "SENT_AT_INVALID", "发送时间格式不合法", 0);
            }
        }
        JsonNode itemsNode = root.get("items");
        List<JsonNode> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray())
            for (JsonNode item : itemsNode) items.add(item);
        else if (itemsNode != null && booleanConfig(integration, "allowSingleItem", false)) items.add(itemsNode);
        else throw new InboundReject(400, "ITEMS_REQUIRED", "items 必须是数组", 0);
        int maxItems = Math.min(MAX_BATCH_ITEMS, Math.max(1, integer(integration.get("maxBatchItems"), 500)));
        if (items.isEmpty() || items.size() > maxItems)
            throw new InboundReject(413, "BATCH_SIZE_INVALID", "批次条数不符合接入方上限", 0);
        String bodySource = inboundExternal(root, "sourceCode", "SOURCE_INVALID");
        String configuredSource = integrationConfig(integration, "sourceCode", "");
        if (!bodySource.isBlank() && !configuredSource.isBlank() && !bodySource.equals(configuredSource))
            throw new InboundReject(403, "SOURCE_MISMATCH", "业务来源不在登记范围", 0);
        return new InboundEnvelope(profile, schema, messageId, projectCode, tenantCode, items,
                inboundExternal(root, "retryOf", "RETRY_REFERENCE_INVALID"), externalRequestId);
    }

    private void validateRetryReference(Map<String, Object> integration, String retryOf)
    {
        if (retryOf == null || retryOf.isBlank()) return;
        Long integrationId = toLong(integration.get("integrationId"));
        Map<String, Object> original = integrationMapper.selectBatchByMessageId(integrationId, retryOf);
        if (original == null)
        {
            original = integrationMapper.selectBatchByPlatformRequestId(retryOf);
            if (original != null && !integrationId.equals(toLong(original.get("integrationId"))))
                original = null;
        }
        if (original == null)
            throw new InboundReject(422, "RETRY_REFERENCE_NOT_FOUND", "retryOf 未引用本接入方已有批次", 0);
    }

    private List<ValidatedItem> validateItems(Map<String, Object> integration, List<JsonNode> items,
            String bodyHash, String envelopeProjectCode)
    {
        List<ValidatedItem> result = new ArrayList<>();
        String profile = text(integration.get("profile")).toUpperCase(Locale.ROOT);
        List<String> scopes = parseArrayQuiet(text(integration.get("projectScopeJson")));
        String timezone = text(integration.getOrDefault("timezone", "Asia/Shanghai"));
        for (int index = 0; index < items.size(); index++)
        {
            JsonNode item = items.get(index);
            String key = "";
            String errorCode = "";
            String errorMessage = "";
            String projectCode = envelopeProjectCode;
            Timestamp businessTime = null;
            try
            {
                if (item == null || !item.isObject()) throw new IllegalArgumentException("记录必须是对象");
                key = profileBusinessKey(integration, profile, item);
                if (!scopes.isEmpty() && (projectCode.isBlank() || !scopes.contains(projectCode)))
                    throw new InboundReject(403, "PROJECT_NOT_ALLOWED", "项目不在接入范围", 0);
                String itemProject = itemExternal(item, "projectCode");
                if (!itemProject.isBlank())
                {
                    if (!projectCode.isBlank() && !projectCode.equals(itemProject))
                        throw new IllegalArgumentException("项目范围不一致");
                    projectCode = itemProject;
                    if (!scopes.isEmpty() && !scopes.contains(projectCode))
                        throw new InboundReject(403, "PROJECT_NOT_ALLOWED", "项目不在接入范围", 0);
                }
                String timeField = switch (profile)
                {
                    case "EVENT" -> "occurredAt";
                    case "SNAPSHOT" -> "snapshotTime";
                    case "RECORD" -> "updatedAt";
                    case "FILE_NOTIFICATION" -> "receivedAt";
                    default -> "";
                };
                String timeValue = item.path(timeField).asText("");
                if (timeValue.isBlank() && booleanConfig(integration, "businessTimeRequired", true))
                    throw new IllegalArgumentException("业务时间不能为空");
                if (!timeValue.isBlank())
                    businessTime = Timestamp.from(parseInstant(timeValue, ZoneId.of(timezone)));
                String operation = item.path("operation").asText("UPSERT").toUpperCase(Locale.ROOT);
                if (Set.of("EVENT", "RECORD").contains(profile)
                        && !Set.of("UPSERT", "DELETE").contains(operation))
                    throw new IllegalArgumentException("operation 不支持");
                if ("FILE_NOTIFICATION".equals(profile))
                {
                    String mediaRef = item.path("mediaRef").asText("");
                    Map<String, Object> media = mediaRef.matches("[A-Za-z0-9_-]{32,128}")
                            ? integrationMapper.selectMediaRef(mediaRef) : null;
                    String approvedSource = integrationConfig(integration, "sourceCode", "");
                    Instant mediaExpiry = media == null ? null : instantValue(media.get("expiresAt"));
                    if (media == null || !"ACTIVE".equalsIgnoreCase(text(media.get("status")))
                            || mediaExpiry == null || !mediaExpiry.isAfter(Instant.now())
                            || approvedSource.isBlank()
                            || !approvedSource.equals(text(media.get("sourceCode")))
                            || !key.equals(text(media.get("businessKey"))))
                        throw new IllegalArgumentException("mediaRef 无效");
                    if (item.has("url") || item.has("downloadUrl"))
                        throw new IllegalArgumentException("文件通知不允许外部 URL");
                }
                String normalized = normalizedItem(profile, key, projectCode, businessTime, operation, item);
                result.add(new ValidatedItem(item, key, projectCode, businessTime, normalized,
                        true, "", ""));
            }
            catch (InboundReject ex)
            {
                throw ex;
            }
            catch (RuntimeException ex)
            {
                errorCode = "ITEM_INVALID";
                errorMessage = limitText(ex.getMessage(), 160);
                if (key.isBlank()) key = "__invalid__" + index + "_" + bodyHash.substring(0, 12);
                result.add(new ValidatedItem(item, key, projectCode, businessTime, "{}",
                        false, errorCode, errorMessage));
            }
        }
        return result;
    }

    private String profileBusinessKey(Map<String, Object> integration, String profile, JsonNode item)
    {
        String configuredField = integrationConfig(integration, "businessKeyField", "");
        String field = configuredField.isBlank() ? switch (profile)
        {
            case "EVENT" -> "eventId";
            case "SNAPSHOT" -> "snapshotId";
            case "RECORD" -> "recordId";
            case "FILE_NOTIFICATION" -> "fileId";
            default -> "";
        } : configuredField;
        String key = item.path(field).asText("");
        if (key.isBlank()) key = item.path("id").asText("");
        if (key.isBlank() || !DashboardIntegrationSecurity.isSafeExternalValue(key))
            throw new IllegalArgumentException("业务主键不能为空或不合法");
        return key;
    }

    private String normalizedItem(String profile, String key, String projectCode,
            Timestamp businessTime, String operation, JsonNode item)
    {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("profile", profile);
        normalized.put("businessKey", key);
        if (!projectCode.isBlank()) normalized.put("projectCode", projectCode);
        if (businessTime != null) normalized.put("businessTime", LOCAL_DATETIME.format(
                businessTime.toLocalDateTime()));
        normalized.put("operation", operation);
        JsonNode data = item.get("data");
        if (data == null || data.isNull()) data = item;
        normalized.put("data", objectMapper.convertValue(data, Object.class));
        if (item.has("version")) normalized.put("version", item.get("version").asLong());
        if (item.has("sequence")) normalized.put("sequence", item.get("sequence").asLong());
        return json(normalized);
    }

    private String normalizeIdempotencyKey(String supplied, String messageId, Map<String, Object> integration)
    {
        String key = inboundExternal(supplied, "Idempotency-Key", "IDEMPOTENCY_KEY_INVALID");
        boolean required = booleanConfig(integration, "idempotencyRequired", true);
        if (key.isBlank() && booleanConfig(integration, "allowDerivedIdempotency", false))
            key = safeOptionalExternal(messageId);
        if (key.isBlank() && required)
            throw new InboundReject(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少幂等标识", 0);
        if (key.isBlank()) key = "body-" + UUID.randomUUID();
        return key;
    }

    private Map<String, Object> messageRow(Long batchId, Map<String, Object> integration,
            InboundEnvelope envelope, ValidatedItem item, String businessKey, int index,
            String status, String errorCode, String errorMessage)
    {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("batchId", batchId);
        message.put("integrationId", integration.get("integrationId"));
        message.put("profile", envelope.profile());
        message.put("businessKey", businessKey);
        message.put("itemIndex", index);
        message.put("projectCode", item.projectCode());
        message.put("businessTime", item.businessTime());
        boolean retain = booleanConfig(integration, "retainRawBody", false)
                && integer(integration.get("rawRetentionDays"), 30) > 0;
        message.put("payloadCiphertext", retain
                ? DashboardIntegrationCrypto.encrypt(item.item().toString(), environment) : null);
        message.put("payloadHash", itemPayloadHash(item.item()));
        message.put("normalizedJson", item.normalizedJson());
        message.put("status", status);
        message.put("retryCount", 0);
        message.put("errorCode", nullableExternal(errorCode));
        message.put("errorMessage", limitText(errorMessage, 500));
        return message;
    }

    private String retainedRawBody(Map<String, Object> integration, byte[] rawBody)
    {
        if (!booleanConfig(integration, "retainRawBody", false)
                || integer(integration.get("rawRetentionDays"), 30) <= 0) return null;
        return DashboardIntegrationCrypto.encrypt(new String(rawBody, StandardCharsets.UTF_8), environment);
    }

    private void batchUpdate(Long batchId, String status, int accepted, int processing,
            int duplicates, int rejected, int retryable, String response, String errorCode, String errorMessage)
    {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("batchId", batchId);
        update.put("status", status);
        update.put("acceptedCount", accepted);
        update.put("processingCount", processing);
        update.put("duplicateCount", duplicates);
        update.put("rejectedCount", rejected);
        update.put("retryableCount", retryable);
        update.put("responseJson", response);
        update.put("errorCode", nullableExternal(errorCode));
        update.put("errorMessage", limitText(errorMessage, 500));
        update.put("errorCodePresent", true);
        update.put("errorMessagePresent", true);
        if (Set.of("ACCEPTED", "PARTIAL", "FAILED", "CONFLICT").contains(status))
            update.put("markProcessedAt", true);
        else update.put("clearProcessedAt", true);
        integrationMapper.updateBatch(update);
    }

    private Map<String, Object> receipt(String platformRequestId, String messageId,
            String integrationCode, int accepted, int processing, int duplicates, int rejected,
            int retryable, List<Map<String, Object>> items, String status)
    {
        Map<String, Object> receipt = new LinkedHashMap<>();
        boolean partial = rejected > 0 || retryable > 0 || "PARTIAL".equals(status);
        receipt.put("code", "CONFLICT".equals(status) ? "CONFLICT"
                : partial ? "PARTIAL"
                : "PROCESSING".equals(status) ? "PROCESSING"
                : "FAILED".equals(status) ? "FAILED" : "ACCEPTED");
        receipt.put("message", "PROCESSING".equals(status)
                ? partial ? "已可靠接收，部分记录待处理或已拒绝" : "已可靠接收，正在处理"
                : "CONFLICT".equals(status) ? "批次存在业务键或版本冲突"
                : "FAILED".equals(status) ? "批次处理失败"
                : partial ? "批次部分处理完成" : "已接收");
        if (!messageId.isBlank()) receipt.put("messageId", messageId);
        receipt.put("requestId", platformRequestId);
        receipt.put("integrationCode", integrationCode);
        receipt.put("accepted", accepted);
        receipt.put("processing", processing);
        receipt.put("duplicates", duplicates);
        receipt.put("rejected", rejected);
        receipt.put("retryable", retryable);
        receipt.put("items", items);
        return receipt;
    }

    private Map<String, Object> errorReceipt(String platformRequestId, String integrationCode,
            String code, String message)
    {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("code", code);
        receipt.put("message", limitText(message, 200));
        receipt.put("requestId", platformRequestId);
        receipt.put("integrationCode", limitText(integrationCode, 64));
        receipt.put("accepted", 0);
        receipt.put("processing", 0);
        receipt.put("duplicates", 0);
        receipt.put("rejected", 0);
        receipt.put("retryable", 0);
        return receipt;
    }

    private InboundResponse replayStoredResponse(Map<String, Object> existing, String ignoredRequestId)
    {
        Map<String, Object> receipt = parseObjectQuiet(text(existing.get("responseJson")));
        if (receipt.isEmpty())
        {
            receipt = errorReceipt(text(existing.get("platformRequestId")), "",
                    "PROCESSING", "请求已接收，正在处理");
        }
        String status = text(existing.get("status"));
        int httpStatus = Set.of("PROCESSING", "RECEIVED", "VALIDATED").contains(status) ? 202
                : "CONFLICT".equals(status) ? 409
                : "FAILED".equals(status) ? 422 : 200;
        return new InboundResponse(httpStatus, receipt, 0);
    }

    private void registerAfterCommit(Runnable task)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override public void afterCommit() { task.run(); }
            });
        else task.run();
    }

    private void scheduleProcess(Long batchId)
    {
        if (batchId == null) return;
        if (taskExecutor != null) taskExecutor.execute(() -> processBatch(batchId));
        else CompletableFuture.runAsync(() -> processBatch(batchId));
    }

    @EventListener(ContextRefreshedEvent.class)
    public void startInboundRecovery()
    {
        if (!maintenanceStarted.compareAndSet(false, true)) return;
        Runnable maintenance = () -> {
            try
            {
                operations.cleanup();
                integrationMapper.deleteExpiredNonces();
                if (retentionService != null) retentionService.maintain();
                integrationMapper.revokeExpiredMediaRefs();
                integrationMapper.deleteExpiredMediaCandidates();
                Instant now = Instant.now();
                mediaCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
                for (Long batchId : integrationMapper.selectRecoverableBatchIds(200))
                    scheduleProcess(batchId);
            }
            catch (RuntimeException ignored)
            {
                // 数据库尚未完成迁移或短暂不可用时保留持久台账；下一轮继续恢复。
            }
        };
        long interval = Math.max(10L, Math.min(integer(environment == null ? null
                : environment.getProperty("dashboard.integration.recovery-interval-seconds"), 30), 3600));
        if (scheduledExecutor != null)
            scheduledExecutor.scheduleWithFixedDelay(maintenance, 1, interval, TimeUnit.SECONDS);
        else CompletableFuture.runAsync(maintenance);
    }

    /* ----------------------------- 异步标准化、查询和死信 ----------------------------- */

    private void processBatch(Long batchId)
    {
        if(batchId==null||!processingBatches.add(batchId)) return;
        try {
            Map<String,Object> batch=integrationMapper.selectBatchById(batchId);
            if(batch==null) return;
            Long id=toLong(batch.get("integrationId"));
            Boolean consumed=operations.withLease("consume:"+id,120,()-> {
                Map<String,Object> integration=integrationMapper.selectIntegrationById(id);
                if(integration==null||!"ACTIVE".equals(text(integration.get("status")))) return false;
                // 租约内重读，迟到重试不能把已完成/已压缩回执重新改为处理中。
                Map<String,Object> current=integrationMapper.selectBatchById(batchId);
                if(current==null) return false;
                List<Map<String,Object>> messages=integrationMapper.selectMessagesByBatch(batchId);
                if(messages.stream().noneMatch(message->Set.of("RECEIVED","PROCESSING","FAILED","RETRYING")
                        .contains(text(message.get("status"))))) return false;
                Map<String,Object> processing=new LinkedHashMap<>(); processing.put("batchId",batchId);
                processing.put("status","PROCESSING"); processing.put("clearProcessedAt",true);
                integrationMapper.updateBatch(processing);
                for(Map<String,Object> message:messages)
                    if(Set.of("RECEIVED","PROCESSING","FAILED","RETRYING").contains(text(message.get("status")))) processMessage(integration,message);
                refreshBatchState(batchId);
                return true;
            });
            if(consumed==null) {
                Runnable retry=()->processBatch(batchId);
                if(scheduledExecutor!=null) scheduledExecutor.schedule(retry,200,TimeUnit.MILLISECONDS);
                else CompletableFuture.delayedExecutor(200,TimeUnit.MILLISECONDS).execute(retry);
            }
        } finally { processingBatches.remove(batchId); }
    }

    private void processMessage(Map<String, Object> integration, Map<String, Object> message)
    {
        Long messageId = toLong(message.get("integrationMessageId"));
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("integrationMessageId", messageId);
        start.put("status", "PROCESSING");
        start.put("errorCode", null);
        start.put("errorMessage", null);
        start.put("errorCodePresent", true);
        start.put("errorMessagePresent", true);
        start.put("clearProcessedAt", true);
        integrationMapper.updateMessage(start);
        try
        {
            JsonNode normalized = objectMapper.readTree(text(message.get("normalizedJson")));
            if (normalized == null || !normalized.isObject())
                throw new IllegalArgumentException("标准化数据格式不合法");
            upsertStandardRecord(integration, message, normalized);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("integrationMessageId", messageId);
            done.put("status", "ACCEPTED");
            done.put("processedAt", TimestampValue.now());
            done.put("errorCode", null);
            done.put("errorMessage", null);
            done.put("errorCodePresent", true);
            done.put("errorMessagePresent", true);
            integrationMapper.updateMessage(done);
            operations.signal(toLong(integration.get("integrationId")));
        }
        catch (ConflictRecordException ex)
        {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("integrationMessageId", messageId);
            conflict.put("status", "CONFLICT");
            conflict.put("processedAt", TimestampValue.now());
            conflict.put("errorCode", "VERSION_CONFLICT");
            conflict.put("errorMessage", "记录版本或业务时间早于当前值");
            conflict.put("errorCodePresent", true);
            conflict.put("errorMessagePresent", true);
            integrationMapper.updateMessage(conflict);
        }
        catch (Exception ex)
        {
            int retries = integer(message.get("retryCount"), 0) + 1;
            int maxAttempts = Math.max(1,
                    Math.min(integrationConfigInt(integration, "maxAttempts", 3), 5));
            int deadLetterAfter = Math.max(1,
                    Math.min(integrationConfigInt(integration, "deadLetterAfter", 5), 5));
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("integrationMessageId", messageId);
            failed.put("retryCount", retries);
            failed.put("errorCode", "PROCESSING_FAILED");
            failed.put("errorMessage", "标准化处理失败");
            failed.put("errorCodePresent", true);
            failed.put("errorMessagePresent", true);
            if (retries >= deadLetterAfter)
            {
                failed.put("status", "DEAD_LETTER");
                operations.alert(text(integration.get("integrationCode")),"DEAD_LETTER");
                failed.put("processedAt", TimestampValue.now());
            }
            else
            {
                failed.put("status", "FAILED");
                failed.put("clearProcessedAt", true);
            }
            integrationMapper.updateMessage(failed);
            // maxAttempts 控制同一轮的快速指数退避；超过后由持久台账恢复任务按较慢
            // 周期继续尝试，累计达到 deadLetterAfter 才进入死信。
            if (retries < deadLetterAfter && retries < maxAttempts)
            {
                long delay = Math.min(30000L, 500L * (1L << Math.min(retries - 1, 6)));
                Runnable retry = () -> processBatch(toLong(message.get("batchId")));
                if (scheduledExecutor != null) scheduledExecutor.schedule(retry, delay, TimeUnit.MILLISECONDS);
                else CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(retry);
            }
        }
    }

    private void upsertStandardRecord(Map<String, Object> integration,
            Map<String, Object> message, JsonNode normalized)
    {
        Long integrationId = toLong(integration.get("integrationId"));
        String profile = text(message.get("profile"));
        String key = text(message.get("businessKey"));
        String operation = normalized.path("operation").asText("UPSERT");
        Map<String, Object> existing = integrationMapper.selectRecordByBusinessKey(integrationId, profile, key);
        String dataJson = normalized.path("data").isMissingNode()
                ? "{}" : normalized.path("data").toString();
        Long incomingVersion = normalized.has("version") ? normalized.path("version").asLong(0) : 0L;
        Timestamp businessTime = timestampFromValue(message.get("businessTime"));
        if (existing == null)
        {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("integrationId", integrationId);
            record.put("environment", text(integration.get("environment")));
            record.put("profile", profile);
            record.put("businessKey", key);
            record.put("projectCode", message.get("projectCode"));
            record.put("dataJson", "DELETE".equals(operation) ? "{}" : dataJson);
            record.put("sourceBusinessTime", businessTime);
            record.put("sourceUpdatedAt", businessTime);
            record.put("versionNo", incomingVersion > 0 ? incomingVersion : 1L);
            record.put("status", "DELETE".equals(operation) ? "DELETED" : "ACTIVE");
            try
            {
                integrationMapper.insertRecord(record);
            }
            catch (DuplicateKeyException race)
            {
                // 并发重复由后续查询和版本判断收敛，不生成第二条标准记录。
                existing = integrationMapper.selectRecordByBusinessKey(integrationId, profile, key);
                if (existing == null) throw race;
            }
            if (existing == null) return;
        }
        if (existing != null)
        {
            long currentVersion = longValue(existing.get("versionNo"), 0L);
            Timestamp currentTime = timestampFromValue(existing.get("sourceBusinessTime"));
            String targetStatus = "DELETE".equals(operation) ? "DELETED" : "ACTIVE";
            boolean sameRecord = sameJson(text(existing.get("dataJson")),
                    "DELETE".equals(operation) ? "{}" : dataJson)
                    && text(existing.get("projectCode")).equals(text(message.get("projectCode")))
                    && targetStatus.equals(text(existing.get("status")))
                    && sameTimestampSecond(currentTime, businessTime);
            if (incomingVersion > 0)
            {
                if (currentVersion > incomingVersion || currentVersion == incomingVersion && !sameRecord)
                    throw new ConflictRecordException();
                if (currentVersion == incomingVersion) return;
            }
            else if (currentTime != null && businessTime != null)
            {
                if (currentTime.after(businessTime)
                        || sameTimestampSecond(currentTime, businessTime) && !sameRecord)
                    throw new ConflictRecordException();
                if (sameRecord) return;
            }
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("recordId", existing.get("recordId"));
            update.put("projectCode", message.get("projectCode"));
            update.put("dataJson", "DELETE".equals(operation) ? "{}" : dataJson);
            update.put("sourceBusinessTime", businessTime);
            update.put("sourceUpdatedAt", businessTime);
            update.put("versionNo", incomingVersion > 0 ? incomingVersion : Math.max(1, currentVersion + 1));
            update.put("status", targetStatus);
            integrationMapper.updateRecord(update);
        }
    }

    private void refreshBatchState(Long batchId)
    {
        Map<String, Object> batch = integrationMapper.selectBatchById(batchId);
        List<Map<String, Object>> messages = integrationMapper.selectMessagesByBatch(batchId);
        int accepted = 0;
        int processing = 0;
        int duplicates = 0;
        int rejected = 0;
        int retryable = 0;
        int dead = 0;
        int conflicts = 0;
        List<Map<String, Object>> itemResults = new ArrayList<>();
        for (Map<String, Object> message : messages)
        {
            String status = text(message.get("status"));
            if ("ACCEPTED".equals(status)) accepted++;
            else if (Set.of("RECEIVED", "PROCESSING").contains(status)) processing++;
            else if ("DUPLICATE".equals(status)) duplicates++;
            else if ("REJECTED".equals(status)) rejected++;
            else if ("FAILED".equals(status)) retryable++;
            else if ("DEAD_LETTER".equals(status)) { dead++; rejected++; }
            else if ("CONFLICT".equals(status)) { conflicts++; rejected++; }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", message.get("itemIndex"));
            item.put("businessKey", message.get("businessKey"));
            item.put("status", status);
            if (message.get("errorCode") != null) item.put("code", message.get("errorCode"));
            itemResults.add(item);
        }
        Map<String, Object> previousReceipt = parseObjectQuiet(text(batch == null ? null : batch.get("responseJson")));
        Object previousItems = previousReceipt.get("items");
        if (previousItems instanceof Collection<?> collection)
        {
            for (Object value : collection)
            {
                if (!(value instanceof Map<?, ?> raw)
                        || !Set.of("DUPLICATE", "CONFLICT").contains(text(raw.get("status")))) continue;
                Map<String, Object> item = castMap(raw);
                boolean alreadyPresent = itemResults.stream().anyMatch(existing ->
                        text(existing.get("index")).equals(text(item.get("index"))));
                if (!alreadyPresent)
                {
                    itemResults.add(item);
                    if ("DUPLICATE".equals(text(item.get("status")))) duplicates++;
                    else { conflicts++; rejected++; }
                }
            }
        }
        itemResults.sort(Comparator.comparingInt(item -> integer(item.get("index"), Integer.MAX_VALUE)));
        String status;
        if (processing > 0 || retryable > 0) status = "PROCESSING";
        else if (rejected > 0 && (accepted > 0 || duplicates > 0)) status = "PARTIAL";
        else if (conflicts > 0) status = "CONFLICT";
        else if (rejected > 0 || dead > 0) status = "FAILED";
        else status = "ACCEPTED";
        String platformRequestId = text(batch == null ? null : batch.get("platformRequestId"));
        String integrationCode = "";
        if (batch != null)
        {
            Map<String, Object> integration = integrationMapper.selectIntegrationById(toLong(batch.get("integrationId")));
            integrationCode = text(integration == null ? null : integration.get("integrationCode"));
        }
        Map<String, Object> receipt = receipt(platformRequestId, text(batch == null ? null : batch.get("messageId")),
                integrationCode, accepted, processing, duplicates, rejected, retryable, itemResults, status);
        batchUpdate(batchId, status, accepted, processing, duplicates, rejected, retryable,
                json(receipt), dead > 0 ? "DEAD_LETTER" : conflicts > 0 ? "VERSION_CONFLICT" : "",
                dead > 0 ? "存在死信记录" : conflicts > 0 ? "存在版本或业务键冲突" : "");
    }

    public Map<String, Object> getBatch(Long batchId)
    {
        Map<String, Object> batch = integrationMapper.selectBatchById(batchId);
        if (batch == null) throw new ServiceException("接收批次不存在");
        Map<String, Object> result = safeBatch(batch);
        List<Map<String, Object>> items = new ArrayList<>();
        if (batch.get("detailsExpiredAt") == null)
            for (Map<String, Object> message : integrationMapper.selectMessagesByBatch(batchId))
                items.add(safeMessageRow(message));
        result.put("items", items);
        return result;
    }

    public List<Map<String, Object>> listBatches(Long integrationId, String status, Integer limit)
    {
        if (integrationId != null) requireIntegration(integrationId);
        int bounded = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : integrationMapper.selectBatchList(integrationId,
                text(status).isBlank() ? null : text(status).toUpperCase(Locale.ROOT), bounded))
            result.add(safeBatch(item));
        return result;
    }

    public List<Map<String, Object>> listDeadLetters(Long integrationId, String keyword, Integer limit)
    {
        if (integrationId != null) requireIntegration(integrationId);
        int bounded = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : integrationMapper.selectDeadLetterList(integrationId,
                limitText(text(keyword), 80), bounded))
            result.add(safeMessageRow(item));
        return result;
    }

    public List<Map<String, Object>> listBatchesPage(Long integrationId, String status, Integer pageNum, Integer pageSize)
    {
        if (integrationId != null) requireIntegration(integrationId);
        startManagementPage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = integrationMapper.selectBatchList(integrationId,
                    text(status).isBlank() ? null : text(status).toUpperCase(Locale.ROOT), null);
            rows.replaceAll(this::safeBatch);
            return rows;
        }
        finally { PageHelper.clearPage(); }
    }

    public List<Map<String, Object>> listDeadLettersPage(Long integrationId, String keyword, Integer pageNum, Integer pageSize)
    {
        if (integrationId != null) requireIntegration(integrationId);
        startManagementPage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = integrationMapper.selectDeadLetterList(integrationId,
                    limitText(text(keyword), 80), null);
            rows.replaceAll(this::safeMessageRow);
            return rows;
        }
        finally { PageHelper.clearPage(); }
    }

    @Transactional
    public Map<String, Object> replayDeadLetter(Long messageId, String reason, String username)
    {
        Map<String, Object> message = integrationMapper.selectMessageById(messageId);
        if (message == null) throw new ServiceException("消息不存在");
        if (!"DEAD_LETTER".equals(text(message.get("status"))))
            throw new ServiceException("只有死信消息可以重放");
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("integrationMessageId", messageId);
        update.put("status", "RECEIVED");
        update.put("retryCount", 0);
        update.put("errorCode", null);
        update.put("errorMessage", limitText(reason, 500));
        update.put("errorCodePresent", true);
        update.put("errorMessagePresent", true);
        update.put("clearProcessedAt", true);
        integrationMapper.updateMessage(update);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", message.get("batchId"));
        batch.put("status", "PROCESSING");
        batch.put("errorCode", null);
        batch.put("errorMessage", null);
        batch.put("errorCodePresent", true);
        batch.put("errorMessagePresent", true);
        batch.put("clearProcessedAt", true);
        integrationMapper.updateBatch(batch);
        registerAfterCommit(() -> scheduleProcess(toLong(message.get("batchId"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", messageId);
        result.put("status", "PROCESSING");
        result.put("operator", limitText(username, 64));
        result.put("reason", limitText(reason, 200));
        return result;
    }

    private Map<String, Object> safeBatch(Map<String, Object> batch)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("batchId", "integrationId", "endpointCode", "normalizedIdempotencyKey",
                "messageId", "externalRequestId", "bodyHash", "platformRequestId", "schemaVersion", "status",
                "acceptedCount", "processingCount", "duplicateCount", "rejectedCount", "retryableCount",
                "errorCode", "errorMessage", "receivedAt", "processedAt", "expiresAt", "detailsExpiredAt"))
            if (batch.containsKey(key)) result.put(key, batch.get(key));
        result.put("detailExpired", batch.get("detailsExpiredAt") != null);
        return result;
    }

    private Map<String, Object> safeMessageRow(Map<String, Object> message)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("integrationMessageId", "batchId", "integrationId", "integrationCode",
                "profile", "businessKey", "itemIndex", "projectCode", "businessTime", "status",
                "retryCount", "errorCode", "errorMessage", "receivedAt", "processedAt"))
            if (message.containsKey(key)) result.put(key, message.get(key));
        return result;
    }

    /**
     * 外部系统查询处理结果。查询入口仍要求同一接入方认证，避免通过可猜的
     * messageId 读取其他接入方台账。
     */
    public InboundResponse queryInboundStatus(String integrationCode, String endpointCode,
            String method, String requestTarget, String remoteAddress, Map<String, String> headers,
            String messageId, String platformRequestId)
    {
        String requestId = UUID.randomUUID().toString();
        Semaphore inboundPermit = null;
        boolean permitAcquired = false;
        try
        {
            Map<String, Object> integration = loadActiveIntegration(integrationCode);
            if (!text(integration.get("endpointCode")).equals(text(endpointCode)))
                throw new InboundReject(404, "ENDPOINT_NOT_FOUND", "入站接口不存在", 0);
            String headerIntegration = header(headers, "x-integration-id");
            if (!headerIntegration.isBlank()
                    && !DashboardIntegrationSecurity.constantTimeEquals(
                            text(integration.get("integrationCode")), headerIntegration))
                throw new InboundReject(403, "INTEGRATION_MISMATCH", "接入方标识不一致", 0);
            if (!isAllowedIp(integration, remoteAddress) || !allowedOrigin(integration, header(headers, "origin")))
                throw new InboundReject(403, "REQUEST_NOT_ALLOWED", "请求来源未授权", 0);
            WindowCounter counter = inboundRateCounters.computeIfAbsent(
                    text(integration.get("integrationCode")) + "|query|" + text(remoteAddress),
                    ignored -> new WindowCounter());
            if (!counter.tryAcquire(Math.max(1, integer(integration.get("rateLimit"), 10))))
                throw new InboundReject(429, "RATE_LIMITED", "接入方查询频率超过上限", 1);
            inboundPermit = inboundSemaphore(integration);
            if (!inboundPermit.tryAcquire())
                throw new InboundReject(429, "CONCURRENCY_LIMITED", "接入方并发请求达到上限", 1);
            permitAcquired = true;
            authenticateInbound(integration, method, requestTarget, headers, new byte[0]);
            Map<String, Object> batch;
            if (!text(messageId).isBlank())
            {
                if (!DashboardIntegrationSecurity.isSafeExternalValue(messageId))
                    throw new InboundReject(400, "MESSAGE_ID_INVALID", "messageId 不合法", 0);
                batch = integrationMapper.selectBatchByMessageId(toLong(integration.get("integrationId")),
                        messageId);
            }
            else
            {
                if (!DashboardIntegrationSecurity.isSafeExternalValue(platformRequestId))
                    throw new InboundReject(400, "REQUEST_ID_INVALID", "requestId 不合法", 0);
                batch = integrationMapper.selectBatchByPlatformRequestId(platformRequestId);
                if (batch != null && !toLong(integration.get("integrationId"))
                        .equals(toLong(batch.get("integrationId")))) batch = null;
            }
            if (batch == null)
                throw new InboundReject(404, "RESULT_NOT_FOUND", "处理结果不存在", 0);
            return new InboundResponse(200, statusReceipt(batch), 0);
        }
        catch (InboundReject reject)
        {
            return new InboundResponse(reject.httpStatus(), errorReceipt(requestId, text(integrationCode),
                    reject.code(), reject.getMessage()), reject.retryAfterSeconds());
        }
        catch (Exception ex)
        {
            return new InboundResponse(503, errorReceipt(requestId, text(integrationCode),
                    "QUERY_UNAVAILABLE", "处理结果暂时不可查询"), 5);
        }
        finally
        {
            if (permitAcquired && inboundPermit != null) inboundPermit.release();
        }
    }

    private Map<String, Object> statusReceipt(Map<String, Object> batch)
    {
        Map<String, Object> result = parseObjectQuiet(text(batch.get("responseJson")));
        if (result.isEmpty())
        {
            result = new LinkedHashMap<>();
            result.put("code", text(batch.get("status")));
            result.put("message", "处理状态已记录");
            result.put("requestId", batch.get("platformRequestId"));
            result.put("messageId", batch.get("messageId"));
            result.put("accepted", batch.get("acceptedCount"));
            result.put("processing", batch.get("processingCount"));
            result.put("duplicates", batch.get("duplicateCount"));
            result.put("rejected", batch.get("rejectedCount"));
            result.put("retryable", batch.get("retryableCount"));
        }
        result.put("batchId", batch.get("batchId"));
        result.put("status", batch.get("status"));
        result.put("updatedAt", batch.get("processedAt") == null
                ? batch.get("receivedAt") : batch.get("processedAt"));
        return result;
    }

    /* ----------------------------- 媒体引用和代理 ----------------------------- */

    @Transactional
    public Map<String, Object> issueMediaRef(String candidateRef, String datasetCode,
            Long pageId, Long revisionId, Long shareId)
    {
        if (candidateRef == null || !candidateRef.matches("[A-Za-z0-9_-]{32,128}"))
            throw new ServiceException("媒体候选引用无效");
        String normalizedDatasetCode = requireCode(datasetCode, "数据集编码");
        if (pageId == null || revisionId == null)
            throw new ServiceException("媒体页面版本绑定不能为空");
        Map<String, Object> candidate = integrationMapper.selectMediaRef(candidateRef);
        if (candidate == null || !"CANDIDATE".equalsIgnoreCase(text(candidate.get("status")))
                || !normalizedDatasetCode.equals(text(candidate.get("datasetCode"))))
            throw new ServiceException("媒体候选引用无效或与数据集不匹配");
        Instant candidateExpiry = instantValue(candidate.get("expiresAt"));
        if (candidateExpiry == null || !candidateExpiry.isAfter(Instant.now()))
            throw new ServiceException("媒体候选引用已过期");
        Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(normalizedDatasetCode);
        Map<String,Object> revision=dashboardMapper.selectRevisionMetadata(revisionId);
        boolean preview=shareId==null&&revision!=null&&"DRAFT".equals(revision.get("status"))&&com.ruoyi.common.utils.SecurityUtils.hasPermi("dashboard:page:preview");
        if (dataset == null || !("ACTIVE".equalsIgnoreCase(text(dataset.get("status"))) || preview&&"DRAFT".equalsIgnoreCase(text(dataset.get("status"))))
                || !"API".equalsIgnoreCase(text(dataset.get("dataType"))))
            throw new ServiceException("媒体候选数据集未启用");
        Map<String, Object> datasetConfig = parseObject(text(dataset.get("configJson")), "媒体候选数据集配置");
        if (!text(candidate.get("sourceCode")).equals(text(datasetConfig.get("sourceCode"))))
            throw new ServiceException("媒体候选来源与数据集不一致");
        if (!datasetDeclaresMediaEndpoint(datasetConfig, text(candidate.get("endpointCode"))))
            throw new ServiceException("媒体候选已不在当前数据集配置中");
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(
                text(candidate.get("sourceCode")), text(candidate.get("endpointCode")));
        if (endpoint == null) throw new ServiceException("媒体 endpoint 不存在");
        if (!"ACTIVE".equalsIgnoreCase(text(endpoint.get("status")))
                || !"ACTIVE".equalsIgnoreCase(text(endpoint.get("sourceStatus"))))
            throw new ServiceException("媒体 endpoint 未启用");
        if (!"BINARY_MEDIA".equalsIgnoreCase(text(endpoint.get("responseType"))))
            throw new ServiceException("endpoint 不是媒体响应类型");
        if (integrationMapper.consumeMediaCandidate(candidateRef) != 1)
            throw new ServiceException("媒体候选引用已被使用或已失效");
        String mediaRef = DashboardIntegrationSecurity.randomToken(48);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mediaRef", mediaRef);
        row.put("sourceCode", candidate.get("sourceCode"));
        row.put("endpointCode", candidate.get("endpointCode"));
        row.put("datasetCode", normalizedDatasetCode);
        row.put("mediaProfile", candidate.get("mediaProfile"));
        row.put("businessKey", candidate.get("businessKey"));
        row.put("formParamsCiphertext", candidate.get("formParamsCiphertext"));
        row.put("pageId", pageId);
        row.put("revisionId", revisionId);
        row.put("shareId", shareId);
        row.put("expiresAt", Timestamp.from(candidateExpiry));
        row.put("status", "ACTIVE");
        integrationMapper.insertMediaRef(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mediaRef", mediaRef);
        result.put("expiresAt", row.get("expiresAt"));
        result.put("contentType", "application/octet-stream");
        return result;
    }

    public MediaPayload fetchMedia(String mediaRef, Long pageId, Long revisionId, Long shareId)
            throws Exception
    {
        if (mediaRef == null || !mediaRef.matches("[A-Za-z0-9_-]{32,128}"))
            throw new ServiceException("媒体引用无效");
        Map<String, Object> ref = integrationMapper.selectMediaRef(mediaRef);
        if (ref == null || !"ACTIVE".equalsIgnoreCase(text(ref.get("status"))))
            throw new ServiceException("媒体引用无效或已撤销");
        Instant expires = instantValue(ref.get("expiresAt"));
        if (expires != null && !expires.isAfter(Instant.now()))
            throw new ServiceException("媒体引用已过期");
        if (!bindingMatches(ref, pageId, revisionId, shareId))
            throw new ServiceException("媒体引用未授权");
        Map<String, Object> endpoint = integrationMapper.selectEndpointByCode(
                text(ref.get("sourceCode")), text(ref.get("endpointCode")));
        if (endpoint == null || !"BINARY_MEDIA".equalsIgnoreCase(text(endpoint.get("responseType")))
                || !"ACTIVE".equalsIgnoreCase(text(endpoint.get("status")))
                || !"ACTIVE".equalsIgnoreCase(text(endpoint.get("sourceStatus"))))
            throw new ServiceException("媒体 endpoint 不可用");
        CachedMedia cached = mediaCache.get(mediaRef);
        if (cached != null && cached.expiresAt().isAfter(Instant.now()))
            return cached.payload();
        Map<String, Object> sourceConfig = parseObject(text(endpoint.get("sourceConfigJson")), "HTTP 数据源配置");
        Map<String, Object> endpointConfig = parseObject(text(endpoint.get("configJson")), "媒体 endpoint 配置");
        String secretCipher = !text(endpoint.get("credentialCiphertext")).isBlank()
                ? text(endpoint.get("credentialCiphertext")) : text(endpoint.get("sourceSecretCiphertext"));
        String provider = text(endpoint.get("authProvider")).toUpperCase(Locale.ROOT);
        if (provider.isBlank() || "INHERIT".equals(provider))
            provider = text(sourceConfig.get("authProvider")).toUpperCase(Locale.ROOT);
        if (provider.isBlank()) throw new ServiceException("媒体 endpoint 未配置认证 provider");
        String credentialRef=text(endpoint.get("credentialRef"));
        if(credentialRef.isBlank()) credentialRef=text(sourceConfig.get("credentialRef"));
        String secret = "NO_AUTH".equals(provider) ? "" : DashboardIntegrationCredentials.resolve(credentialRef,secretCipher,environment);
        EndpointContext context = contextForMedia(endpoint, sourceConfig, endpointConfig, provider, secret);
        Map<String, Object> values = parseObject(DashboardIntegrationCrypto.decrypt(
                text(ref.get("formParamsCiphertext")), environment), "媒体参数");
        byte[] payload = requestBinary(context, values, endpointConfig, UUID.randomUUID().toString());
        MediaPayload result = prepareMediaPayload(mediaRef, payload, endpointConfig);
        long ttl = expires == null ? 300 : Math.max(1, Math.min(
                Duration.between(Instant.now(), expires).getSeconds(), 86400));
        mediaCache.put(mediaRef, new CachedMedia(result, Instant.now().plusSeconds(ttl)));
        return result;
    }

    private boolean datasetDeclaresMediaEndpoint(Map<String, Object> datasetConfig, String endpointCode)
    {
        Object value = datasetConfig.get("mediaProjections");
        if (!(value instanceof Collection<?> projections)) return false;
        for (Object raw : projections)
            if (raw instanceof Map<?, ?> projection
                    && endpointCode.equals(text(projection.get("endpointCode")))) return true;
        return false;
    }

    @Transactional
    public int revokeMediaForPage(Long pageId, Long revisionId)
    {
        return integrationMapper.revokeMediaRefsByPage(pageId, revisionId);
    }

    @Transactional
    public int revokeMediaForShare(Long shareId)
    {
        return integrationMapper.revokeMediaRefsByShare(shareId);
    }

    @Transactional
    public int revokeMediaForDeletedPages()
    {
        return integrationMapper.revokeMediaRefsForDeletedPages();
    }

    /** 返回媒体引用的绑定摘要，供登录/分享 Controller 在拉取前执行页面授权校验。 */
    public MediaBinding getMediaBinding(String mediaRef)
    {
        if (mediaRef == null || !mediaRef.matches("[A-Za-z0-9_-]{32,128}"))
            throw new ServiceException("媒体引用无效");
        Map<String, Object> ref = integrationMapper.selectMediaRef(mediaRef);
        if (ref == null || !"ACTIVE".equalsIgnoreCase(text(ref.get("status"))))
            throw new ServiceException("媒体引用无效或已撤销");
        Instant expires = instantValue(ref.get("expiresAt"));
        if (expires != null && !expires.isAfter(Instant.now()))
            throw new ServiceException("媒体引用已过期");
        return new MediaBinding(mediaRef, toLong(ref.get("pageId")), toLong(ref.get("revisionId")),
                toLong(ref.get("shareId")), expires);
    }

    private Map<String, Object> normalizeMediaForm(Map<String, Object> formParams, Map<String, Object> config)
    {
        Map<String, Object> input = formParams == null ? Collections.emptyMap() : formParams;
        Set<String> allowed = new LinkedHashSet<>(stringList(config.get("formFields"), 32, 64));
        if (allowed.isEmpty())
            throw new ServiceException("媒体 endpoint 未登记表单字段");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : input.keySet())
        {
            if (!allowed.contains(name) || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                throw new ServiceException("媒体参数未登记: " + name);
            String value = scalarText(input.get(name), name);
            validateMediaParameterValue(value);
            result.put(name, value);
        }
        if (!result.keySet().containsAll(allowed))
            throw new ServiceException("媒体请求缺少已登记的表单字段");
        return result;
    }

    private void validateMediaParameterValue(String value)
    {
        if (value == null || value.isBlank() || value.length() > 512)
            throw new ServiceException("媒体参数为空或过长");
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || value.startsWith("//") || value.contains("\\")
                || value.contains("\r") || value.contains("\n") || value.indexOf('\0') >= 0)
            throw new ServiceException("媒体参数包含不允许的地址或控制字符");
        for (String segment : value.split("/", -1))
            if ("..".equals(segment)) throw new ServiceException("媒体参数包含不允许的路径段");
    }

    private Map<String, Object> mediaPolicy(Map<String, Object> config)
    {
        Object value = config.get("media");
        if (value instanceof Map<?, ?> map) return castMap(map);
        return new LinkedHashMap<>();
    }

    private boolean bindingMatches(Map<String, Object> ref, Long pageId, Long revisionId, Long shareId)
    {
        Long refPage = toLong(ref.get("pageId"));
        Long refRevision = toLong(ref.get("revisionId"));
        Long refShare = toLong(ref.get("shareId"));
        if (refShare != null)
            return shareId != null && refShare.equals(shareId)
                    && (refPage == null || refPage.equals(pageId))
                    && (refRevision == null || refRevision.equals(revisionId));
        if (refPage != null && (pageId == null || !refPage.equals(pageId))) return false;
        return refRevision == null || revisionId != null && refRevision.equals(revisionId);
    }

    private EndpointContext contextForMedia(Map<String, Object> endpoint, Map<String, Object> sourceConfig,
            Map<String, Object> endpointConfig, String provider, String secret)
    {
        Map<String, String> mapping = stringMap(endpointConfig.get("headerMapping"));
        return new EndpointContext(toLong(endpoint.get("endpointId")), text(endpoint.get("sourceCode")),
                text(endpoint.get("endpointCode")), endpoint, sourceConfig, endpointConfig,
                text(endpoint.get("path")), text(endpoint.getOrDefault("method", "POST")).toUpperCase(Locale.ROOT),
                text(endpoint.getOrDefault("requestContentType", "FORM_URLENCODED")).toUpperCase(Locale.ROOT),
                "BINARY_MEDIA", provider, secret,
                text(endpointConfig.getOrDefault("identity", sourceConfig.get("identity"))),
                text(endpointConfig.getOrDefault("keyId", endpoint.get("credentialRef"))),
                text(endpointConfig.getOrDefault("timestampUnit", "SECONDS")).toUpperCase(Locale.ROOT),
                booleanValue(endpointConfig.get("nonceRequired"), true),
                text(endpointConfig.getOrDefault("canonicalProfile",
                        "METHOD_PATH_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1")),
                text(endpointConfig.getOrDefault("signatureEncoding", "BASE64")).toUpperCase(Locale.ROOT),
                mapping);
    }

    private byte[] requestBinary(EndpointContext context, Map<String, Object> values,
            Map<String, Object> endpointConfig, String requestId) throws Exception
    {
        for (Map.Entry<String, Object> entry : values.entrySet())
            validateMediaParameterValue(scalarText(entry.getValue(), entry.getKey()));
        List<Map<String, Object>> declarations = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        String bodyLocation = "JSON".equals(context.requestContentType()) ? "json" : "form";
        for (String name : stringList(endpointConfig.get("formFields"), 32, 64))
        {
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", name);
            declaration.put("in", bodyLocation);
            declaration.put("type", "STRING");
            declaration.put("required", true);
            declarations.add(declaration);
            declared.add(name);
        }
        Matcher pathMatcher = PATH_PARAMETER.matcher(context.path());
        while (pathMatcher.find())
        {
            String name = pathMatcher.group(1) == null ? pathMatcher.group(2) : pathMatcher.group(1);
            if (!declared.add(name)) continue;
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", name);
            declaration.put("in", "path");
            declaration.put("type", "STRING");
            declaration.put("required", true);
            declarations.add(declaration);
        }
        Map<String, Object> fakeDataset = new LinkedHashMap<>();
        fakeDataset.put("paramSchemaJson", json(declarations));
        Map<String, Object> policy = mediaPolicy(endpointConfig);
        int sourceTimeout = Math.max(1, Math.min(
                integer(context.sourceConfig().get("timeoutSeconds"), 15), 120));
        int firstByteTimeout = Math.min(sourceTimeout, Math.max(1, Math.min(
                integer(policy.get("firstByteTimeoutSeconds"), sourceTimeout), 120)));
        int downloadTimeout = Math.min(Math.max(sourceTimeout, 1), Math.max(1, Math.min(
                integer(policy.get("downloadTimeoutSeconds"), sourceTimeout), 300)));
        int sourceConcurrency = Math.max(1, Math.min(
                integer(context.sourceConfig().get("concurrencyLimit"), 20), 100));
        int concurrency = Math.min(sourceConcurrency, Math.max(1, Math.min(
                integer(policy.get("concurrencyLimit"), sourceConcurrency), 100)));
        String permit=operations.acquirePermit("out:"+context.sourceCode(),concurrency,firstByteTimeout+downloadTimeout+5);
        if(permit==null) throw new OutboundFailure("RATE_LIMITED","媒体来源并发达到上限",true,1);
        try
        {

            HttpRequest request = buildOutboundRequest(context, fakeDataset,
                    new LinkedHashMap<>(endpointConfig), values,
                    requestId, DashboardIntegrationSecurity.randomToken(24), firstByteTimeout);
            HttpResponse<InputStream> response = DashboardPinnedHttp.send(request,validateOutboundTarget(request.uri().toString(),context.sourceConfig()));
            try (InputStream input = response.body())
            {
                if (response.statusCode() == 400 || response.statusCode() == 422)
                    throw new OutboundFailure("SOURCE_ERROR", "媒体来源拒绝了请求参数", false, 0);
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new OutboundFailure("AUTH_ERROR", "媒体来源鉴权失败", false, 0);
                if (response.statusCode() == 429)
                    throw new OutboundFailure("RATE_LIMITED", "媒体来源请求频率受限", true,
                            retryAfter(response));
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new OutboundFailure("SOURCE_ERROR", "媒体来源暂不可用",
                            response.statusCode() >= 500, retryAfter(response));
                int maxBytes = Math.max(1024, Math.min(integer(policy.get("maxBytes"),
                        DEFAULT_MAX_RESPONSE_BYTES), MAX_RESPONSE_BYTES));
                return readBoundedWithTimeout(input, maxBytes, downloadTimeout);
            }
        }
        catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException ex)
        {
            throw new OutboundFailure("TIMEOUT", "媒体来源连接或首字节超时", true, 0);
        }
        catch (IOException ex)
        {
            throw new OutboundFailure("SOURCE_ERROR", "媒体来源读取失败", true, 0);
        }
        finally
        {
            operations.releasePermit("out:"+context.sourceCode(),permit);
        }
    }

    private byte[] readBoundedWithTimeout(InputStream input, int maxBytes, int timeoutSeconds)
            throws IOException, InterruptedException
    {
        Executor executor = taskExecutor == null ? java.util.concurrent.ForkJoinPool.commonPool() : taskExecutor;
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            try { return readBounded(input, maxBytes); }
            catch (IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
        }, executor);
        try
        {
            return future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.TimeoutException ex)
        {
            try { input.close(); } catch (IOException ignored) { }
            future.cancel(true);
            throw new OutboundFailure("TIMEOUT", "来源完整下载超时", true, 0);
        }
        catch (java.util.concurrent.ExecutionException ex)
        {
            Throwable cause = rootCause(ex);
            if (cause instanceof OutboundFailure failure) throw failure;
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException("媒体来源读取失败", cause);
        }
    }

    private MediaPayload prepareMediaPayload(String mediaRef, byte[] payload,
            Map<String, Object> endpointConfig)
    {
        Map<String, Object> policy = mediaPolicy(endpointConfig);
        int maxBytes = Math.max(1024, Math.min(integer(policy.get("maxBytes"),
                DEFAULT_MAX_RESPONSE_BYTES), MAX_RESPONSE_BYTES));
        if (payload == null || payload.length == 0) throw new ServiceException("媒体内容为空");
        if (payload.length > maxBytes) throw new ServiceException("媒体内容超过字节上限");
        String contentType = detectMediaType(payload, safeAllowedMimeTypes(endpointConfig));
        String profile = text(endpointConfig.getOrDefault("mediaProfile", "IMAGE")).toUpperCase(Locale.ROOT);
        if ("IMAGE".equals(profile) && !contentType.startsWith("image/"))
            throw new ServiceException("媒体内容与 IMAGE profile 不匹配");
        DashboardMediaScanner.scan(payload,environment,booleanValue(policy.get("scanRequired"),"application/pdf".equals(contentType)));
        if("application/pdf".equals(contentType)) payload=sanitizePdf(payload);
        MediaInspection inspection = contentType.startsWith("image/")
                ? inspectImage(payload, contentType, policy) : new MediaInspection(payload, 0, 0, 0);
        if (inspection.bytes().length > maxBytes)
            throw new ServiceException("清理后的媒体内容超过字节上限");
        contentType = detectMediaType(inspection.bytes(),List.of());
        boolean inline = contentType.startsWith("image/") && booleanValue(policy.get("inline"), true);
        String extension = switch (contentType)
        {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
        String token = mediaRef == null ? "media" : mediaRef.replaceAll("[^A-Za-z0-9_-]", "");
        if (token.isBlank()) token = "media";
        token = token.substring(0, Math.min(token.length(), 16));
        return new MediaPayload(inspection.bytes(), contentType, inline,
                "media-" + token + "." + extension,
                inspection.width(), inspection.height(), inspection.frameCount());
    }

    private byte[] sanitizePdf(byte[] payload)
    {
        try(org.apache.pdfbox.pdmodel.PDDocument source=org.apache.pdfbox.Loader.loadPDF(payload);
                org.apache.pdfbox.pdmodel.PDDocument safe=new org.apache.pdfbox.pdmodel.PDDocument()) {
            if(source.isEncrypted()||source.getNumberOfPages()<1||source.getNumberOfPages()>100) throw new ServiceException("PDF 加密或页数超限");
            for(org.apache.pdfbox.pdmodel.PDPage page:source.getPages()) {
                org.apache.pdfbox.pdmodel.PDPage copy=safe.importPage(page);
                copy.setAnnotations(List.of()); copy.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.AA);
                copy.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.METADATA);
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();safe.save(output);return output.toByteArray();
        } catch(ServiceException ex) {throw ex;}
        catch(Exception ex) {throw new ServiceException("PDF 无法安全解析");}
    }

    private MediaInspection inspectImage(byte[] payload, String contentType, Map<String, Object> policy)
    {
        int maxWidth = Math.max(1, Math.min(integer(policy.get("maxWidth"), 8192), 32768));
        int maxHeight = Math.max(1, Math.min(integer(policy.get("maxHeight"), 8192), 32768));
        long maxPixels = Math.max(1, Math.min(integer(policy.get("maxPixels"), 12_000_000), 25_000_000));
        int maxFrames = Math.max(1, Math.min(integer(policy.get("maxAnimationFrames"), 1), 100));
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(payload)))
        {
            if (input == null) throw new ServiceException("媒体图片无法安全解码");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ServiceException("媒体图片格式缺少安全解码器");
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(input, false, true);
                if (!readerMatchesContentType(reader, contentType))
                    throw new ServiceException("媒体文件头与解码格式不一致");
                int frames = reader.getNumImages(true);
                if (frames < 1 || frames > maxFrames)
                    throw new ServiceException("媒体动画帧数超过上限");
                long totalPixels = 0;
                int widest = 0;
                int tallest = 0;
                for (int index = 0; index < frames; index++)
                {
                    int width = reader.getWidth(index);
                    int height = reader.getHeight(index);
                    if (width < 1 || height < 1 || width > maxWidth || height > maxHeight)
                        throw new ServiceException("媒体图片尺寸超过上限");
                    long pixels = (long) width * height;
                    if (pixels > maxPixels - totalPixels)
                        throw new ServiceException("媒体图片总像素超过上限");
                    totalPixels += pixels;
                    widest = Math.max(widest, width);
                    tallest = Math.max(tallest, height);
                    BufferedImage decoded = reader.read(index);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
                        throw new ServiceException("媒体图片解码结果不完整");
                    decoded.flush();
                }
                byte[] safeBytes = payload;
                if (booleanValue(policy.get("stripMetadata"), true))
                {
                    if ("image/jpeg".equals(contentType)) safeBytes = stripJpegMetadata(payload);
                    else if ("image/png".equals(contentType)) safeBytes = stripPngMetadata(payload);
                    else {
                        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                        if("image/webp".equals(contentType)) {
                            if(frames!=1) throw new ServiceException("动画 WebP 不支持安全转码");
                            BufferedImage frame=reader.read(0); ImageIO.write(frame,"png",buffer); frame.flush();
                        } else {
                            javax.imageio.ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
                            try(javax.imageio.stream.ImageOutputStream output=ImageIO.createImageOutputStream(buffer)) {
                                writer.setOutput(output);writer.prepareWriteSequence(null);
                                for(int i=0;i<frames;i++) {
                                    BufferedImage frame=reader.read(i);
                                    javax.imageio.metadata.IIOMetadata metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame),writer.getDefaultWriteParam());
                                    org.w3c.dom.Node tree=reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                                    for(org.w3c.dom.Node node=tree.getFirstChild();node!=null;node=node.getNextSibling())
                                        if("GraphicControlExtension".equals(node.getNodeName())) {
                                            javax.imageio.metadata.IIOMetadataNode target=new javax.imageio.metadata.IIOMetadataNode("javax_imageio_gif_image_1.0");
                                            javax.imageio.metadata.IIOMetadataNode control=new javax.imageio.metadata.IIOMetadataNode("GraphicControlExtension");
                                            for(String attribute:List.of("disposalMethod","userInputFlag","transparentColorFlag","delayTime","transparentColorIndex")) {
                                                org.w3c.dom.Node attr=node.getAttributes().getNamedItem(attribute);if(attr!=null) control.setAttribute(attribute,attr.getNodeValue());
                                            }
                                            target.appendChild(control);metadata.mergeTree("javax_imageio_gif_image_1.0",target);
                                        }
                                    writer.writeToSequence(new javax.imageio.IIOImage(frame,null,metadata),writer.getDefaultWriteParam());frame.flush();
                                }
                                writer.endWriteSequence();
                            } finally {writer.dispose();}
                        }
                        safeBytes=buffer.toByteArray();
                    }
                }
                return new MediaInspection(safeBytes, widest, tallest, frames);
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (ServiceException ex) { throw ex; }
        catch (IOException | RuntimeException ex)
        {
            throw new ServiceException("媒体图片无法安全解码");
        }
    }

    private boolean readerMatchesContentType(ImageReader reader, String contentType) throws IOException
    {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        return switch (contentType)
        {
            case "image/png" -> "png".equals(format);
            case "image/jpeg" -> "jpeg".equals(format) || "jpg".equals(format);
            case "image/gif" -> "gif".equals(format);
            case "image/webp" -> "webp".equals(format);
            default -> false;
        };
    }

    private byte[] stripJpegMetadata(byte[] payload)
    {
        int end = -1;
        for (int index = payload.length - 2; index >= 2; index--)
        {
            if ((payload[index] & 0xff) == 0xff && (payload[index + 1] & 0xff) == 0xd9)
            {
                end = index + 2;
                break;
            }
        }
        if (end < 4) throw new ServiceException("JPEG 结束标记不合法");
        ByteArrayOutputStream output = new ByteArrayOutputStream(end);
        output.write(payload, 0, 2);
        int position = 2;
        while (position < end)
        {
            if ((payload[position] & 0xff) != 0xff)
                throw new ServiceException("JPEG 段结构不合法");
            int markerStart = position;
            while (position < end && (payload[position] & 0xff) == 0xff) position++;
            if (position >= end) throw new ServiceException("JPEG 标记不完整");
            int marker = payload[position] & 0xff;
            if (marker == 0xda)
            {
                output.write(payload, markerStart, end - markerStart);
                return output.toByteArray();
            }
            if (marker == 0xd8 || marker == 0xd9 || marker == 0x01
                    || marker >= 0xd0 && marker <= 0xd7)
            {
                output.write(payload, markerStart, position + 1 - markerStart);
                position++;
                continue;
            }
            if (position + 2 >= end) throw new ServiceException("JPEG 段长度不完整");
            int length = (payload[position + 1] & 0xff) << 8 | payload[position + 2] & 0xff;
            int segmentEnd = position + 1 + length;
            if (length < 2 || segmentEnd > end) throw new ServiceException("JPEG 段长度不合法");
            if (marker != 0xe1 && marker != 0xed && marker != 0xfe)
                output.write(payload, markerStart, segmentEnd - markerStart);
            position = segmentEnd;
        }
        throw new ServiceException("JPEG 扫描数据缺失");
    }

    private byte[] stripPngMetadata(byte[] payload)
    {
        Set<String> removed = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length);
        output.write(payload, 0, 8);
        int position = 8;
        boolean ended = false;
        while (position < payload.length)
        {
            if (position > payload.length - 12) throw new ServiceException("PNG 数据块不完整");
            long length = (long) (payload[position] & 0xff) << 24
                    | (long) (payload[position + 1] & 0xff) << 16
                    | (long) (payload[position + 2] & 0xff) << 8
                    | payload[position + 3] & 0xffL;
            if (length > Integer.MAX_VALUE || length > payload.length - position - 12L)
                throw new ServiceException("PNG 数据块长度不合法");
            int chunkLength = (int) length + 12;
            String type = new String(payload, position + 4, 4, StandardCharsets.US_ASCII);
            if (!removed.contains(type)) output.write(payload, position, chunkLength);
            position += chunkLength;
            if ("IEND".equals(type))
            {
                ended = true;
                break;
            }
        }
        if (!ended || position != payload.length) throw new ServiceException("PNG 结束数据不合法");
        return output.toByteArray();
    }

    private String detectMediaType(byte[] payload, List<String> allowed)
    {
        if (payload == null || payload.length == 0) throw new ServiceException("媒体内容为空");
        String detected;
        if (startsWith(payload, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))
            detected = "image/png";
        else if (startsWith(payload, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
            detected = "image/jpeg";
        else if (startsWith(payload, new byte[] {0x47, 0x49, 0x46, 0x38}))
            detected = "image/gif";
        else if (startsWith(payload, new byte[] {0x25, 0x50, 0x44, 0x46}))
            detected = "application/pdf";
        else if (payload.length >= 12 && payload[0] == 'R' && payload[1] == 'I'
                && payload[2] == 'F' && payload[3] == 'F'
                && payload[8] == 'W' && payload[9] == 'E'
                && payload[10] == 'B' && payload[11] == 'P')
            detected = "image/webp";
        else throw new ServiceException("媒体文件头不在允许范围");
        if (!allowed.isEmpty() && allowed.stream().noneMatch(detected::equalsIgnoreCase))
            throw new ServiceException("媒体类型不在允许范围");
        return detected;
    }

    private boolean startsWith(byte[] value, byte[] prefix)
    {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private List<String> safeAllowedMimeTypes(Map<String, Object> config)
    {
        Map<String, Object> policy = mediaPolicy(config);
        List<String> result = stringList(policy.get("allowedMimeTypes"), 16, 80);
        if (result.isEmpty()) return List.of("image/png", "image/jpeg");
        List<String> normalized = new ArrayList<>();
        for (String mime : result)
        {
            String value = mime.toLowerCase(Locale.ROOT);
            if (SUPPORTED_MEDIA_TYPES.contains(value) && !normalized.contains(value)) normalized.add(value);
        }
        return normalized;
    }

    /* ----------------------------- 通用校验和转换 ----------------------------- */

    private String itemPayloadHash(JsonNode item)
    {
        String canonical = json(canonicalJsonValue(item));
        return DashboardIntegrationSecurity.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private Object canonicalJsonValue(JsonNode node)
    {
        if (node == null || node.isNull()) return null;
        if (node.isObject())
        {
            Map<String, Object> result = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), canonicalJsonValue(entry.getValue())));
            return result;
        }
        if (node.isArray())
        {
            List<Object> result = new ArrayList<>();
            for (JsonNode child : node) result.add(canonicalJsonValue(child));
            return result;
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private boolean sameJson(String left, String right)
    {
        JsonNode leftNode = readTreeQuiet(left);
        JsonNode rightNode = readTreeQuiet(right);
        return leftNode != null && rightNode != null
                && java.util.Objects.equals(canonicalJsonValue(leftNode), canonicalJsonValue(rightNode));
    }

    private boolean sameTimestampSecond(Timestamp left, Timestamp right)
    {
        if (left == null || right == null) return left == null && right == null;
        return left.toInstant().getEpochSecond() == right.toInstant().getEpochSecond();
    }

    private String inboundExternal(JsonNode object, String field, String code)
    {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isValueNode())
            throw new InboundReject(400, code, field + " 不合法", 0);
        return inboundExternal(value.asText(), field, code);
    }

    private String inboundExternal(String raw, String field, String code)
    {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        if (!DashboardIntegrationSecurity.isSafeExternalValue(value))
            throw new InboundReject(400, code, field + " 不合法", 0);
        return value;
    }

    private String itemExternal(JsonNode item, String field)
    {
        JsonNode value = item == null ? null : item.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isValueNode()) throw new IllegalArgumentException(field + " 不合法");
        String normalized = value.asText().trim();
        if (!normalized.isBlank() && !DashboardIntegrationSecurity.isSafeExternalValue(normalized))
            throw new IllegalArgumentException(field + " 不合法");
        return normalized;
    }

    private String text(Object value)
    {
        return text(value, true);
    }

    private String text(Object value, boolean trim)
    {
        if (value == null) return "";
        if (value instanceof String string) return trim ? string.trim() : string;
        if (value instanceof JsonNode node) return node.isValueNode() ? node.asText() : node.toString();
        return String.valueOf(value);
    }

    private String requireCode(Object value, String name)
    {
        String code = text(value);
        if (!SAFE_CODE.matcher(code).matches()) throw new ServiceException(name + "格式不合法");
        return code;
    }

    private String requireExternal(Object value, String name)
    {
        String result = text(value);
        if (!DashboardIntegrationSecurity.isSafeExternalValue(result))
            throw new ServiceException(name + "不合法");
        return result;
    }

    private String safeOptionalExternal(Object value)
    {
        String result = text(value);
        if (result.isBlank()) return "";
        return DashboardIntegrationSecurity.isSafeExternalValue(result) ? result : "";
    }

    private String nullableExternal(String value)
    {
        return value == null || value.isBlank() ? null : limitText(value, 256);
    }

    private String scalarText(Object value, String name)
    {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof Collection<?>)
            throw new ServiceException(name + "必须是标量值");
        String result = text(value, false);
        if (result.length() > 512 || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0)
            throw new ServiceException(name + "值不合法");
        return result;
    }

    private int integer(Object value, int fallback)
    {
        if (value == null || text(value).isBlank()) return fallback;
        try { return Integer.parseInt(text(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private int boundedInt(Object value, int min, int max, int fallback, String name)
    {
        int result;
        try { result = value == null || text(value).isBlank() ? fallback : Integer.parseInt(text(value)); }
        catch (NumberFormatException ex) { throw new ServiceException(name + "必须是整数"); }
        if (result < min || result > max) throw new ServiceException(name + "超出允许范围");
        return result;
    }

    private void validateBoundedInt(Map<?, ?> map, String name, int min, int max, int fallback)
    {
        if (!map.containsKey(name)) return;
        Object value = map.get(name);
        if (value == null) return;
        int parsed = boundedInt(value, min, max, fallback, name);
        if (parsed < min || parsed > max) throw new ServiceException(name + "超出允许范围");
    }

    private void validateBoolean(Map<?, ?> map, String name)
    {
        if (map.containsKey(name) && map.get(name) != null && !(map.get(name) instanceof Boolean))
            throw new ServiceException(name + "必须是布尔值");
    }

    private void validateJsonPath(Object value, String name)
    {
        String path = text(value);
        if (path.length() > 128 || (!path.isBlank() && ArraysSupport.allJsonPathParts(path) == false))
            throw new ServiceException(name + "不合法");
    }

    private void validateHeaders(Object value)
    {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> map)) throw new ServiceException("请求头映射必须是对象");
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            String name = text(entry.getKey());
            if (!SAFE_HEADER.matcher(name).matches() || isForbiddenHeader(name)
                    || scalarText(entry.getValue(), name).length() > 256)
                throw new ServiceException("请求头映射不合法");
        }
    }

    private void validateStringArray(Object value, String name, int maxItems, int maxLength)
    {
        if (value == null) return;
        if (!(value instanceof Collection<?> collection))
            throw new ServiceException(name + "必须是数组");
        if (collection.size() > maxItems) throw new ServiceException(name + "数量超限");
        for (Object item : collection)
        {
            String text = scalarText(item, name);
            if (text.isBlank() || text.length() > maxLength || !DashboardIntegrationSecurity.isSafeExternalValue(text))
                throw new ServiceException(name + "包含不合法值");
        }
    }

    private List<String> stringList(Object value, int maxItems, int maxLength)
    {
        if (value == null) return new ArrayList<>();
        Object source = value;
        if (value instanceof String string)
        {
            if (string.isBlank()) return new ArrayList<>();
            JsonNode node = readTreeQuiet(string);
            if (node != null && node.isArray()) source = objectMapper.convertValue(node, Object.class);
            else return splitList(string);
        }
        if (!(source instanceof Collection<?> collection)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : collection)
        {
            String text = scalarText(item, "数组值");
            if (text.isBlank() || text.length() > maxLength) continue;
            if (result.size() >= maxItems) break;
            result.add(text);
        }
        return result;
    }

    private List<String> splitList(String value)
    {
        List<String> result = new ArrayList<>();
        for (String item : text(value).split(","))
            if (!item.isBlank() && result.size() < 100) result.add(item.trim());
        return result;
    }

    private List<Integer> integerList(Object value, int maxItems)
    {
        List<Integer> result = new ArrayList<>();
        if (value instanceof Collection<?> collection)
        {
            for (Object item : collection)
            {
                try
                {
                    int number = Integer.parseInt(text(item));
                    if (number > 0 && number <= 65535 && result.size() < maxItems) result.add(number);
                }
                catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }

    private Map<String, String> stringMap(Object value)
    {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> {
                String name = text(key);
                String val = text(item);
                if (SAFE_HEADER.matcher(name).matches() && !isForbiddenHeader(name)
                        && val.length() <= 128) result.put(name, val);
            });
        return result;
    }

    private Map<String, Object> castMap(Map<?, ?> source)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String json(Object value)
    {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ServiceException("JSON 序列化失败"); }
    }

    private String jsonString(Object value, String fallback)
    {
        if (value == null) return fallback;
        if (value instanceof String string) return string.isBlank() ? fallback : string;
        return json(value);
    }

    private Map<String, Object> parseObject(String raw, String label)
    {
        JsonNode node = readTreeQuiet(raw);
        if (node == null || !node.isObject()) throw new ServiceException(label + "必须是 JSON 对象");
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> parseObjectQuiet(String raw)
    {
        JsonNode node = readTreeQuiet(raw);
        return node != null && node.isObject()
                ? objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {})
                : new LinkedHashMap<>();
    }

    private JsonNode readTreeQuiet(String raw)
    {
        if (raw == null || raw.isBlank()) return null;
        try { return objectMapper.readTree(raw); }
        catch (JsonProcessingException ex) { return null; }
    }

    private List<String> parseArrayQuiet(String raw)
    {
        JsonNode node = readTreeQuiet(raw);
        if (node == null || !node.isArray()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node)
        {
            String value = item.asText("");
            if (!value.isBlank() && result.size() < 100) result.add(value);
        }
        return result;
    }

    private String textNode(JsonNode root, String path)
    {
        JsonNode value = atPathStrict(root, path);
        return value == null || !value.isValueNode() ? "" : limitText(value.asText(""), 200);
    }

    private String normalizeDateTime(String value, String timezone)
    {
        try
        {
            Instant instant = parseInstant(value, ZoneId.of(timezone == null || timezone.isBlank()
                    ? "Asia/Shanghai" : timezone));
            return LOCAL_DATETIME.format(ZonedDateTime.ofInstant(instant,
                    ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone)));
        }
        catch (RuntimeException ex) { throw new OutboundFailure("INVALID_DATA", "时间字段格式不合法", false, 0); }
    }

    private Instant parseInstant(String value, ZoneId zone)
    {
        String text = value == null ? "" : value.trim();
        try { return Instant.parse(text); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (DateTimeParseException ignored) { }
        LocalDateTime local;
        try { local=LocalDateTime.parse(text,LOCAL_DATETIME); }
        catch(DateTimeParseException ignored) {local=LocalDateTime.parse(text);}
        List<java.time.ZoneOffset> offsets=zone.getRules().getValidOffsets(local);
        if(offsets.size()!=1) throw new IllegalArgumentException("业务时间存在夏令时歧义，请提供 UTC 偏移");
        return local.toInstant(offsets.get(0));
    }

    private String limitText(String value, int max)
    {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean booleanValue(Object value, boolean fallback)
    {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(text(value))) return true;
        if ("false".equalsIgnoreCase(text(value))) return false;
        return fallback;
    }

    private boolean booleanConfig(Map<String, Object> integration, String name, boolean fallback)
    {
        return booleanValue(integrationConfigMap(integration).get(name), fallback);
    }

    private int integrationConfigInt(Map<String, Object> integration, String name, int fallback)
    {
        return integer(integrationConfigMap(integration).get(name), fallback);
    }

    private String integrationConfig(Map<String, Object> integration, String name, String fallback)
    {
        String value = text(integrationConfigMap(integration).get(name));
        return value.isBlank() ? fallback : value;
    }

    private Map<String, Object> integrationConfigMap(Map<String, Object> integration)
    {
        return parseObjectQuiet(text(integration.get("configJson")));
    }

    private boolean booleanProperty(String name, boolean fallback)
    {
        String value = environment == null ? "" : environment.getProperty(name, "");
        return value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private boolean isForbiddenHeader(String name)
    {
        String lower = text(name).toLowerCase(Locale.ROOT);
        return Set.of("host", "content-length", "connection", "transfer-encoding", "proxy-authorization",
                "proxy-authenticate", "upgrade", "authorization", "cookie", "set-cookie").contains(lower)
                || lower.startsWith("sec-");
    }

    private void putSafeHeader(HttpRequest.Builder builder, String name, String value)
    {
        if (!SAFE_HEADER.matcher(name).matches() || isForbiddenHeader(name))
            throw new ServiceException("请求头不受支持");
        builder.header(name, value);
    }

    private TimestampValue timestampValue(Object value)
    {
        Instant instant = instantValue(value);
        return instant == null ? null : new TimestampValue(java.sql.Timestamp.from(instant));
    }

    private java.sql.Timestamp timestampFromValue(Object value)
    {
        Instant instant = instantValue(value);
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private Instant instantValue(Object value)
    {
        if (value == null) return null;
        if (value instanceof java.util.Date date) return date.toInstant();
        String valueText = text(value);
        if (valueText.isBlank()) return null;
        try { return Instant.parse(valueText); }
        catch (RuntimeException ignored) { }
        try { return OffsetDateTime.parse(valueText).toInstant(); }
        catch (RuntimeException ignored) { }
        try { return LocalDateTime.parse(valueText).atZone(ZoneId.of("Asia/Shanghai")).toInstant(); }
        catch (RuntimeException ignored) { return null; }
    }

    private Long toLong(Object value)
    {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(text(value)); } catch (NumberFormatException ex) { return null; }
    }

    private long longValue(Object value, long fallback)
    {
        Long result = toLong(value);
        return result == null ? fallback : result;
    }

    private java.sql.Timestamp parseOptionalTimestamp(Object value)
    {
        if (value == null || text(value).isBlank()) return null;
        Instant instant = instantValue(value);
        if (instant == null) throw new ServiceException("有效期时间格式不合法");
        return java.sql.Timestamp.from(instant);
    }

    private List<Map<String, Object>> parseParamSchema(Object value)
    {
        JsonNode node;
        if (value instanceof String string) node = readTreeQuiet(string);
        else if (value == null) node = null;
        else node = objectMapper.valueToTree(value);
        if (node == null || node.isNull()) return new ArrayList<>();
        if (!node.isArray()) throw new ServiceException("参数定义必须是 JSON 数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : node)
        {
            if (!item.isObject()) throw new ServiceException("参数定义必须是对象");
            result.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
        }
        return result;
    }

    private String normalizeStringArrayJson(Object value, String name, int maxItems, int maxLength)
    {
        if (value == null || text(value).isBlank()) return "[]";
        Object actual = value;
        if (value instanceof String string)
        {
            JsonNode node = readTreeQuiet(string);
            if (node == null || !node.isArray()) throw new ServiceException(name + "必须是 JSON 数组");
            actual = objectMapper.convertValue(node, Object.class);
        }
        validateStringArray(actual, name, maxItems, maxLength);
        return json(actual);
    }

    private boolean isAllowedIp(Map<String, Object> integration, String remoteAddress)
    {
        List<String> allowed = parseArrayQuiet(text(integration.get("allowedIpJson")));
        if (allowed.isEmpty()) return true;
        if (remoteAddress == null || remoteAddress.isBlank()) return false;
        String normalized = remoteAddress.trim();
        for (String candidate : allowed)
        {
            if (candidate.equalsIgnoreCase(normalized)) return true;
            if (matchesCidr(normalized, candidate)) return true;
        }
        return false;
    }

    private boolean matchesCidr(String address, String cidr)
    {
        try
        {
            String[] parts = cidr.split("/", 2);
            java.net.InetAddress actual = java.net.InetAddress.getByName(address);
            java.net.InetAddress network = java.net.InetAddress.getByName(parts[0]);
            byte[] actualBytes = actual.getAddress();
            byte[] networkBytes = network.getAddress();
            if (actualBytes.length != networkBytes.length) return false;
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : actualBytes.length * 8;
            if (prefix < 0 || prefix > actualBytes.length * 8) return false;
            int full = prefix / 8;
            int remaining = prefix % 8;
            for (int i = 0; i < full; i++) if (actualBytes[i] != networkBytes[i]) return false;
            if (remaining == 0) return true;
            int mask = 0xff << (8 - remaining);
            return (actualBytes[full] & mask) == (networkBytes[full] & mask);
        }
        catch (RuntimeException | java.net.UnknownHostException ex) { return false; }
    }

    private boolean allowedOrigin(Map<String, Object> integration, String origin)
    {
        if (origin == null || origin.isBlank()) return true;
        List<String> allowed = stringList(integrationConfigMap(integration).get("allowedOrigins"), 32, 255);
        return !allowed.isEmpty() && allowed.stream().anyMatch(item -> item.equals(origin));
    }

    private boolean keyInValidityWindow(Map<String, Object> key)
    {
        if (!"ACTIVE".equalsIgnoreCase(text(key.get("status")))) return false;
        Instant now = Instant.now();
        Instant from = instantValue(key.get("validFrom"));
        Instant to = instantValue(key.get("validTo"));
        return (from == null || !now.isBefore(from)) && (to == null || now.isBefore(to));
    }

    private String hexHmac(String secret, String canonical)
    {
        String base64 = DashboardIntegrationSecurity.hmacSha256Base64(secret, canonical);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException ex) { return ""; }
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private String header(Map<String, String> headers, String name)
    {
        if (headers == null || name == null) return "";
        String normalized = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet())
            if (entry.getKey() != null && normalized.equals(entry.getKey().toLowerCase(Locale.ROOT)))
                return text(entry.getValue(), false);
        return "";
    }

    private record InboundEnvelope(String profile, String schemaVersion, String messageId,
            String projectCode, String tenantCode, List<JsonNode> items, String retryOf,
            String externalRequestId) { }

    private record ValidatedItem(JsonNode item, String businessKey, String projectCode,
            java.sql.Timestamp businessTime, String normalizedJson, boolean valid,
            String errorCode, String errorMessage) { }

    private record AuthContext(Map<String, Object> key, String provider) { }

    public record InboundResponse(int httpStatus, Map<String, Object> body, long retryAfterSeconds) { }

    public record MediaPayload(byte[] bytes, String contentType, boolean inline, String filename,
            int width, int height, int frameCount) { }

    public record MediaBinding(String mediaRef, Long pageId, Long revisionId, Long shareId,
            Instant expiresAt) { }

    private record CachedMedia(MediaPayload payload, Instant expiresAt) { }

    private record MediaInspection(byte[] bytes, int width, int height, int frameCount) { }

    private static final class WindowCounter
    {
        private long windowStart;
        private int count;

        synchronized boolean tryAcquire(int limit)
        {
            long now = System.currentTimeMillis();
            if (windowStart == 0 || now - windowStart >= 1000)
            {
                windowStart = now;
                count = 0;
            }
            if (count >= Math.max(1, limit)) return false;
            count++;
            return true;
        }
    }

    private static final class InboundReject extends RuntimeException
    {
        private final int httpStatus;
        private final String code;
        private final long retryAfterSeconds;

        private InboundReject(int httpStatus, String code, String message, long retryAfterSeconds)
        {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        int httpStatus() { return httpStatus; }
        String code() { return code; }
        long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private static final class ConflictRecordException extends RuntimeException
    {
        private ConflictRecordException() { super("记录版本冲突"); }
    }

    private static final class TimestampValue
    {
        private final java.sql.Timestamp value;

        private TimestampValue(java.sql.Timestamp value) { this.value = value; }
        static java.sql.Timestamp now() { return java.sql.Timestamp.from(Instant.now()); }
        static java.sql.Timestamp afterSeconds(long seconds)
        {
            return java.sql.Timestamp.from(Instant.now().plusSeconds(Math.max(1, seconds)));
        }
        static java.sql.Timestamp afterDays(int days)
        {
            return java.sql.Timestamp.from(Instant.now().plusSeconds(Math.max(0, days) * 86400L));
        }
        java.sql.Timestamp value() { return value; }
    }

    private static final class ArraysSupport
    {
        private static boolean allJsonPathParts(String path)
        {
            if (path == null || path.isBlank()) return true;
            for (String part : path.split("\\."))
                if (!JSON_PATH_PART.matcher(part).matches()) return false;
            return true;
        }
    }
}
