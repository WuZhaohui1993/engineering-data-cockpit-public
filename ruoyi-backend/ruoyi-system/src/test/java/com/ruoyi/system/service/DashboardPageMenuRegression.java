package com.ruoyi.system.service;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardMapper;
import com.ruoyi.system.mapper.SysMenuMapper;

/**
 * 无数据库、无测试框架依赖的菜单配置回归检查。
 * Maven test-compile 后，以 test-classes、主模块 classes 和应用依赖作为 classpath 执行 main。
 * Mapper 代理仅允许菜单查询/保存；任何额外数据库操作都会使检查失败。
 */
public final class DashboardPageMenuRegression
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;

    public static void main(String[] args) throws Exception
    {
        Fixture created = new Fixture();
        Map<String, Object> result = created.configure();
        equal(true, result.get("created"));
        equal(1, created.inserts);
        equal(0, created.updates);
        equal(2800L, created.saved.getParentId());
        equal(7, created.saved.getOrderNum());
        equal("DashboardRuntime42", created.saved.getRouteName());
        equal("runtime/code/project-overview", created.saved.getPath());
        equal("dashboard:page:view", created.saved.getPerms());
        equal(Map.of("dashboardPageCode", "project-overview"), JSON.readValue(created.saved.getQuery(), Map.class));

        Fixture moved = new Fixture();
        SysMenu existing = existingMenu();
        existing.setQuery("{\"pageCode\":\"business-filter\",\"zone\":\"east\"}");
        moved.matches.add(existing);
        moved.parent = null; // 已移走的菜单不再依赖默认大屏目录存在。
        result = moved.configure();
        equal(false, result.get("created"));
        equal(0, moved.inserts);
        equal(1, moved.updates);
        equal(9L, moved.saved.getParentId());
        equal("custom-overview", moved.saved.getPath());
        equal("我的运行页", moved.saved.getMenuName());
        equal(27, moved.saved.getOrderNum());
        equal("1", moved.saved.getStatus());
        equal("1", moved.saved.getVisible());
        equal("custom:page:view", moved.saved.getPerms());
        equal("user", moved.saved.getIcon());
        equal(Map.of("pageCode", "business-filter", "zone", "east", "dashboardPageCode", "project-overview"),
                JSON.readValue(moved.saved.getQuery(), Map.class));
        equal("/dashboard/runtime/code/project-overview", result.get("url"));
        moved.configure();
        equal(0, moved.inserts);
        equal(2, moved.updates);

        Fixture legacy = new Fixture();
        SysMenu legacyMenu = existingMenu();
        legacyMenu.setQuery("");
        legacy.matches.add(legacyMenu);
        legacy.configure();
        equal(Map.of("dashboardPageCode", "project-overview"), JSON.readValue(legacy.saved.getQuery(), Map.class));
        equal(9L, legacy.saved.getParentId());

        Fixture duplicate = new Fixture();
        duplicate.matches.add(existingMenu());
        duplicate.matches.add(existingMenu());
        rejects(duplicate, "多个运行菜单");
        Fixture collision = new Fixture();
        collision.collisions.add(existingMenu());
        rejects(collision, "被其他菜单使用");
        Fixture disabled = new Fixture();
        disabled.page.put("status", "1");
        rejects(disabled, "已停用");
        Fixture unpublished = new Fixture();
        unpublished.revision = null;
        rejects(unpublished, "尚未发布");
        Fixture missingParent = new Fixture();
        missingParent.parent = null;
        rejects(missingParent, "未找到");
        Fixture invalidQuery = new Fixture();
        SysMenu invalid = existingMenu();
        invalid.setQuery("invalid-json");
        invalidQuery.matches.add(invalid);
        rejects(invalidQuery, "格式不合法");
        System.out.println("Dashboard page menu regression passed: " + checks + " assertions");
    }

    private static SysMenu existingMenu()
    {
        SysMenu menu = new SysMenu();
        menu.setMenuId(4000L);
        menu.setParentId(9L);
        menu.setMenuName("我的运行页");
        menu.setPath("custom-overview");
        menu.setComponent("dashboard/runtime/index");
        menu.setRouteName("DashboardRuntime42");
        menu.setMenuType("C");
        menu.setOrderNum(27);
        menu.setStatus("1");
        menu.setVisible("1");
        menu.setPerms("custom:page:view");
        menu.setIcon("user");
        return menu;
    }

    private static void rejects(Fixture fixture, String message) throws Exception
    {
        try
        {
            fixture.configure();
            throw new AssertionError("Expected rejection containing: " + message);
        }
        catch (ServiceException ex)
        {
            equal(true, ex.getMessage().contains(message));
            equal(0, fixture.inserts);
            equal(0, fixture.updates);
        }
    }

    private static void equal(Object expected, Object actual)
    {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("Expected " + expected + " but was " + actual);
        checks++;
    }

    private static void inject(Object target, String name, Object value) throws Exception
    {
        Field field = DashboardService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture
    {
        final DashboardService service = new DashboardService();
        final Map<String, Object> page = new LinkedHashMap<>(Map.of(
                "pageId", 42L, "pageCode", "project-overview", "pageName", "项目概况",
                "status", "0", "isDeleted", "0", "currentRevisionId", 7L));
        Map<String, Object> revision = Map.of("status", "PUBLISHED", "versionNo", 2,
                "revisionId", 7L, "schemaJson", "{\"widgets\":[]}");
        final List<SysMenu> matches = new ArrayList<>();
        final List<SysMenu> collisions = new ArrayList<>();
        SysMenu parent = new SysMenu();
        SysMenu saved;
        int inserts;
        int updates;

        Fixture() throws Exception
        {
            parent.setMenuId(2800L);
            inject(service, "dashboardMapper", Proxy.newProxyInstance(DashboardMapper.class.getClassLoader(),
                    new Class<?>[]{DashboardMapper.class}, (proxy, method, args) -> {
                        switch (method.getName())
                        {
                            case "selectPageById": return page;
                            case "selectRevisionById": return revision;
                            case "selectRevisionList": return List.of();
                            default: throw new AssertionError("Unexpected dashboard operation: " + method.getName());
                        }
                    }));
            inject(service, "sysMenuMapper", Proxy.newProxyInstance(SysMenuMapper.class.getClassLoader(),
                    new Class<?>[]{SysMenuMapper.class}, (proxy, method, args) -> {
                        switch (method.getName())
                        {
                            case "selectDashboardPageMenus":
                                equal("runtime/code/project-overview", args[0]);
                                equal("DashboardRuntime42", args[1]);
                                equal("project-overview", args[2]);
                                return matches;
                            case "selectMenuByParentAndPath": return parent;
                            case "selectMenusByPathOrRouteName": return collisions;
                            case "selectMaxOrderNum": return 6;
                            case "insertMenu":
                                inserts++;
                                saved = (SysMenu) args[0];
                                saved.setMenuId(4001L);
                                return 1;
                            case "updateMenu":
                                updates++;
                                saved = (SysMenu) args[0];
                                return 1;
                            default: throw new AssertionError("Unexpected menu operation: " + method.getName());
                        }
                    }));
        }

        Map<String, Object> configure()
        {
            return service.configurePageMenu(42L, "admin");
        }
    }
}
