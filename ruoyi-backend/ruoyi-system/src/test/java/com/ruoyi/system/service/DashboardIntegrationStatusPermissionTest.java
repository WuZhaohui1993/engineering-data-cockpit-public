package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.Assert.*;

/** 两个状态写入入口必须遵守同一目标动作权限，编辑表单不能绕过暂停和吊销权限。 */
public class DashboardIntegrationStatusPermissionTest
{
    private static final String EDIT = "dashboard:integration:edit";
    private static final String PAUSE = "dashboard:integration:pause";
    private static final String REVOKE = "dashboard:integration:revoke";
    private static final List<String> STATUSES = List.of("DRAFT", "ACTIVE", "PAUSED", "REVOKED");

    @After
    public void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void bothWritePathsEnforceTheRequestedAction() throws Exception
    {
        List<Set<String>> grants = List.of(Set.of(), Set.of(EDIT), Set.of(PAUSE), Set.of(REVOKE),
                Set.of(EDIT, PAUSE), Set.of(EDIT, REVOKE));
        for (boolean editForm : List.of(false, true))
            for (String target : STATUSES)
                for (Set<String> permissions : grants)
                {
                    Fixture fixture = new Fixture("ACTIVE".equals(target) ? "PAUSED" : "ACTIVE", true);
                    login(permissions);
                    String required = "PAUSED".equals(target) ? PAUSE : "REVOKED".equals(target) ? REVOKE : EDIT;
                    if (permissions.contains(required))
                    {
                        fixture.change(editForm, target);
                        assertEquals(target, fixture.row.get("status"));
                        assertEquals(1, fixture.writes);
                    }
                    else
                    {
                        try
                        {
                            fixture.change(editForm, target);
                            fail("目标动作缺少权限仍被写入: " + target + ", editForm=" + editForm);
                        }
                        catch (AccessDeniedException expected)
                        {
                            assertEquals(0, fixture.writes);
                        }
                    }
                }
    }

    @Test
    public void unchangedStatusDoesNotDemandAnAdditionalActionPermission() throws Exception
    {
        login(Set.of());
        for (boolean editForm : List.of(false, true))
            for (String status : STATUSES)
            {
                Fixture fixture = new Fixture(status, true);
                fixture.change(editForm, status);
                assertEquals(status, fixture.row.get("status"));
                assertEquals(1, fixture.writes);
            }
    }

    @Test
    public void editingOtherFieldsDoesNotRequireAStatusPermission() throws Exception
    {
        login(Set.of(EDIT));
        Fixture fixture = new Fixture("REVOKED", true);
        fixture.service.updateIntegration(Map.of("integrationId", 42L, "remark", "更新备注"), "test");
        assertEquals("REVOKED", fixture.row.get("status"));
        assertEquals("更新备注", fixture.row.get("remark"));
        assertEquals(1, fixture.writes);
    }

    @Test
    public void administratorWildcardStillAllowsStatusChanges() throws Exception
    {
        login(Set.of("*:*:*"));
        for (boolean editForm : List.of(false, true))
            for (String target : STATUSES)
            {
                Fixture fixture = new Fixture("ACTIVE".equals(target) ? "PAUSED" : "ACTIVE", true);
                fixture.change(editForm, target);
                assertEquals(target, fixture.row.get("status"));
            }
    }

    @Test
    public void normalizedStatusCannotBypassTheActionPermission() throws Exception
    {
        login(Set.of(EDIT));
        for (boolean editForm : List.of(false, true))
            for (String target : List.of(" paused ", " revoked "))
            {
                Fixture fixture = new Fixture("ACTIVE", true);
                try
                {
                    fixture.change(editForm, target);
                    fail("大小写或空白绕过了目标动作权限");
                }
                catch (AccessDeniedException expected)
                {
                    assertEquals(0, fixture.writes);
                }
            }
    }

    @Test
    public void existingStatusAndActiveKeyValidationRemainInForce() throws Exception
    {
        login(Set.of("*:*:*"));
        for (boolean editForm : List.of(false, true))
            for (String target : List.of("ACTIVE", "UNKNOWN"))
            {
                Fixture fixture = new Fixture("PAUSED", false);
                try
                {
                    fixture.change(editForm, target);
                    fail("原有状态或启用密钥校验失效");
                }
                catch (ServiceException expected)
                {
                    assertEquals(0, fixture.writes);
                    assertTrue(expected.getMessage().contains("ACTIVE".equals(target) ? "启用密钥" : "状态不合法"));
                }
            }
    }

    private void login(Set<String> permissions)
    {
        LoginUser user = new LoginUser();
        user.setPermissions(permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    private static class Fixture
    {
        final Map<String, Object> row = new HashMap<>();
        final DashboardIntegrationService service;
        int writes;

        Fixture(String status, boolean hasActiveKey) throws Exception
        {
            row.put("integrationId", 42L);
            row.put("status", status);
            service = new DashboardIntegrationService()
            {
                @Override public Map<String, Object> getIntegration(Long id)
                {
                    return new HashMap<>(row);
                }
            };
            DashboardIntegrationMapper mapper = (DashboardIntegrationMapper) Proxy.newProxyInstance(
                    DashboardIntegrationMapper.class.getClassLoader(), new Class<?>[]{DashboardIntegrationMapper.class},
                    (proxy, method, args) ->
                    {
                        switch (method.getName())
                        {
                            case "selectIntegrationById": return new HashMap<>(row);
                            case "selectIntegrationKeys":
                                return hasActiveKey ? List.of(Map.of("status", "ACTIVE")) : List.of();
                            case "updateIntegration":
                                @SuppressWarnings("unchecked")
                                Map<String, Object> changes = (Map<String, Object>) args[0];
                                row.putAll(changes);
                                writes++;
                                return 1;
                            case "updateIntegrationStatus":
                                row.put("status", args[1]);
                                writes++;
                                return 1;
                            default: throw new AssertionError("测试意外调用 Mapper: " + method.getName());
                        }
                    });
            Field field = DashboardIntegrationService.class.getDeclaredField("integrationMapper");
            field.setAccessible(true);
            field.set(service, mapper);
        }

        void change(boolean editForm, String target)
        {
            if (editForm)
                service.updateIntegration(Map.of("integrationId", 42L, "status", target), "test");
            else
                service.updateIntegrationStatus(42L, target, "test");
        }
    }
}
