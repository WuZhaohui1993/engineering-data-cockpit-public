package com.ruoyi.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationPolicyMapper;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公共运维记录保留。业务记录和 HTTP 缓存继续由接入方/接口各自配置。 */
@Service
public class DashboardIntegrationPolicyService
{
    private static final List<String> FIELDS = List.of("auditRetentionDays", "resolvedAlertRetentionDays", "testLogRetentionDays");
    private static final int CLEANUP_LIMIT = 1000;
    @Autowired private DashboardIntegrationPolicyMapper mapper;
    @Autowired private Environment environment;
    private final ObjectMapper json = new ObjectMapper();

    public Map<String, Object> getPolicy()
    {
        Map<String, Object> stored = mapper.selectPolicy();
        Map<String, Object> config = Map.of();
        if (stored != null)
        {
            try
            {
                config = json.readValue(String.valueOf(stored.get("configJson")), new TypeReference<>() { });
                if (config == null) throw new IllegalArgumentException();
            }
            catch (Exception ex) { throw new ServiceException("公共保留策略无法读取，请核对配置"); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int auditDefault = Math.max(1, Math.min(3650,
                environment.getProperty("dashboard.integration.audit-retention-days", Integer.class, 30)));
        for (String field : FIELDS)
            result.put(field, days(config.getOrDefault(field, field.equals("auditRetentionDays") ? auditDefault : 30), field));
        // 正文不进入系统操作日志；原始报文只按接入方的独立授权策略加密保存。
        result.put("testResponseBodyStored", false);
        return result;
    }

    @Transactional
    public Map<String, Object> updatePolicy(Map<String, Object> input, String actor)
    {
        if (input == null || input.size() != FIELDS.size() || !input.keySet().containsAll(FIELDS))
            throw new ServiceException("请填写三项公共日志保留天数，不支持其他策略属性");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : FIELDS) result.put(field, days(input.get(field), field));
        try { mapper.savePolicy(json.writeValueAsString(result), actor); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ServiceException("公共保留策略无法保存"); }
        result.put("testResponseBodyStored", false);
        return result;
    }

    public int auditRetentionDays() { return (Integer) getPolicy().get("auditRetentionDays"); }

    /** 每轮独立限量，不自动清理未确认告警或无关模块的系统操作日志。 */
    public void cleanup()
    {
        Map<String, Object> policy = getPolicy();
        Instant now = Instant.now();
        mapper.deleteExpiredConfirmedAlerts(cutoff(now, (Integer) policy.get("resolvedAlertRetentionDays")), CLEANUP_LIMIT);
        mapper.deleteExpiredTestLogs(cutoff(now, (Integer) policy.get("testLogRetentionDays")), CLEANUP_LIMIT);
    }

    private Timestamp cutoff(Instant now, int days) { return Timestamp.from(now.minusSeconds(days * 86400L)); }

    static int days(Object value, String field)
    {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            throw new ServiceException(field + " 必须是 1 至 3650 的整数");
        BigInteger number = new BigInteger(value.toString());
        if (number.compareTo(BigInteger.ONE) < 0 || number.compareTo(BigInteger.valueOf(3650)) > 0)
            throw new ServiceException(field + " 必须是 1 至 3650 的整数");
        return number.intValue();
    }
}
