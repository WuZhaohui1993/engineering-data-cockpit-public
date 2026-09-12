package com.ruoyi.system.service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 对真实 Mapper 生成 SQL，守住归档写入范围及分页前可执行的过滤条件。 */
public class DashboardDataFolderSqlTest
{
    @Test
    public void moveStatementsOnlyChangeFolderAndAuditMetadata() throws Exception
    {
        Configuration configuration = load("DashboardDataFolderMapper");
        for (String variant : List.of("dataset", "source", "integration", "page", "asset", "map"))
        {
            String scope = List.of("asset", "map").contains(variant) ? "resource" : variant;
            Map<String, Object> params = Map.of("scope", scope, "ids", List.of(1L, 2L),
                    "folderId", 3L, "groupCode", "stable-code", "username", "test", "resourceType", variant);
            String sql = sql(configuration, "DashboardDataFolderMapper.moveMembers", params);
            String set = sql.substring(sql.indexOf(" set ") + 5, sql.indexOf(" where "));
            assertEquals(("dataset".equals(scope) ? "group_code" : "folder_id")
                    + " = ?, update_by = ?, update_time = now()", set);
            assertFalse(sql.contains("config_json"));
            assertFalse(sql.contains("status"));
            assertFalse(sql.contains("secret"));
            assertFalse(sql.contains("schema_json"));
            assertFalse(sql.contains("current_revision_id"));
            assertFalse(sql.contains("resource_path"));
            String locked = sql(configuration, "DashboardDataFolderMapper.selectMemberIds", params);
            assertTrue(locked.endsWith("order by 1 for update"));
            assertEquals(2, configuration.getMappedStatement("com.ruoyi.system.mapper.DashboardDataFolderMapper.selectMemberIds")
                    .getBoundSql(params).getParameterMappings().size());
        }
    }

    @Test
    public void originalPageAndResourceTablesAndDeletionRulesArePreserved() throws Exception
    {
        Configuration configuration = load("DashboardDataFolderMapper");
        Map<String, Object> page = Map.of("scope", "page", "folderId", 1L, "lock", true, "groupCode", "");
        assertTrue(sql(configuration, "DashboardDataFolderMapper.selectFolder", page).contains("from dashboard_page_folder"));
        assertTrue(sql(configuration, "DashboardDataFolderMapper.selectFolder", page).endsWith("for update"));
        assertTrue(sql(configuration, "DashboardDataFolderMapper.countMembers", page).contains("is_deleted = '0'"));
        Map<String, Object> resource = Map.of("scope", "resource", "folderId", 1L, "lock", false, "groupCode", "");
        assertTrue(sql(configuration, "DashboardDataFolderMapper.selectFolder", resource).contains("from dashboard_resource_folder"));
        String count = sql(configuration, "DashboardDataFolderMapper.countMembers", resource);
        assertTrue(count.contains("dashboard_asset"));
        assertTrue(count.contains("dashboard_map_resource"));
        String mapMembers = sql(configuration, "DashboardDataFolderMapper.selectMemberIds",
                Map.of("scope", "resource", "resourceType", "map", "ids", List.of(1L)));
        assertTrue(mapMembers.contains("map_code != 'demo-region'"));
    }

    @Test
    public void pageAndResourceFolderFiltersApplyBeforeOrderingAndLegacyQueriesStillBind() throws Exception
    {
        Configuration configuration = load("DashboardMapper");
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", null); params.put("includeDeleted", false); params.put("assetType", "IMAGE");
        params.put("folderIds", List.of(1L, 2L)); params.put("folderId", 1L);
        for (String name : List.of("selectPageList", "selectPublishedPageList", "selectAssetList", "selectMapResourceList", "selectMapResourcePage"))
        {
            String filtered = sql(configuration, "DashboardMapper." + name + "ByFolders", params);
            assertTrue(name, filtered.contains("folder_id in"));
            assertFalse(name, sql(configuration, "DashboardMapper." + name, params).contains("folder_id in"));
        }
    }

    @Test
    public void sourcePaginationUsesTheSameFiltersForCountAndBoundedRows() throws Exception
    {
        Configuration configuration = load("DashboardMapper");
        Map<String, Object> params = Map.of("keyword", "工程", "type", "HTTP", "status", "ACTIVE",
                "environment", "TEST", "folderIds", List.of(1L, 2L), "limit", 10, "offset", 20L);
        String count = sql(configuration, "DashboardMapper.countDataSourcesByFilters", params).toLowerCase(java.util.Locale.ROOT);
        String page = sql(configuration, "DashboardMapper.selectDataSourcePage", params).toLowerCase(java.util.Locale.ROOT);
        assertTrue(page.endsWith("order by s.data_source_id desc limit ? offset ?"));
        assertEquals(count.substring(count.indexOf(" where ")),
                page.substring(page.indexOf(" where "), page.indexOf(" order by ")));
        assertFalse(page.contains("secret_ciphertext"));
    }

    @Test
    public void datasetFolderFilterCombinesLegacyGroupTypeAndUnclassified() throws Exception
    {
        Configuration configuration = load("DashboardMapper");
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", "进度");
        params.put("dataType", "SQL");
        params.put("groupCode", "legacy");
        params.put("folderGroupCodes", List.of("legacy", "nested"));
        String sql = sql(configuration, "DashboardMapper.selectDatasetListByFolders", params);
        assertTrue(sql.contains("data_type = ?"));
        assertTrue(sql.contains("group_code = ?"));
        assertTrue(sql.contains("group_code in"));
        assertTrue(sql.endsWith("order by dataset_id desc"));
        params.put("folderGroupCodes", List.of(""));
        assertTrue(sql(configuration, "DashboardMapper.selectDatasetListByFolders", params).contains("group_code in"));
        params.put("folderGroupCodes", List.of());
        assertTrue(sql(configuration, "DashboardMapper.selectDatasetListByFolders", params).contains("1 = 0"));
        params.put("folderGroupCodes", null);
        assertFalse(sql(configuration, "DashboardMapper.selectDatasetListByFolders", params).contains("group_code in"));
        params.remove("folderGroupCodes");
        assertNotNull(sql(configuration, "DashboardMapper.selectDatasetList", params));
    }

    @Test
    public void integrationListAndLegacyListBothProduceValidBoundSql() throws Exception
    {
        Configuration configuration = load("DashboardIntegrationMapper");
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", null);
        params.put("environment", "TEST");
        params.put("status", "ACTIVE");
        assertFalse(sql(configuration, "DashboardIntegrationMapper.selectIntegrationList", params).contains("folder_id in"));
        params.put("folderIds", List.of(0L));
        assertTrue(sql(configuration, "DashboardIntegrationMapper.selectIntegrationListByFolders", params).contains("folder_id in"));
    }

    private Configuration load(String mapper) throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/system/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String statement, Map<String, Object> params)
    {
        return configuration.getMappedStatement("com.ruoyi.system.mapper." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
