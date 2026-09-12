package com.ruoyi.system.service;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationMapper;
import com.ruoyi.system.mapper.DashboardMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.springframework.core.env.Environment;
import static org.junit.Assert.*;

/** 分页必须保留精确 total，预校验不能消费 PageHelper，内置来源不能在每页重复出现。 */
public class DashboardManagementPaginationTest
{
    @After public void clearPage() { PageHelper.clearPage(); }

    @Test
    public void builtinAndDatabaseBoundaryUsesOnlyTheRequiredDatabaseSlice() throws Exception
    {
        SourceFixture fixture = new SourceFixture();
        assertPage(fixture.page(null, 1, 2), 5, "labor", "master");
        assertEquals(0, fixture.pageQueries.size());
        assertPage(fixture.page(null, 2, 2), 5, "source-3", "source-2");
        assertEquals(0L, fixture.pageQueries.get(0).get("offset"));
        assertEquals(2, fixture.pageQueries.get(0).get("limit"));
        assertPage(fixture.page(null, 3, 2), 5, "source-1");
        assertEquals(2L, fixture.pageQueries.get(1).get("offset"));
        assertPage(fixture.page(null, 1, 3), 5, "labor", "master", "source-3");
        assertEquals(1, fixture.pageQueries.get(2).get("limit"));
    }

    @Test
    public void systemRootAndOutOfRangePagesKeepTheirOwnTotals() throws Exception
    {
        SourceFixture fixture = new SourceFixture();
        assertPage(fixture.page(-1L, 2, 1), 2, "master");
        assertEquals(0, fixture.countQueries);
        assertPage(fixture.page(0L, 1, 2), 3, "source-3", "source-2");
        int before = fixture.pageQueries.size();
        assertPage(fixture.page(null, 100, 10), 5);
        assertEquals(before, fixture.pageQueries.size());
        assertPage(fixture.service.listDataSourcesPage("master", null, null, null, -1L, false, 1, 10), 1, "master");
        assertPage(fixture.service.listDataSourcesPage(null, "HTTP", null, null, null, false, 1, 2), 3, "source-3", "source-2");
    }

    @Test
    public void invalidPaginationIsRejectedBeforeDatabaseQueries() throws Exception
    {
        SourceFixture fixture = new SourceFixture();
        for (int[] value : List.of(new int[]{0, 10}, new int[]{1, 0}, new int[]{1, 1001}))
        {
            try { fixture.page(null, value[0], value[1]); fail("invalid page accepted"); }
            catch (ServiceException expected) { assertTrue(expected.getMessage().contains("分页")); }
        }
        assertEquals(0, fixture.countQueries);
        assertTrue(fixture.pageQueries.isEmpty());
    }

    @Test
    public void integrationEndpointsBatchesAndDeadLettersPreservePageAfterRedaction() throws Exception
    {
        IntegrationFixture fixture = new IntegrationFixture();
        for (int kind = 0; kind < 4; kind++)
        {
            fixture.expected = kind;
            List<Map<String, Object>> rows = switch (kind) {
                case 0 -> fixture.service.listIntegrationsPage(null, null, null, 10L, true, 2, 3);
                case 1 -> fixture.service.listEndpointsPage("source-demo", null, null, 2, 3);
                case 2 -> fixture.service.listBatchesPage(42L, null, 2, 3);
                default -> fixture.service.listDeadLettersPage(42L, null, 2, 3);
            };
            assertSame(fixture.page, rows);
            assertEquals(25, new PageInfo<>(rows).getTotal());
            assertEquals(1, rows.size());
            assertFalse(rows.get(0).containsKey("secretCiphertext"));
            assertFalse(rows.get(0).containsKey("rawBodyCiphertext"));
            assertNull(PageHelper.getLocalPage());
        }
        assertEquals(4, fixture.prechecks);
    }

    @Test
    public void missingIntegrationOrSourceIsRejectedBeforeStartingPagination() throws Exception
    {
        IntegrationFixture fixture = new IntegrationFixture();
        fixture.missing = true;
        for (int kind : List.of(1, 2, 3))
        {
            try
            {
                if (kind == 1) fixture.service.listEndpointsPage("source-demo", null, null, 1, 10);
                else if (kind == 2) fixture.service.listBatchesPage(42L, null, 1, 10);
                else fixture.service.listDeadLettersPage(42L, null, 1, 10);
                fail("missing scope accepted");
            }
            catch (ServiceException expected) { assertNull(PageHelper.getLocalPage()); }
        }
        assertNull(fixture.page);
    }

    private static void assertPage(List<Map<String, Object>> rows, long total, String... codes)
    {
        assertTrue(rows instanceof Page<?>);
        assertEquals(total, new PageInfo<>(rows).getTotal());
        assertEquals(List.of(codes), rows.stream().map(item -> item.get("code")).toList());
        for (Map<String, Object> row : rows)
        {
            assertFalse(row.containsKey("configJson"));
            assertFalse(row.containsKey("secretCiphertext"));
        }
    }

    private static final class SourceFixture
    {
        final DashboardService service = new DashboardService();
        final List<Map<String, Object>> pageQueries = new ArrayList<>();
        final List<Map<String, Object>> sources = new ArrayList<>();
        int countQueries;

        SourceFixture() throws Exception
        {
            for (int id : List.of(3, 2, 1)) sources.add(new LinkedHashMap<>(Map.of("dataSourceId", (long) id,
                    "sourceCode", "source-" + id, "sourceName", "数据源 " + id, "sourceType", "HTTP",
                    "folderId", 0L, "status", "ACTIVE", "configJson", "{\"baseUrl\":\"https://example.invalid\",\"environment\":\"TEST\"}")));
            set(service, "environment", Proxy.newProxyInstance(Environment.class.getClassLoader(), new Class<?>[]{Environment.class},
                    (proxy, method, args) -> method.getName().equals("getProperty") ? "" : null));
            set(service, "dataFolders", new DashboardDataFolderService() {
                @Override public List<Long> filterIds(String scope, Long id, boolean children) { return id == null ? null : List.of(id); }
            });
            set(service, "dashboardMapper", Proxy.newProxyInstance(DashboardMapper.class.getClassLoader(), new Class<?>[]{DashboardMapper.class},
                    (proxy, method, args) -> {
                        assertNull(PageHelper.getLocalPage());
                        Map<String, Object> filters = cast(args[0]);
                        if (method.getName().equals("countDataSourcesByFilters")) { countQueries++; return (long) sources.size(); }
                        if (method.getName().equals("selectDataSourcePage"))
                        {
                            pageQueries.add(new HashMap<>(filters));
                            int offset = ((Number) filters.get("offset")).intValue();
                            int limit = ((Number) filters.get("limit")).intValue();
                            return new ArrayList<>(sources.subList(offset, Math.min(sources.size(), offset + limit)));
                        }
                        throw new AssertionError("分页不允许全量来源查询: " + method.getName());
                    }));
        }

        List<Map<String, Object>> page(Long folderId, int pageNum, int pageSize)
        { return service.listDataSourcesPage(null, null, null, null, folderId, false, pageNum, pageSize); }
    }

    private static final class IntegrationFixture
    {
        final DashboardIntegrationService service = new DashboardIntegrationService();
        Page<Map<String, Object>> page;
        int prechecks;
        int expected;
        boolean missing;

        IntegrationFixture() throws Exception
        {
            set(service, "dataFolders", new DashboardDataFolderService() {
                @Override public List<Long> filterIds(String scope, Long id, boolean children)
                { assertNull(PageHelper.getLocalPage()); prechecks++; return List.of(10L, 11L); }
            });
            set(service, "dashboardMapper", Proxy.newProxyInstance(DashboardMapper.class.getClassLoader(), new Class<?>[]{DashboardMapper.class},
                    (proxy, method, args) -> {
                        assertEquals("selectDataSourceByCode", method.getName());
                        assertNull(PageHelper.getLocalPage()); prechecks++;
                        return missing ? null : Map.of("sourceCode", "source-demo", "sourceType", "HTTP");
                    }));
            set(service, "integrationMapper", Proxy.newProxyInstance(DashboardIntegrationMapper.class.getClassLoader(), new Class<?>[]{DashboardIntegrationMapper.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("selectIntegrationById"))
                        { assertNull(PageHelper.getLocalPage()); prechecks++; return missing ? null : Map.of("integrationId", 42L); }
                        assertEquals(List.of("selectIntegrationListByFolders", "selectEndpointPage", "selectBatchList", "selectDeadLetterList").get(expected), method.getName());
                        Page<?> request = PageHelper.getLocalPage();
                        assertNotNull(request);
                        assertEquals(2, request.getPageNum()); assertEquals(3, request.getPageSize());
                        if (expected >= 2) assertNull(args[2]); // 新分页不能保留旧的 limit 截断。
                        page = new Page<>(2, 3, false); page.setTotal(25);
                        page.add(new LinkedHashMap<>(Map.of("integrationId", 42L, "endpointId", 1L,
                                "batchId", 2L, "integrationMessageId", 3L, "configJson", "{}",
                                "secretCiphertext", "sensitive-fixture", "rawBodyCiphertext", "sensitive-fixture")));
                        return page;
                    }));
        }
    }

    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }

    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
}
