package com.ruoyi.system.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationRetentionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 删除正文和标准记录时仍保留业务去重依据，禁止借外键级联清理消息。 */
@Service
public class DashboardIntegrationRetentionService
{
    static final int PAGE_SIZE = 20;
    static final int CLEANUP_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(DashboardIntegrationRetentionService.class);
    private final DashboardIntegrationRetentionMapper mapper;
    private final DashboardIntegrationOperations operations;
    private final Environment environment;
    private final ObjectMapper json = new ObjectMapper();
    private Clock clock = Clock.systemUTC();
    private long afterId;
    private Instant nextSweep = Instant.MIN;

    public DashboardIntegrationRetentionService(DashboardIntegrationRetentionMapper mapper,
            DashboardIntegrationOperations operations, Environment environment)
    {
        this.mapper = mapper;
        this.operations = operations;
        this.environment = environment;
    }

    /** 每轮至多20个接入方；一轮未扫完时由下次维护继续，不饿死后面的接入方。 */
    public synchronized void maintain()
    {
        Instant now = clock.instant();
        if (now.isBefore(nextSweep)) return;
        List<Map<String, Object>> policies = mapper.selectPolicies(afterId, PAGE_SIZE);
        for (Map<String, Object> integration : policies)
        {
            long id = ((Number) integration.get("integrationId")).longValue();
            try
            {
                CleanupResult result = cleanupIntegration(integration, now);
                if (result != null && result.total() > 0)
                {
                    operations.audit("INBOUND_RETENTION", String.valueOf(integration.get("integrationCode")),
                            UUID.randomUUID().toString(), "CLEANED", "system", 0, 0);
                    log.info("推送接入保留期清理完成，接入方={}，原文={}，处理正文={}，回执摘要={}，过期重复批次={}，业务记录={}",
                            id, result.rawBodies(), result.messages(), result.receipts(), result.batches(), result.records());
                }
            }
            catch (RuntimeException ex)
            {
                // 不输出异常正文，避免 JDBC/配置异常夹带连接参数；失败事务回滚，下一轮重试。
                log.warn("推送接入保留期清理未完成，接入方={}，请检查数据库迁移和维护配置", id);
            }
            afterId = id;
        }
        if (policies.size() < PAGE_SIZE)
        {
            afterId = 0;
            int interval = environment.getProperty("dashboard.integration.retention-cleanup-interval-seconds", Integer.class, 300);
            nextSweep = now.plusSeconds(Math.max(60, Math.min(interval, 86400)));
        }
    }

    CleanupResult cleanupIntegration(Map<String, Object> integration, Instant now)
    {
        Map<String, Object> policy;
        try { policy = json.readValue(String.valueOf(integration.getOrDefault("configJson", "{}")), new TypeReference<>() { }); }
        catch (Exception ex) { throw new ServiceException("接入方保留策略不合法"); }
        int successDays = days(policy, "successRetentionDays");
        int recordDays = days(policy, "recordRetentionDays");
        Long id = ((Number) integration.get("integrationId")).longValue();
        return operations.withLease("consume:" + id, 120, () -> {
            Timestamp timestamp = Timestamp.from(now);
            // 与消费共用事务租约；失败、死信和未完成批次不在候选查询中。
            List<Long> rawBatchIds = mapper.selectExpiredRawBatchIds(id, timestamp, CLEANUP_LIMIT);
            int raw = rawBatchIds.isEmpty() ? 0 : mapper.clearRawBatches(id, rawBatchIds);
            List<Long> rawMessageIds = mapper.selectExpiredRawMessageIds(id, timestamp, CLEANUP_LIMIT);
            raw += rawMessageIds.isEmpty() ? 0 : mapper.clearRawMessages(id, rawMessageIds);
            int messages = 0, receipts = 0, batches = 0, records = 0;
            if (successDays > 0)
            {
                Timestamp cutoff = Timestamp.from(now.minus(successDays, ChronoUnit.DAYS));
                List<Long> ids = mapper.selectExpiredSuccessMessageIds(id, cutoff, CLEANUP_LIMIT);
                messages = ids.isEmpty() ? 0 : mapper.clearSuccessMessages(id, ids);
                for (Map<String, Object> batch : mapper.selectExpiredSuccessBatches(id, cutoff, CLEANUP_LIMIT))
                {
                    Long batchId = ((Number) batch.get("batchId")).longValue();
                    if (mapper.deleteEmptySuccessBatch(id, batchId) > 0) batches++;
                    else receipts += mapper.compactSuccessBatch(id, batchId,
                            summaryReceipt(batch, String.valueOf(integration.get("integrationCode"))), timestamp);
                }
            }
            if (recordDays > 0)
            {
                List<Long> ids = mapper.selectExpiredRecordIds(id,
                        Timestamp.from(now.minus(recordDays, ChronoUnit.DAYS)), CLEANUP_LIMIT);
                records = ids.isEmpty() ? 0 : mapper.deleteExpiredRecords(id, ids);
                if (records > 0) operations.signal(id);
            }
            return new CleanupResult(raw, messages, receipts, batches, records);
        });
    }

    /** 缺项兼容旧配置；显式错误不能退回“长期保留”掩盖输入错误。 */
    static int days(Map<String, Object> config, String key)
    {
        if (!config.containsKey(key)) return 0;
        Object value = config.get(key);
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 3650)
            throw new ServiceException(("successRetentionDays".equals(key) ? "成功明细" : "业务记录") + "保留天数必须是0到3650的整数");
        return ((Number) value).intValue();
    }

    String summaryReceipt(Map<String, Object> batch, String integrationCode)
    {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("code", "ACCEPTED");
        receipt.put("message", "已接收，成功明细已到期，保留回执摘要");
        receipt.put("requestId", batch.get("platformRequestId"));
        if (batch.get("messageId") != null) receipt.put("messageId", batch.get("messageId"));
        receipt.put("integrationCode", integrationCode);
        for (String[] field : List.of(new String[]{"accepted", "acceptedCount"}, new String[]{"processing", "processingCount"},
                new String[]{"duplicates", "duplicateCount"}, new String[]{"rejected", "rejectedCount"}, new String[]{"retryable", "retryableCount"}))
            receipt.put(field[0], batch.getOrDefault(field[1], 0));
        receipt.put("items", List.of());
        receipt.put("detailExpired", true);
        try { return json.writeValueAsString(receipt); }
        catch (Exception ex) { throw new ServiceException("回执摘要生成失败"); }
    }

    record CleanupResult(int rawBodies, int messages, int receipts, int batches, int records)
    {
        int total() { return rawBodies + messages + receipts + batches + records; }
    }
}
