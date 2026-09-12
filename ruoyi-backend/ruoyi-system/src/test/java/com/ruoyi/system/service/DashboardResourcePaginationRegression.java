package com.ruoyi.system.service;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * 使用真实 Mapper XML、PageHelper 和 H2 的 MySQL 兼容内存库验证分页。
 * 不连接业务库；test-compile 后将 H2 jar 加入应用依赖 classpath 执行 main。
 */
public final class DashboardResourcePaginationRegression
{
    private static int checks;

    public static void main(String[] args) throws Exception
    {
        UnpooledDataSource source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:dashboard-pagination;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = source.getConnection(); Statement sql = connection.createStatement())
        {
            String audit = ", create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp, remark varchar(255)";
            sql.execute("create table dashboard_resource_folder (folder_id bigint primary key, folder_code varchar(64), folder_name varchar(100), parent_id bigint, sort_order int, status varchar(1)" + audit + ")");
            sql.execute("insert into dashboard_resource_folder(folder_id,folder_code,folder_name,status) values(1,'one','项目目录','0')");
            sql.execute("create table dashboard_asset (asset_id bigint primary key, asset_code varchar(64), asset_name varchar(100), folder_id bigint, asset_type varchar(20), category varchar(100), resource_path varchar(255), mime_type varchar(100), file_size bigint, status varchar(1)" + audit + ")");
            sql.execute("create table dashboard_map_resource (map_id bigint primary key, map_code varchar(64), map_name varchar(100), folder_id bigint, geojson_json varchar(4000), status varchar(1)" + audit + ")");
            for (int i = 1; i <= 23; i++)
            {
                sql.execute("insert into dashboard_asset(asset_id,asset_code,asset_name,folder_id,asset_type,category,resource_path,status) values(" + i + ",'asset-" + i + "','资源" + i + "'," + (i % 2) + ",'" + (i % 2 == 0 ? "VIDEO" : "IMAGE") + "','分页标签','/profile/asset-" + i + ".png','0')");
                sql.execute("insert into dashboard_map_resource(map_id,map_code,map_name,folder_id,geojson_json,status) values(" + i + ",'map-" + i + "','登记地图" + i + (i == 23 ? "%_" : "") + "'," + (i % 2) + ",'{}','0')");
            }
        }
        Configuration configuration = new Configuration(new Environment("regression", new JdbcTransactionFactory(), source));
        PageInterceptor pagination = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pagination.setProperties(properties);
        configuration.addInterceptor(pagination);
        try (InputStream mapperXml = DashboardResourcePaginationRegression.class.getResourceAsStream("/mapper/system/DashboardMapper.xml"))
        {
            new XMLMapperBuilder(mapperXml, configuration, "DashboardMapper.xml", configuration.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession())
        {
            DashboardService service = new DashboardService();
            Field mapper = DashboardService.class.getDeclaredField("dashboardMapper");
            mapper.setAccessible(true);
            mapper.set(service, session.getMapper(DashboardMapper.class));
            List<Object> assetIds = new ArrayList<>();
            for (int page = 1; page <= 3; page++)
            {
                List<Map<String, Object>> rows = service.listAssetsPage("", "", null, page, 10);
                total(rows, 23, page == 3 ? 3 : 10);
                rows.forEach(row -> assetIds.add(row.get("assetId")));
                equal(rows.get(0).get("resourcePath"), rows.get(0).get("resourceUrl"));
                equal(null, PageHelper.getLocalPage());
            }
            equal(23, new HashSet<>(assetIds).size());
            total(service.listAssetsPage("分页标签", "VIDEO", 0L, 1, 5), 11, 5);
            total(service.listAssetsPage("资源23", "IMAGE", 1L, 1, 10), 1, 1);
            equal(23, service.listAssets("", "", null).size());
            List<Object> mapCodes = new ArrayList<>();
            for (int page = 1; page <= 3; page++)
            {
                List<Map<String, Object>> rows = service.listMapResourcesPage("", null, page, 10);
                total(rows, 24, page == 3 ? 4 : 10);
                rows.forEach(row -> mapCodes.add(row.get("mapCode")));
                equal(page == 1, rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("builtin"))));
            }
            equal(24, new HashSet<>(mapCodes).size());
            equal("demo-region", mapCodes.get(0));
            total(service.listMapResourcesPage("demo-region", null, 1, 10), 1, 1);
            total(service.listMapResourcesPage("DEMO", null, 1, 10), 1, 1);
            total(service.listMapResourcesPage("%", null, 1, 10), 1, 1);
            total(service.listMapResourcesPage("_", null, 1, 10), 1, 1);
            total(service.listMapResourcesPage("内置", 0L, 1, 10), 1, 1);
            total(service.listMapResourcesPage("内置", 1L, 1, 10), 0, 0);
            total(service.listMapResourcesPage("", 1L, 2, 10), 12, 2);
            total(service.listMapResourcesPage("", 0L, 2, 10), 12, 2);
            total(service.listMapResourcesPage("不存在", null, 1, 10), 0, 0);
            total(service.listMapResourcesPage("", null, 4, 10), 24, 0);
            equal(24, service.listMapResources("", null).size());
            equal(null, PageHelper.getLocalPage());
            for (int[] invalid : new int[][] { { 0, 10 }, { 1, 0 }, { 1, 1001 } })
            {
                try
                {
                    service.listMapResourcesPage("", null, invalid[0], invalid[1]);
                    throw new AssertionError("Invalid pagination accepted");
                }
                catch (ServiceException expected) { checks++; }
                equal(null, PageHelper.getLocalPage());
            }
        }
        System.out.println("Dashboard resource pagination regression passed: " + checks + " assertions");
    }

    private static void total(List<Map<String, Object>> rows, long expectedTotal, int expectedSize)
    {
        equal(true, rows instanceof Page);
        equal(expectedTotal, ((Page<?>) rows).getTotal());
        equal(expectedSize, rows.size());
    }

    private static void equal(Object expected, Object actual)
    {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
        checks++;
    }
}
