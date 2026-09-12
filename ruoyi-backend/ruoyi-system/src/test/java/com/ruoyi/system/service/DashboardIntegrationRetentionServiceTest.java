package com.ruoyi.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationMapper;
import com.ruoyi.system.mapper.DashboardIntegrationRetentionMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.Assert.*;

/** 保留配置、维护编排和幂等回执回归；真实 SQL 条件另由数据库集成回归验证。 */
public class DashboardIntegrationRetentionServiceTest
{
    private static final Instant NOW = Instant.parse("2026-09-10T04:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void legacyMissingDaysStayPermanentAndExplicitDaysAreStrict() throws Exception
    {
        DashboardIntegrationService inbound = new DashboardIntegrationService();
        for (String key : List.of("successRetentionDays", "recordRetentionDays"))
        {
            assertEquals(0, DashboardIntegrationRetentionService.days(Map.of(), key));
            for (Number valid : List.of(0, 1, 30L, 3650L))
            {
                assertEquals(valid.intValue(), DashboardIntegrationRetentionService.days(Map.of(key, valid), key));
                invoke(inbound, "validateIntegrationConfig", new Class<?>[]{Map.class}, Map.of(key, valid));
            }
            for (Object invalid : Arrays.asList(null, -1, 3651L, Long.MAX_VALUE, 1.0d, 1.5d,
                    new BigDecimal("30"), "30", true, List.of(30)))
            {
                Map<String, Object> config = new HashMap<>();
                config.put(key, invalid);
                try
                {
                    DashboardIntegrationRetentionService.days(config, key);
                    fail("不合法的保留期限被接受: " + invalid);
                }
                catch (ServiceException expected) { assertTrue(expected.getMessage().contains("整数")); }
                try
                {
                    invoke(inbound, "validateIntegrationConfig", new Class<?>[]{Map.class}, config);
                    fail("接入方保存入口没有校验保留期限: " + invalid);
                }
                catch (ServiceException expected) { assertTrue(expected.getMessage().contains("整数")); }
            }
        }
    }

    @Test
    public void safeConfigRoundTripKeepsRetentionWithoutInventingLegacyDefaults() throws Exception
    {
        DashboardIntegrationService inbound = new DashboardIntegrationService();
        assertEquals(Map.of(), read((String) invoke(inbound, "safeIntegrationConfig",
                new Class<?>[]{Object.class}, "{}")));
        assertEquals(Map.of("successRetentionDays", 30, "recordRetentionDays", 0),
                read((String) invoke(inbound, "safeIntegrationConfig", new Class<?>[]{Object.class},
                        "{\"successRetentionDays\":30,\"recordRetentionDays\":0}")));
    }

    @Test
    public void summaryRetainsOriginalRequestMessageAndAllCounts() throws Exception
    {
        Fixture fixture = new Fixture();
        Map<String, Object> batch = batch(12L);
        batch.put("processingCount", 2);
        batch.put("rejectedCount", 3);
        batch.put("retryableCount", 4);
        Map<String, Object> result = read(fixture.service.summaryReceipt(batch, "test-inbound"));
        assertEquals("original-request", result.get("requestId"));
        assertEquals("original-message", result.get("messageId"));
        assertEquals("test-inbound", result.get("integrationCode"));
        assertEquals("ACCEPTED", result.get("code"));
        assertEquals(7, result.get("accepted"));
        assertEquals(2, result.get("processing"));
        assertEquals(93, result.get("duplicates"));
        assertEquals(3, result.get("rejected"));
        assertEquals(4, result.get("retryable"));
        assertEquals(true, result.get("detailExpired"));
        assertEquals(List.of(), result.get("items"));
        assertFalse(result.containsKey("responseJson"));
        batch.remove("messageId");
        assertFalse(read(fixture.service.summaryReceipt(batch, "test-inbound")).containsKey("messageId"));
    }

    @Test
    public void missingOrZeroPoliciesExpireOnlyRawBodiesAndNeverBusinessRecords() throws Exception
    {
        for (String config : List.of("{}", "{\"successRetentionDays\":0,\"recordRetentionDays\":0}"))
        {
            Fixture fixture = new Fixture();
            fixture.answers.put("selectExpiredRawBatchIds", args -> List.of(10L));
            fixture.answers.put("selectExpiredRawMessageIds", args -> List.of(20L));
            DashboardIntegrationRetentionService.CleanupResult result = fixture.service.cleanupIntegration(policy(42L, config), NOW);
            assertEquals(2, result.rawBodies());
            assertEquals(0, result.messages());
            assertEquals(0, result.receipts());
            assertEquals(0, result.batches());
            assertEquals(0, result.records());
            assertFalse(fixture.called("selectExpiredRecordIds"));
            assertFalse(fixture.called("selectExpiredSuccessMessageIds"));
            assertEquals(List.of(), fixture.operations.signals);
            assertEquals(List.of("consume:42"), fixture.operations.leases);
        }
    }

    @Test
    public void enabledCleanupUsesBoundedCandidatesIndependentCutoffsAndRecordSignal() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.answers.put("selectExpiredRawBatchIds", args -> List.of(10L));
        fixture.answers.put("selectExpiredRawMessageIds", args -> List.of(20L));
        fixture.answers.put("selectExpiredSuccessMessageIds", args -> List.of(30L));
        fixture.answers.put("selectExpiredSuccessBatches", args -> List.of(batch(40L), batch(41L)));
        fixture.answers.put("deleteEmptySuccessBatch", args -> Long.valueOf(40L).equals(args[1]) ? 1 : 0);
        fixture.answers.put("selectExpiredRecordIds", args -> List.of(50L, 51L));
        DashboardIntegrationRetentionService.CleanupResult result = fixture.service.cleanupIntegration(
                policy(42L, "{\"successRetentionDays\":7,\"recordRetentionDays\":2}"), NOW);
        assertEquals(new DashboardIntegrationRetentionService.CleanupResult(2, 1, 1, 1, 2), result);
        assertEquals(List.of("consume:42"), fixture.operations.leases);
        assertEquals(List.of(42L), fixture.operations.signals);
        assertEquals(Timestamp.from(NOW.minusSeconds(7 * 86400L)), fixture.call("selectExpiredSuccessMessageIds").args[1]);
        assertEquals(Timestamp.from(NOW.minusSeconds(2 * 86400L)), fixture.call("selectExpiredRecordIds").args[1]);
        for (Call call : fixture.calls)
            if (call.name.startsWith("selectExpired"))
            {
                assertEquals(42L, call.args[0]);
                assertEquals(100, call.args[2]);
            }
        Call compact = fixture.call("compactSuccessBatch");
        assertEquals(41L, compact.args[1]);
        assertEquals("original-request", read((String) compact.args[2]).get("requestId"));
        assertEquals(93, read((String) compact.args[2]).get("duplicates"));
        assertEquals(List.of(50L, 51L), fixture.call("deleteExpiredRecords").args[1]);
    }

    @Test
    public void unavailableConsumeLeaseDoesNotSelectOrDeleteAnything() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.operations.grant = false;
        assertNull(fixture.service.cleanupIntegration(
                policy(42L, "{\"successRetentionDays\":1,\"recordRetentionDays\":1}"), NOW));
        assertTrue(fixture.calls.isEmpty());
        assertTrue(fixture.operations.signals.isEmpty());
    }

    @Test
    public void noDeletedRecordsDoesNotSignalDatasetChanges() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.answers.put("selectExpiredRecordIds", args -> List.of(50L));
        fixture.answers.put("deleteExpiredRecords", args -> 0);
        assertEquals(0, fixture.service.cleanupIntegration(policy(42L, "{\"recordRetentionDays\":1}"), NOW).records());
        assertTrue(fixture.operations.signals.isEmpty());
    }

    @Test
    public void malformedPolicyFailsBeforeAcquiringLeaseOrDeletingAnything() throws Exception
    {
        for (String config : List.of("broken-json", "{\"recordRetentionDays\":\"1\"}", "{\"successRetentionDays\":-1}"))
        {
            Fixture fixture = new Fixture();
            try
            {
                fixture.service.cleanupIntegration(policy(42L, config), NOW);
                fail("不合法的历史配置执行了清理");
            }
            catch (ServiceException expected)
            {
                assertTrue(fixture.calls.isEmpty());
                assertTrue(fixture.operations.leases.isEmpty());
            }
        }
    }

    @Test
    public void maintenanceContinuesBeyondFirstTwentyThenWaitsForNextSweep() throws Exception
    {
        Fixture fixture = new Fixture();
        List<Map<String, Object>> policies = new ArrayList<>();
        for (long id = 1; id <= 21; id++) policies.add(policy(id, "{}"));
        fixture.answers.put("selectPolicies", args -> policies.stream()
                .filter(row -> ((Number) row.get("integrationId")).longValue() > (long) args[0])
                .limit((int) args[1]).toList());
        fixture.time(NOW);
        fixture.service.maintain();
        assertEquals(20, fixture.operations.leases.size());
        fixture.service.maintain();
        assertEquals(21, fixture.operations.leases.size());
        assertEquals("consume:21", fixture.operations.leases.get(20));
        fixture.service.maintain();
        fixture.time(NOW.plusSeconds(299));
        fixture.service.maintain();
        assertEquals(21, fixture.operations.leases.size());
        fixture.time(NOW.plusSeconds(300));
        fixture.service.maintain();
        assertEquals(41, fixture.operations.leases.size());
        assertEquals("consume:1", fixture.operations.leases.get(21));
        List<Call> scans = fixture.calls.stream().filter(call -> "selectPolicies".equals(call.name)).toList();
        assertEquals(3, scans.size());
        assertEquals(0L, scans.get(0).args[0]);
        assertEquals(20L, scans.get(1).args[0]);
        assertEquals(0L, scans.get(2).args[0]);
        for (Call call : scans) assertEquals(20, call.args[1]);
    }

    @Test
    public void failedIntegrationDoesNotPreventLaterPoliciesBeingMaintained() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.answers.put("selectPolicies", args -> List.of(policy(1L, "{}"), policy(2L, "{}")));
        fixture.answers.put("selectExpiredRawBatchIds", args -> {
            if (Long.valueOf(1L).equals(args[0])) throw new ServiceException("模拟维护失败");
            return List.of();
        });
        fixture.time(NOW);
        fixture.service.maintain();
        assertEquals(List.of("consume:1", "consume:2"), fixture.operations.leases);
        assertTrue(fixture.calls.stream().anyMatch(call -> "selectExpiredRawMessageIds".equals(call.name)
                && Long.valueOf(2L).equals(call.args[0])));
    }

    @Test
    public void rawZeroSkipsBothBatchAndMessageCiphertextButKeepsProcessingData() throws Exception
    {
        DashboardIntegrationService inbound = new DashboardIntegrationService();
        Map<String, Object> integration = new HashMap<>(policy(42L, "{\"retainRawBody\":true}"));
        integration.put("rawRetentionDays", 0);
        assertNull(invoke(inbound, "retainedRawBody", new Class<?>[]{Map.class, byte[].class},
                integration, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Class<?> envelopeType = Class.forName(DashboardIntegrationService.class.getName() + "$InboundEnvelope");
        Class<?> itemType = Class.forName(DashboardIntegrationService.class.getName() + "$ValidatedItem");
        JsonNode body = JSON.readTree("{\"recordId\":\"goods-1\",\"data\":{\"quantity\":1}}");
        Object envelope = construct(envelopeType, "RECORD", "1.0", "batch-1", "", "", List.of(body), "", "");
        Object item = construct(itemType, body, "goods-1", "", Timestamp.from(NOW),
                "{\"data\":{\"quantity\":1}}", true, "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) invoke(inbound, "messageRow",
                new Class<?>[]{Long.class, Map.class, envelopeType, itemType, String.class, int.class,
                        String.class, String.class, String.class},
                1L, integration, envelope, item, "goods-1", 0, "RECEIVED", "", "");
        assertNull(message.get("payloadCiphertext"));
        assertNotNull(message.get("normalizedJson"));
        assertEquals(64, ((String) message.get("payloadHash")).length());
    }

    @Test
    public void lateConsumerLeavesCompactedReceiptAndDuplicateCountsUntouched() throws Exception
    {
        Fixture fixture = new Fixture();
        Map<String, Object> row = batch(12L);
        row.put("integrationId", 42L);
        row.put("status", "ACCEPTED");
        row.put("detailsExpiredAt", Timestamp.from(NOW));
        row.put("responseJson", fixture.service.summaryReceipt(row, "test-inbound"));
        Map<String, Object> original = new LinkedHashMap<>(row);
        int[] batchReads = {0};
        DashboardIntegrationMapper mapper = (DashboardIntegrationMapper) Proxy.newProxyInstance(
                DashboardIntegrationMapper.class.getClassLoader(), new Class<?>[]{DashboardIntegrationMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "selectBatchById": batchReads[0]++; return new LinkedHashMap<>(row);
                        case "selectIntegrationById":
                            assertTrue(fixture.operations.inLease);
                            return Map.of("integrationId", 42L, "status", "ACTIVE");
                        case "selectMessagesByBatch":
                            assertTrue(fixture.operations.inLease);
                            // 成功正文已清空，且93条重复项只存在于批次计数中。
                            return List.of(Map.of("integrationMessageId", 5L, "status", "ACCEPTED", "payloadHash", "retained-hash"));
                        default: throw new AssertionError("迟到消费意外读写: " + method.getName());
                    }
                });
        DashboardIntegrationService inbound = new DashboardIntegrationService();
        set(inbound, "integrationMapper", mapper);
        set(inbound, "operations", fixture.operations);
        invoke(inbound, "processBatch", new Class<?>[]{Long.class}, 12L);
        assertEquals(2, batchReads[0]);
        assertEquals(original, row);
        assertEquals(List.of("consume:42"), fixture.operations.leases);
        DashboardIntegrationService.InboundResponse replay = (DashboardIntegrationService.InboundResponse) invoke(
                inbound, "replayStoredResponse", new Class<?>[]{Map.class, String.class}, row, "new-request-that-must-not-replace-original");
        assertEquals(200, replay.httpStatus());
        assertEquals("original-request", replay.body().get("requestId"));
        assertEquals(7, replay.body().get("accepted"));
        assertEquals(93, replay.body().get("duplicates"));
        assertEquals("ACCEPTED", replay.body().get("code"));
        assertEquals(true, replay.body().get("detailExpired"));
    }

    private static Map<String, Object> policy(long id, String config)
    {
        return Map.of("integrationId", id, "integrationCode", "retention-test-" + id, "configJson", config);
    }

    private static Map<String, Object> batch(long id)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", id);
        row.put("platformRequestId", "original-request");
        row.put("messageId", "original-message");
        row.put("acceptedCount", 7);
        row.put("processingCount", 0);
        row.put("duplicateCount", 93);
        row.put("rejectedCount", 0);
        row.put("retryableCount", 0);
        return row;
    }

    private static Map<String, Object> read(String value) throws Exception
    {
        return JSON.readValue(value, new TypeReference<>() { });
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object construct(Class<?> type, Object... arguments) throws Exception
    {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException ex)
        {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }

    private record Call(String name, Object[] args) { }

    private static class RecordingOperations extends DashboardIntegrationOperations
    {
        final List<String> leases = new ArrayList<>();
        final List<Long> signals = new ArrayList<>();
        boolean grant = true;
        boolean inLease;

        @Override public <T> T withLease(String key, int seconds, Supplier<T> action)
        {
            leases.add(key);
            assertEquals(120, seconds);
            if (!grant) return null;
            assertFalse(inLease);
            inLease = true;
            try { return action.get(); }
            finally { inLease = false; }
        }

        @Override public void signal(Long id)
        {
            assertTrue("数据变更信号必须与记录清理同事务", inLease);
            signals.add(id);
        }

        @Override public void audit(String category, String resource, String requestId, String outcome,
                String actor, long bytes, long ms) { }
    }

    private static class Fixture
    {
        final RecordingOperations operations = new RecordingOperations();
        final List<Call> calls = new ArrayList<>();
        final Map<String, Function<Object[], Object>> answers = new HashMap<>();
        final DashboardIntegrationRetentionService service;

        Fixture()
        {
            DashboardIntegrationRetentionMapper mapper = (DashboardIntegrationRetentionMapper) Proxy.newProxyInstance(
                    DashboardIntegrationRetentionMapper.class.getClassLoader(),
                    new Class<?>[]{DashboardIntegrationRetentionMapper.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (!"selectPolicies".equals(name))
                            assertTrue("保留期读写必须在消费租约内: " + name, operations.inLease);
                        calls.add(new Call(name, args.clone()));
                        Function<Object[], Object> response = answers.get(name);
                        if (response != null) return response.apply(args);
                        if (name.startsWith("select")) return List.of();
                        if (List.of("clearRawBatches", "clearRawMessages", "clearSuccessMessages", "deleteExpiredRecords").contains(name))
                            return ((List<?>) args[1]).size();
                        if ("compactSuccessBatch".equals(name)) return 1;
                        if ("deleteEmptySuccessBatch".equals(name)) return 0;
                        throw new AssertionError("测试意外调用 Mapper: " + name);
                    });
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("retention-test",
                    Map.of("dashboard.integration.retention-cleanup-interval-seconds", 300)));
            service = new DashboardIntegrationRetentionService(mapper, operations, environment);
        }

        boolean called(String name) { return calls.stream().anyMatch(call -> name.equals(call.name)); }
        Call call(String name) { return calls.stream().filter(call -> name.equals(call.name)).findFirst().orElseThrow(); }
        void time(Instant now) throws Exception { set(service, "clock", Clock.fixed(now, ZoneOffset.UTC)); }
    }
}
