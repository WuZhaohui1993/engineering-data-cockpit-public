package com.ruoyi.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationPolicyMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.Assert.*;

/** 公共保留配置与清理编排；SQL 条件和 Controller 日志正文另做真实接口回归。 */
public class DashboardIntegrationPolicyServiceTest
{
    private static final String AUDIT_ENV = "dashboard.integration.audit-retention-days";
    private static final List<String> FIELDS = List.of("auditRetentionDays", "resolvedAlertRetentionDays", "testLogRetentionDays");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void missingStoredPolicyKeepsEnvironmentAuditDaysAndDoesNotPersistDefaults() throws Exception
    {
        Fixture fixture = new Fixture(Map.of(AUDIT_ENV, 45));
        assertEquals(Map.of("auditRetentionDays", 45, "resolvedAlertRetentionDays", 30,
                "testLogRetentionDays", 30, "testResponseBodyStored", false), fixture.service.getPolicy());
        assertEquals(45, fixture.service.auditRetentionDays());
        assertNull(fixture.stored);
        assertFalse(fixture.called("savePolicy"));
    }

    @Test
    public void environmentDefaultAndBoundsRemainSafeAndStoredFieldsTakePrecedence() throws Exception
    {
        assertEquals(30, new Fixture(Map.of()).service.auditRetentionDays());
        assertEquals(1, new Fixture(Map.of(AUDIT_ENV, -5)).service.auditRetentionDays());
        assertEquals(3650, new Fixture(Map.of(AUDIT_ENV, 9999)).service.auditRetentionDays());
        Fixture fixture = new Fixture(Map.of(AUDIT_ENV, 90));
        fixture.stored = "{\"auditRetentionDays\":7}";
        assertEquals(7, fixture.service.auditRetentionDays());
        assertEquals(30, fixture.service.getPolicy().get("resolvedAlertRetentionDays"));
        assertEquals(30, fixture.service.getPolicy().get("testLogRetentionDays"));
        assertFalse(fixture.called("savePolicy"));
    }

    @Test
    public void saveAndGetRoundTripOnlyPolicyFieldsAndKeepBodyStorageDisabled() throws Exception
    {
        Fixture fixture = new Fixture(Map.of(AUDIT_ENV, 90));
        Map<String, Object> input = policy(7, 14, 21);
        Map<String, Object> original = new LinkedHashMap<>(input);
        Map<String, Object> saved = fixture.service.updatePolicy(input, "policy-editor");
        assertEquals(original, input);
        assertEquals(original, JSON.readValue(fixture.stored, new TypeReference<Map<String, Object>>() { }));
        assertEquals("policy-editor", fixture.call("savePolicy").args()[1]);
        assertEquals(false, saved.get("testResponseBodyStored"));
        assertEquals(saved, fixture.service.getPolicy());
        saved.put("auditRetentionDays", 200);
        assertEquals(7, fixture.service.auditRetentionDays());
    }

    @Test
    public void policyDaysAcceptOnlyIntegralJavaNumbersInOneTo3650Range() throws Exception
    {
        for (String field : FIELDS)
        {
            for (Object value : List.of((byte) 1, (short) 7, 30, 3650L, BigInteger.valueOf(3650)))
            {
                Fixture fixture = new Fixture(Map.of());
                Map<String, Object> input = policy(30, 30, 30);
                input.put(field, value);
                assertEquals(((Number) value).intValue(), fixture.service.updatePolicy(input, "tester").get(field));
            }
            for (Object value : Arrays.asList(null, 0, -1, 3651, Long.MAX_VALUE,
                    new BigInteger("999999999999999999999"), 1.0d, 1.5d, new BigDecimal("30"), "30", "", true, List.of(30)))
            {
                Fixture fixture = new Fixture(Map.of());
                Map<String, Object> input = policy(30, 30, 30);
                input.put(field, value);
                ServiceException error = assertThrows(ServiceException.class, () -> fixture.service.updatePolicy(input, "tester"));
                assertTrue(error.getMessage().contains("1 至 3650 的整数"));
                assertFalse(fixture.called("savePolicy"));
            }
        }
    }

    @Test
    public void incompleteOrUnknownInputFieldsAreRejectedBeforeSaving() throws Exception
    {
        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(Map.of());
        invalid.add(Map.of("auditRetentionDays", 30));
        Map<String, Object> unknown = policy(30, 30, 30);
        unknown.put("recordRetentionDays", 1);
        invalid.add(unknown);
        Map<String, Object> readOnly = policy(30, 30, 30);
        readOnly.put("testResponseBodyStored", true);
        invalid.add(readOnly);
        invalid.add(Map.of("auditRetentionDays", 30, "resolvedAlertRetentionDays", 30, "unknown", 30));
        for (Map<String, Object> input : invalid)
        {
            Fixture fixture = new Fixture(Map.of());
            assertThrows(ServiceException.class, () -> fixture.service.updatePolicy(input, "tester"));
            assertFalse(fixture.called("savePolicy"));
        }
    }

    @Test
    public void cleanupUsesSeparateCutoffsTheSameClockAndOneThousandPerTable() throws Exception
    {
        Fixture fixture = new Fixture(Map.of());
        fixture.stored = JSON.writeValueAsString(policy(90, 17, 5));
        Instant before = Instant.now();
        fixture.service.cleanup();
        Instant after = Instant.now();
        Call alerts = fixture.call("deleteExpiredConfirmedAlerts");
        Call tests = fixture.call("deleteExpiredTestLogs");
        assertEquals(1000, alerts.args()[1]);
        assertEquals(1000, tests.args()[1]);
        Instant alertCutoff = ((Timestamp) alerts.args()[0]).toInstant();
        Instant testCutoff = ((Timestamp) tests.args()[0]).toInstant();
        assertFalse(alertCutoff.isBefore(before.minusSeconds(17 * 86400L)));
        assertFalse(alertCutoff.isAfter(after.minusSeconds(17 * 86400L)));
        assertEquals(alertCutoff.plusSeconds(12 * 86400L), testCutoff);
        assertEquals(1, fixture.calls.stream().filter(call -> call.name().equals("deleteExpiredConfirmedAlerts")).count());
        assertEquals(1, fixture.calls.stream().filter(call -> call.name().equals("deleteExpiredTestLogs")).count());
        assertFalse(fixture.called("savePolicy"));
    }

    @Test
    public void malformedOrInvalidStoredPolicyStopsCleanupBeforeAnyDeletion() throws Exception
    {
        for (String config : List.of("broken-json", "[]", "null", "{\"auditRetentionDays\":\"30\"}",
                "{\"resolvedAlertRetentionDays\":0}", "{\"testLogRetentionDays\":1.5}"))
        {
            Fixture fixture = new Fixture(Map.of());
            fixture.stored = config;
            assertThrows(ServiceException.class, fixture.service::cleanup);
            assertFalse(fixture.called("deleteExpiredConfirmedAlerts"));
            assertFalse(fixture.called("deleteExpiredTestLogs"));
        }
    }

    @Test
    public void httpCachePolicySupportsZeroButRejectsCoercionAndOverflow() throws Exception
    {
        DashboardIntegrationCachePolicy.validate(Map.of());
        DashboardIntegrationCachePolicy.validate(Map.of("cacheSeconds", 0, "sharedFetchSeconds", 0, "staleIfErrorSeconds", 45));
        for (String key : List.of("cacheSeconds", "sharedFetchSeconds", "staleIfErrorSeconds"))
        {
            int maximum = key.equals("staleIfErrorSeconds") ? 86400 : 3600;
            DashboardIntegrationCachePolicy.validate(Map.of(key, BigInteger.valueOf(maximum)));
            for (Object value : Arrays.asList(null, "1", 1.0d, 1.5d, -1, maximum + 1, Long.MAX_VALUE, true))
            {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put(key, value);
                assertThrows(ServiceException.class, () -> DashboardIntegrationCachePolicy.validate(config));
            }
        }
    }

    private static Map<String, Object> policy(int audit, int alerts, int tests)
    {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("auditRetentionDays", audit);
        policy.put("resolvedAlertRetentionDays", alerts);
        policy.put("testLogRetentionDays", tests);
        return policy;
    }

    private static void inject(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Call(String name, Object[] args) { }

    private static class Fixture
    {
        final DashboardIntegrationPolicyService service = new DashboardIntegrationPolicyService();
        final List<Call> calls = new ArrayList<>();
        String stored;

        Fixture(Map<String, Object> properties) throws Exception
        {
            DashboardIntegrationPolicyMapper mapper = (DashboardIntegrationPolicyMapper) Proxy.newProxyInstance(
                    DashboardIntegrationPolicyMapper.class.getClassLoader(), new Class<?>[]{DashboardIntegrationPolicyMapper.class},
                    (proxy, method, args) -> {
                        calls.add(new Call(method.getName(), args == null ? new Object[0] : args.clone()));
                        return switch (method.getName())
                        {
                            case "selectPolicy" -> stored == null ? null : Map.of("configJson", stored);
                            case "savePolicy" -> { stored = (String) args[0]; yield 1; }
                            case "deleteExpiredConfirmedAlerts", "deleteExpiredTestLogs" -> 1000;
                            default -> throw new AssertionError("意外调用: " + method.getName());
                        };
                    });
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("policy-test", properties));
            inject(service, "mapper", mapper);
            inject(service, "environment", environment);
        }

        boolean called(String name) { return calls.stream().anyMatch(call -> call.name().equals(name)); }
        Call call(String name) { return calls.stream().filter(call -> call.name().equals(name)).findFirst().orElseThrow(); }
    }
}
