package com.ruoyi.system.service;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationStateMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.Assert.*;

public class DashboardIntegrationMonitoringScopeTest
{
    @Test public void allThreeQueriesUseLiteralResourceScopeAndKnownCategories() throws Exception
    {
        Configuration configuration=configuration();
        for(String id:List.of("auditsByScope","alertsByScope","metricsByScope")) {
            String inbound=sql(configuration,id,Map.of("category","INBOUND","resourceCode","site_a"));
            assertTrue(inbound.contains("category in ('inbound','inbound_retention')"));
            assertTrue(inbound.contains("resource_code=?"));
            assertTrue(inbound.contains("concat(?,'/')"));assertTrue(inbound.contains("concat(?,':')"));
            assertFalse(inbound.contains(" like "));assertFalse(inbound.contains("site_a"));
            String outbound=sql(configuration,id,Map.of("category","OUTBOUND","resourceCode","site_a"));
            assertTrue(outbound.contains("category=?"));
        }
        String metrics=sql(configuration,"metricsByScope",Map.of("category","WEBSOCKET","resourceCode",""));
        assertTrue(metrics.contains("0 as pending,0 as deadletters,0 as records"));
        assertTrue(metrics.contains("category in ('outbound','inbound','media')"));
        assertTrue(metrics.contains("as websocketevents24h"));
        assertTrue(metrics.contains("as retentionruns24h"));
        String all=sql(configuration,"metricsByScope",Map.of("category","","resourceCode",""));
        assertTrue(all.contains("join dashboard_integration i on i.integration_id=m.integration_id"));
        String resources=sql(configuration,"resources",Map.of());
        for(String privateField:List.of("config_json","secret","credential","base_url","schema_json"))
            assertFalse(privateField,resources.contains(privateField));
        assertTrue(resources.contains("source_name as resourcename"));
        assertTrue(sql(configuration,"categorizedAlert",Map.of("category","WEBSOCKET","key","k","resource","source","code","WS_DISCONNECTED"))
                .contains("alert_key,category,resource_code,error_code"));
    }

    @Test public void legacyServiceCallsAndSourceValidationRemainCompatible() throws Exception
    {
        List<String> calls=new ArrayList<>();
        DashboardIntegrationOperations service=new DashboardIntegrationOperations();
        inject(service,"mapper",mapper((method,args)-> {
            calls.add(method+":"+Arrays.toString(args));
            return method.startsWith("metrics")?Map.of():List.of();
        }));
        service.metrics();service.alerts();service.audits(" outbound ");
        assertEquals(List.of("metricsByScope:[, ]","alertsByScope:[, ]","auditsByScope:[OUTBOUND, ]"),calls);
        service.metrics("INBOUND"," site_a ");assertEquals("metricsByScope:[INBOUND, site_a]",calls.get(3));
        for(String invalid:List.of("source/endpoint","site%","site' OR 1=1","source:port")) {
            try { service.audits("",invalid);fail("unsafe resource accepted"); } catch(ServiceException expected) { }
        }
        try { service.alerts("SQL","source");fail("unknown category accepted"); } catch(ServiceException expected) { }
        assertEquals(4,calls.size());
    }

    @Test public void cleanupUsesSharedPolicyAndFallsBackForStandaloneTests() throws Exception
    {
        List<String> calls=new ArrayList<>();
        DashboardIntegrationOperations service=new DashboardIntegrationOperations();
        inject(service,"mapper",mapper((method,args)->{calls.add(method+":"+Arrays.toString(args));return 0;}));
        service.cleanup();assertTrue(calls.contains("cleanupAudits:[30]"));
        calls.clear();
        inject(service,"policyService",new DashboardIntegrationPolicyService() {
            @Override public int auditRetentionDays() { return 14; }
            @Override public void cleanup() { calls.add("sharedCleanup"); }
        });
        service.cleanup();
        assertTrue(calls.indexOf("cleanupAudits:[14]")<calls.indexOf("sharedCleanup"));
    }

    @Test public void alertsAreNamespacedAndMediaAuditStoresOnlySafeSourceIdentity() throws Exception
    {
        List<List<Object>> alerts=new ArrayList<>();List<Map<String,Object>> audits=new ArrayList<>();List<String> lookups=new ArrayList<>();
        DashboardIntegrationOperations service=new DashboardIntegrationOperations();
        inject(service,"mapper",mapper((method,args)->{
            if(method.equals("mediaResource")||method.equals("mediaResourceByReference")) { lookups.add(method);return "weather-source/picture"; }
            if(method.equals("categorizedAlert")) alerts.add(Arrays.asList(args));
            if(method.equals("audit")) audits.add((Map<String,Object>)args[0]);
            return 1;
        }));
        inject(service,"transactions",new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            public void commit(TransactionStatus status) { }
            public void rollback(TransactionStatus status) { }
        });
        service.alert("shared","DEAD_LETTER");
        service.alert("OUTBOUND","shared","same-code");service.alert("WEBSOCKET","shared","same-code");
        assertEquals("INBOUND",alerts.get(0).get(1));
        assertNotEquals(alerts.get(1).get(0),alerts.get(2).get(0));
        service.audit("MEDIA","a".repeat(64),"request","SUCCESS","test",3,1);
        assertEquals("weather-source/picture",audits.get(0).get("resource"));
        assertFalse(audits.get(0).toString().contains("a".repeat(64)));
        lookups.clear();service.auditMedia("private-reference","SUCCESS","test",5);
        assertEquals(List.of("mediaResourceByReference"),lookups);
        assertEquals("weather-source/picture",audits.get(1).get("resource"));
        assertFalse(audits.get(1).toString().contains("private-reference"));
    }

    private interface Invocation { Object invoke(String method,Object[] args); }
    private static DashboardIntegrationStateMapper mapper(Invocation invocation) {
        return (DashboardIntegrationStateMapper)Proxy.newProxyInstance(DashboardIntegrationStateMapper.class.getClassLoader(),
                new Class<?>[]{DashboardIntegrationStateMapper.class},(proxy,method,args)->invocation.invoke(method.getName(),args==null?new Object[]{}:args));
    }
    private static void inject(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Configuration configuration() throws Exception {
        Configuration configuration=new Configuration();
        String resource="mapper/system/DashboardIntegrationStateMapper.xml";
        try(InputStream stream=DashboardIntegrationMonitoringScopeTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);new XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    private static String sql(Configuration configuration,String id,Map<String,Object> params) {
        return configuration.getMappedStatement("com.ruoyi.system.mapper.DashboardIntegrationStateMapper."+id)
                .getBoundSql(params).getSql().replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
    }
}
