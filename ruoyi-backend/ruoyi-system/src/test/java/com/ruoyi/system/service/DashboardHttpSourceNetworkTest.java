package com.ruoyi.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardMapper;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.Assert.*;

/** 保存、接口执行和旧 API 兼容入口必须使用同一来源网络策略。 */
public class DashboardHttpSourceNetworkTest
{
    private static final String PUBLIC_HOST = "8.8.8.8";
    private static final String PUBLIC_URL = "http://" + PUBLIC_HOST + ":9036";

    @Test
    public void publicHttpAllowsExplicitPublicHostAndPortWithoutDevelopmentFlags()
    {
        var target = validate(PUBLIC_URL, "PUBLIC_HTTP", List.of(PUBLIC_HOST), List.of(), List.of(9036), false, false);
        assertEquals(URI.create(PUBLIC_URL), target.uri());
        assertEquals(PUBLIC_HOST, target.addresses().get(0).getHostAddress());
        validate("https://" + PUBLIC_HOST, "PUBLIC_HTTP", List.of(PUBLIC_HOST), List.of(), List.of(443), false, false);
        validate("http://" + PUBLIC_HOST, "PUBLIC_HTTP", List.of(PUBLIC_HOST), List.of(), List.of(80), false, false);
    }

    @Test
    public void publicHttpsAlwaysRequiresHttpsIncludingWithDevelopmentFlag()
    {
        for (boolean allowHttp : List.of(false, true))
            assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTPS",
                    List.of(PUBLIC_HOST), List.of(), List.of(9036), allowHttp, true));
        validate("https://" + PUBLIC_HOST, null, List.of(PUBLIC_HOST), List.of(), List.of(443), false, false);
    }

    @Test
    public void publicHttpRequiresItsOwnHostAndPortAllowlists()
    {
        assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTP",
                List.of(), List.of(PUBLIC_HOST + "/32"), List.of(9036), false, false));
        assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTP",
                List.of("1.1.1.1"), List.of(PUBLIC_HOST + "/32"), List.of(9036), false, false));
        assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTP",
                List.of(PUBLIC_HOST), List.of(), List.of(), false, false));
        assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTP",
                List.of(PUBLIC_HOST), List.of(), List.of(80), false, false));
        assertThrows(IllegalArgumentException.class, () -> validate("http://" + PUBLIC_HOST + ":0", "PUBLIC_HTTP",
                List.of(PUBLIC_HOST), List.of(), List.of(80), false, false));
    }

    @Test
    public void publicHttpRejectsPrivateMetadataAndReservedTargetsEvenWithDevelopmentFlags()
    {
        for (boolean local : List.of(false, true))
            for (String host : List.of("127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.1.2", "169.254.1.2",
                    "169.254.169.254", "100.100.100.200", "100.64.0.1", "0.0.0.0", "224.0.0.1", "192.0.2.1", "198.18.0.1"))
                assertThrows(host, IllegalArgumentException.class, () -> validate("http://" + host + ":9036", "PUBLIC_HTTP",
                        List.of(host), List.of(host + "/32"), List.of(9036), true, local));
    }

    @Test
    public void publicHttpChecksCidrsEvenWithDevelopmentFlags()
    {
        for (boolean local : List.of(false, true))
        {
            validate(PUBLIC_URL, "PUBLIC_HTTP", List.of(PUBLIC_HOST), List.of(PUBLIC_HOST + "/32"), List.of(9036), false, local);
            assertThrows(IllegalArgumentException.class, () -> validate(PUBLIC_URL, "PUBLIC_HTTP",
                    List.of(PUBLIC_HOST), List.of("1.1.1.0/24"), List.of(9036), true, local));
        }
    }

    @Test
    public void privateLinkRetainsHttpGateCidrAndLocalDevelopmentBehavior()
    {
        String url = "http://10.1.2.3:9036";
        assertThrows(IllegalArgumentException.class, () -> validate(url, "PRIVATE_LINK",
                List.of("10.1.2.3"), List.of("10.1.2.0/24"), List.of(9036), false, false));
        validate(url, "PRIVATE_LINK", List.of("10.1.2.3"), List.of("10.1.2.0/24"), List.of(9036), true, false);
        assertThrows(IllegalArgumentException.class, () -> validate(url, "PRIVATE_LINK",
                List.of("10.1.2.3"), List.of(), List.of(9036), true, false));
        assertThrows(IllegalArgumentException.class, () -> validate("http://127.0.0.1", "PRIVATE_LINK",
                List.of("127.0.0.1"), List.of("127.0.0.0/8"), List.of(80), true, false));
        validate("http://127.0.0.1", "PRIVATE_LINK", List.of("127.0.0.1"), List.of(), List.of(80), true, true);
        assertThrows(IllegalArgumentException.class, () -> validate("http://169.254.169.254", "PRIVATE_LINK",
                List.of("169.254.169.254"), List.of(), List.of(80), true, true));
    }

    @Test
    public void saveAndExecutionAcceptPublicHttpWithIdenticalPolicy() throws Exception
    {
        Map<String, Object> config = sourceConfig();
        config.put("networkProfile", "public_http");
        DashboardService save = service(new DashboardService(), Map.of());
        invoke(save, "validateDataSourceConfig", new Class<?>[]{String.class, Map.class}, "HTTP", config);
        assertEquals("PUBLIC_HTTP", config.get("networkProfile"));
        var execute = service(new DashboardIntegrationService(), Map.of());
        assertEquals(URI.create(PUBLIC_URL), ((DashboardIntegrationNetwork.Target) invoke(execute,
                "validateOutboundTarget", new Class<?>[]{String.class, Map.class}, PUBLIC_URL, config)).uri());
    }

    @Test
    public void neitherSaveNorExecutionCanUseGlobalHostsForPublicHttp() throws Exception
    {
        Map<String, Object> properties = Map.of("dashboard.api.allowed-hosts", PUBLIC_HOST);
        for (String missing : List.of("allowedHosts", "allowedPorts"))
        {
            Map<String, Object> config = sourceConfig();
            config.remove(missing);
            assertSaveAndExecutionReject(config, properties);
        }
    }

    @Test
    public void saveAndExecutionRejectTheSameForbiddenAddressesAndSchemes() throws Exception
    {
        Map<String, Object> properties = Map.of("dashboard.integration.allow-plain-http", true,
                "dashboard.integration.allow-local-development-targets", true);
        Map<String, Object> httpsOnly = sourceConfig();
        httpsOnly.put("networkProfile", "PUBLIC_HTTPS");
        assertSaveAndExecutionReject(httpsOnly, properties);
        for (String host : List.of("127.0.0.1", "10.1.2.3", "169.254.169.254"))
        {
            Map<String, Object> config = sourceConfig();
            config.put("baseUrl", "http://" + host + ":9036");
            config.put("allowedHosts", List.of(host));
            assertSaveAndExecutionReject(config, properties);
        }
    }

    @Test
    public void legacyRegisteredEndpointAndUriRetainSourcePolicy() throws Exception
    {
        DashboardService service = service(new DashboardService(), Map.of());
        set(service, "objectMapper", new ObjectMapper());
        Map<String, Object> config = sourceConfig();
        Map<String, Object> source = Map.of("sourceType", "HTTP", "status", "ACTIVE", "configJson",
                new ObjectMapper().writeValueAsString(config));
        assertNotNull(invoke(service, "resolveRegisteredEndpoint", new Class<?>[]{Map.class}, source));
        URI uri = (URI) invoke(service, "resolveApiUri", new Class<?>[]{String.class, String.class, Map.class, Map.class},
                PUBLIC_URL, "/test/${id}", Map.of("id", "a b"), config);
        assertEquals(PUBLIC_URL + "/test/a+b", uri.toString());
    }

    @Test
    public void legacyHttpTransportStillReadsJsonAndRejectsRedirects() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            byte[] body = "{\"rows\":[{\"value\":1}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try
        {
            Map<String, Object> config = sourceConfig();
            config.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            config.put("networkProfile", "PRIVATE_LINK");
            config.put("allowedHosts", List.of("127.0.0.1"));
            config.put("allowedPorts", List.of(server.getAddress().getPort()));
            var service = service(new DashboardService(), Map.of("dashboard.integration.allow-plain-http", true,
                    "dashboard.integration.allow-local-development-targets", true));
            ObjectMapper mapper = new ObjectMapper();
            set(service, "objectMapper", mapper);
            set(service, "dashboardMapper", Proxy.newProxyInstance(DashboardMapper.class.getClassLoader(),
                    new Class<?>[]{DashboardMapper.class}, (proxy, method, args) -> Map.of("sourceType", "HTTP",
                            "status", "ACTIVE", "configJson", mapper.writeValueAsString(config))));
            config.put("path", "/data");
            Class<?>[] types = {String.class, Map.class, Map.class};
            Map<String, Object> dataset = Map.of("endpointCode", "http-fixture", "rowsPath", "rows");
            Map<?, ?> result = (Map<?, ?>) invoke(service, "executeApi", types, "dataset-fixture", dataset, Map.of());
            assertEquals("CONNECTED", result.get("sourceStatus"));
            assertEquals(1, ((List<?>) result.get("rows")).size());
            config.put("path", "/redirect");
            ServiceException rejected = assertThrows(ServiceException.class,
                    () -> invoke(service, "executeApi", types, "dataset-fixture", dataset, Map.of()));
            assertTrue(rejected.getMessage().contains("302"));
        }
        finally { server.stop(0); }
    }

    private void assertSaveAndExecutionReject(Map<String, Object> config, Map<String, Object> properties) throws Exception
    {
        var save = service(new DashboardService(), properties);
        assertThrows(ServiceException.class, () -> invoke(save, "validateDataSourceConfig",
                new Class<?>[]{String.class, Map.class}, "HTTP", config));
        var execute = service(new DashboardIntegrationService(), properties);
        assertThrows(ServiceException.class, () -> invoke(execute, "validateOutboundTarget",
                new Class<?>[]{String.class, Map.class}, config.get("baseUrl"), config));
    }

    private static Map<String, Object> sourceConfig()
    {
        return new LinkedHashMap<>(Map.of("baseUrl", PUBLIC_URL, "path", "/data", "method", "GET",
                "networkProfile", "PUBLIC_HTTP", "allowedHosts", List.of(PUBLIC_HOST), "allowedPorts", List.of(9036)));
    }

    private static DashboardIntegrationNetwork.Target validate(String url, String profile, List<String> hosts,
            List<String> cidrs, List<Integer> ports, boolean allowHttp, boolean local)
    {
        return DashboardIntegrationNetwork.validate(url, profile, hosts, cidrs, ports, allowHttp, local);
    }

    private static <T> T service(T service, Map<String, Object> properties) throws Exception
    {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("http-source-test", properties));
        set(service, "environment", environment);
        return service;
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException ex)
        {
            if (ex.getCause() instanceof Exception error) throw error;
            throw ex;
        }
    }
}
