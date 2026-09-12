package com.ruoyi.system.service;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 真实 Mapper 和 Spring @Transactional + H2 MySQL 模式验证分享版本边界。
 * 只使用内存库；H2 jar 加入应用依赖 classpath 后执行 main，不连接业务数据库。
 */
public final class DashboardShareVersionRegression
{
    private static int checks;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception
    {
        Fixture f = new Fixture();
        Map<String, Object> follow = f.service.createShare(1L, Map.of("pageIds", List.of(2L), "expiresHours", 2), "regression");
        Map<String, Object> fixed = f.service.createShare(1L, Map.of("versionMode", "FIXED", "pageIds", List.of(2L), "permanent", true), "regression");
        long followId = id(follow.get("shareId"));
        String followToken = String.valueOf(follow.get("token"));
        String fixedToken = String.valueOf(fixed.get("token"));
        equal("FOLLOW_PUBLISHED", follow.get("versionMode"));
        equal("FIXED", fixed.get("versionMode"));
        Map<String, Object> protectedFields = f.protectedFields(followId);
        equal(101L, id(f.service.getShareVersion(followToken, null).get("revisionId")));
        equal(201L, id(f.service.getShareVersion(followToken, "page-two").get("revisionId")));
        equal("FOLLOW_PUBLISHED", f.service.selectShareList(1L).stream()
                .filter(row -> id(row.get("shareId")) == followId).findFirst().orElseThrow().get("versionMode"));

        // 新版发布后跟随当前发布，固定链接继续读取已经归档的旧版本。
        f.publish(1, 103, 3, "new-widget");
        f.publish(2, 203, 3, "new-widget");
        equal(103L, id(f.service.getShareVersion(followToken, null).get("revisionId")));
        equal(203L, id(f.service.getShareVersion(followToken, "page-two").get("revisionId")));
        equal(101L, id(f.service.getShareVersion(fixedToken, null).get("revisionId")));
        equal(201L, id(f.service.getShareRuntime(fixedToken, "page-two").get("revisionId")));
        equal("FIXED", f.service.getShareRuntime(fixedToken).get("versionMode"));
        equal(103L, id(f.service.executeSharePageDataset(followToken, 1L, "new-widget", "bound", Map.of(), List.of()).get("revisionId")));
        equal(203L, id(f.service.executeSharePageDataset(followToken, "page-two", 2L, "new-widget", "bound", Map.of(), List.of()).get("revisionId")));
        equal(101L, id(f.service.executeSharePageDataset(fixedToken, 1L, "old-widget", "bound", Map.of(), List.of()).get("revisionId")));
        equal("FORBIDDEN", f.service.executeSharePageDataset(followToken, 1L, "old-widget", "bound", Map.of(), List.of()).get("quality"));
        equal("FORBIDDEN", f.service.executeSharePageDataset(followToken, 1L, "new-widget", "unbound", Map.of(), List.of()).get("quality"));
        equal("FORBIDDEN", f.service.executeSharePageDataset(followToken, "page-two", 3L, "new-widget", "bound", Map.of(), List.of()).get("quality"));

        // 元数据路径不读取配置正文/数据目录，也不更新访问时间。
        int touches = f.touches;
        f.metadataOnly = true;
        Map<String, Object> metadata = f.service.getShareVersion(followToken, "page-two");
        equal(Set.of("pageId", "revisionId", "versionNo", "versionMode"), metadata.keySet());
        equal(touches, f.touches);
        rejects(() -> f.service.getShareVersion(followToken, "page-three"), "未授权");
        rejects(() -> f.service.getShareVersion(followToken, "../page-two"), "编码");
        rejects(() -> f.service.getShareVersion("invalid", null), "无效");
        f.metadataOnly = false;

        // 更改为固定模式捕获所有现有成员当前版本，并保持令牌/期限/成员不变。
        Map<String, Object> changed = f.service.updateShareVersionMode(1L, followId,
                Map.of("versionMode", "FIXED", "pageIds", List.of(3L)));
        equal("FIXED", changed.get("versionMode"));
        equal(103L, id(changed.get("revisionId")));
        equal(203L, f.memberRevision(followId, 2));
        check(protectedFields.equals(f.protectedFields(followId)), "更改版本模式不得改变令牌、有效期或授权成员");
        rejects(() -> f.service.getShareRuntime(followToken, "page-three"), "未授权");
        f.publish(1, 104, 4, "next-widget");
        f.publish(2, 204, 4, "next-widget");
        f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FIXED"));
        equal(103L, id(f.service.getShareVersion(followToken, null).get("revisionId")));
        equal(203L, f.memberRevision(followId, 2));
        f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FOLLOW_PUBLISHED"));
        equal(104L, id(f.service.getShareVersion(followToken, null).get("revisionId")));
        equal(204L, id(f.service.getShareVersion(followToken, "page-two").get("revisionId")));

        // 真实回滚生成新发布版本，跟随链接读取回滚内容，固定旧链接仍保持原版本。
        f.service.rollbackPage(1L, 101L, "regression", "回滚验证");
        Map<String, Object> rolledBack = f.service.getShareRuntime(followToken);
        equal(5L, id(rolledBack.get("versionNo")));
        check(JSON.writeValueAsString(rolledBack.get("schema")).contains("old-widget"), "跟随链接应读取回滚内容");
        equal(101L, id(f.service.getShareVersion(fixedToken, null).get("revisionId")));

        // 任一成员停用时整个变更被拒绝；不能局部更改或扩大集合。
        f.sql("update dashboard_page set status='1' where page_id=2");
        rejects(() -> f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FIXED")), "停用");
        equal("FOLLOW_PUBLISHED", f.shareMode(followId));
        equal(103L, f.memberRevision(followId, 1));
        rejects(() -> f.service.getShareVersion(followToken, "page-two"), "停用");
        f.sql("update dashboard_page set status='0' where page_id=2");

        // 在真实事务内注入第二个成员写入失败，验证前一个成员的更新一起回滚。
        f.failSecondMember = true;
        rejects(() -> f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FIXED")), "注入失败");
        f.failSecondMember = false;
        equal("FOLLOW_PUBLISHED", f.shareMode(followId));
        equal(103L, f.memberRevision(followId, 1));
        equal(203L, f.memberRevision(followId, 2));
        check(protectedFields.equals(f.protectedFields(followId)), "事务回滚后受保护字段保持原值");
        f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FIXED"));
        equal(id(rolledBack.get("revisionId")), f.memberRevision(followId, 1));
        equal(204L, f.memberRevision(followId, 2));
        rejects(() -> f.service.updateShareVersionMode(3L, followId, Map.of("versionMode", "FOLLOW_PUBLISHED")), "无效");
        rejects(() -> f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "LATEST_DRAFT")), "模式");

        // 迁移兼容：未显式写入版本模式的旧链接仍为 FIXED，旧单页无成员也可安全打开入口。
        String legacyToken = "legacy-regression-token-with-32-characters";
        f.sql("insert into dashboard_share(page_id,revision_id,token_hash,status) values(1,101,'"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacyToken.getBytes(StandardCharsets.UTF_8))) + "','ACTIVE')");
        equal("FIXED", f.service.getShareVersion(legacyToken, null).get("versionMode"));
        equal(101L, id(f.service.getShareVersion(legacyToken, "page-one").get("revisionId")));
        rejects(() -> f.service.getShareRuntime(legacyToken, "page-two"), "未授权");
        f.sql("update dashboard_page set is_deleted='1' where page_id=2");
        rejects(() -> f.service.getShareVersion(followToken, "page-two"), "回收站");
        f.sql("update dashboard_page set is_deleted='0' where page_id=2");
        f.sql("update dashboard_share set expires_at='2000-01-01 00:00:00' where share_id=" + followId);
        rejects(() -> f.service.getShareVersion(followToken, null), "过期");
        rejects(() -> f.service.updateShareVersionMode(1L, followId, Map.of("versionMode", "FOLLOW_PUBLISHED")), "过期");
        f.service.revokeShare(1L, id(fixed.get("shareId")));
        rejects(() -> f.service.getShareVersion(fixedToken, null), "撤销");
        rejects(() -> f.service.getShareRuntime(fixedToken), "撤销");
        System.out.println("Dashboard share version regression passed: " + checks + " assertions");
    }

    private static final class Fixture
    {
        final UnpooledDataSource source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:dashboard-share-modes;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        final DashboardService service;
        boolean metadataOnly;
        boolean failSecondMember;
        int touches;

        Fixture() throws Exception
        {
            String audit = ", create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp, remark varchar(255)";
            sql("create table dashboard_page_folder(folder_id bigint primary key, folder_name varchar(100))");
            sql("create table dashboard_page(page_id bigint primary key, page_code varchar(64), page_name varchar(100), folder_id bigint, status varchar(1), is_deleted varchar(1), delete_by varchar(64), delete_time timestamp, current_revision_id bigint" + audit + ")");
            sql("create table dashboard_page_revision(revision_id bigint auto_increment primary key, page_id bigint, version_no int, status varchar(20), schema_json varchar(100000), schema_hash varchar(64), publish_note varchar(255), publish_by varchar(64), publish_time timestamp" + audit + ")");
            sql("create table dashboard_share(share_id bigint auto_increment primary key, page_id bigint, revision_id bigint, version_mode varchar(24) not null default 'FIXED', token_hash varchar(64), share_token varchar(128), expires_at timestamp, status varchar(20), created_by varchar(64), created_at timestamp, last_access_at timestamp)");
            sql("create table dashboard_share_page(share_id bigint, page_id bigint, revision_id bigint, is_entry varchar(1), sort_order int, created_at timestamp, primary key(share_id,page_id))");
            for (int page = 1; page <= 3; page++)
            {
                String code = page == 1 ? "one" : page == 2 ? "two" : "three";
                sql("insert into dashboard_page(page_id,page_code,page_name,folder_id,status,is_deleted) values(" + page + ",'page-" + code + "','页面" + page + "',0,'0','0')");
                publish(page, page * 100 + 1, 1, "old-widget");
            }
            Configuration configuration = new Configuration(new Environment("regression", new SpringManagedTransactionFactory(), source));
            try (InputStream xml = getClass().getResourceAsStream("/mapper/system/DashboardMapper.xml"))
            {
                new XMLMapperBuilder(xml, configuration, "DashboardMapper.xml", configuration.getSqlFragments()).parse();
            }
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
            DashboardMapper real = new SqlSessionTemplate(factory).getMapper(DashboardMapper.class);
            DashboardMapper observed = (DashboardMapper) Proxy.newProxyInstance(DashboardMapper.class.getClassLoader(),
                    new Class<?>[] { DashboardMapper.class }, (proxy, method, args) -> {
                        if (metadataOnly && Set.of("selectRevisionById", "selectDatasetByCode", "touchShare").contains(method.getName()))
                            throw new AssertionError("Metadata endpoint performed heavy or mutating query: " + method.getName());
                        if (method.getName().equals("touchShare")) touches++;
                        if (failSecondMember && method.getName().equals("updateSharePageRevision") && id(args[1]) == 2)
                            throw new ServiceException("注入失败");
                        if (method.getName().equals("selectDatasetByCode"))
                            return Map.of("datasetCode", args[0], "dataType", "JSON", "status", "ACTIVE",
                                    "configJson", "{\"payload\":[{\"value\":42}]}", "paramSchemaJson", "[]", "fieldSchemaJson", "[]");
                        try { return method.invoke(real, args); }
                        catch (InvocationTargetException ex) { throw ex.getCause(); }
                    });
            DashboardService target = new DashboardService();
            inject(target, "dashboardMapper", observed);
            inject(target, "objectMapper", JSON);
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            service = (DashboardService) proxy.getProxy();
        }

        void publish(int page, int revision, int version, String widget) throws Exception
        {
            sql("update dashboard_page_revision set status='ARCHIVED' where page_id=" + page + " and status='PUBLISHED'");
            String schema = JSON.writeValueAsString(Map.of("widgets", List.of(Map.of("id", widget, "binding", Map.of("datasetCode", "bound")))));
            try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "insert into dashboard_page_revision(revision_id,page_id,version_no,status,schema_json) values(?,?,?,'PUBLISHED',?)"))
            {
                statement.setLong(1, revision); statement.setLong(2, page); statement.setInt(3, version); statement.setString(4, schema);
                statement.executeUpdate();
            }
            sql("update dashboard_page set current_revision_id=" + revision + " where page_id=" + page);
        }

        void sql(String value) throws Exception
        {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) { statement.execute(value); }
        }

        Object scalar(String value) throws Exception
        {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(value))
            {
                if (!rows.next()) throw new AssertionError("Missing regression row");
                return rows.getObject(1);
            }
        }

        long memberRevision(long share, long page) throws Exception { return id(scalar("select revision_id from dashboard_share_page where share_id=" + share + " and page_id=" + page)); }
        String shareMode(long share) throws Exception { return String.valueOf(scalar("select version_mode from dashboard_share where share_id=" + share)); }

        Map<String, Object> protectedFields(long share) throws Exception
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", scalar("select share_token from dashboard_share where share_id=" + share));
            result.put("hash", scalar("select token_hash from dashboard_share where share_id=" + share));
            result.put("expiry", scalar("select expires_at from dashboard_share where share_id=" + share));
            List<Long> ids = new ArrayList<>();
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select page_id from dashboard_share_page where share_id=" + share + " order by page_id"))
            {
                while (rows.next()) ids.add(rows.getLong(1));
            }
            result.put("members", ids);
            return result;
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception
    {
        Field field = DashboardService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static long id(Object value) { return Long.parseLong(String.valueOf(value)); }
    private static void equal(Object expected, Object actual)
    {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
        checks++;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable action, String message)
    {
        try { action.run(); throw new AssertionError("Expected rejection containing " + message); }
        catch (ServiceException expected) { check(expected.getMessage().contains(message), "Unexpected error: " + expected.getMessage()); }
    }
}
