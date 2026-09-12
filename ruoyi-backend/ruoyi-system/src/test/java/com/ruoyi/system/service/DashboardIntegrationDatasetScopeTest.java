package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证项目可选后仍保持接入方隔离和已有外部项目范围。 */
public class DashboardIntegrationDatasetScopeTest
{
    private Map<String,Object> create(List<String> scope, String project) throws Exception
    {
        return create(scope, project, null);
    }

    private Map<String,Object> create(List<String> scope, String project, String groupCode) throws Exception
    {
        DashboardService service=new DashboardService() {
            @Override public Map<String,Object> createDataset(Map<String,Object> body,String username) { return body; }
        };
        DashboardIntegrationService integration=new DashboardIntegrationService() {
            @Override public Map<String,Object> getIntegration(Long id) {
                return Map.of("integrationId",id,"integrationCode","scope-test","projectScope",scope);
            }
        };
        Field field=DashboardService.class.getDeclaredField("dashboardIntegrationService");
        field.setAccessible(true); field.set(service,integration);
        Map<String,Object> body=new HashMap<>();
        body.put("datasetCode","scope-dataset");body.put("datasetName","范围回归");
        if(project!=null) body.put("projectCode",project);
        if(groupCode!=null) body.put("groupCode",groupCode);
        return service.createIntegrationDataset(42L,body,"test");
    }

    @Test public void noProjectStillRestrictsIntegrationAndActiveRecords() throws Exception
    {
        String config=String.valueOf(create(List.of(),null).get("configJson"));
        assertTrue(config.contains("WHERE integration_id=42 AND status='ACTIVE'"));
        assertFalse(config.contains("AND project_code="));
    }

    @Test public void explicitProjectIsKept() throws Exception
    {
        String config=String.valueOf(create(List.of(),"demo-1").get("configJson"));
        assertTrue(config.contains("integration_id=42 AND project_code='demo-1' AND status='ACTIVE'"));
    }

    @Test public void safeResponseProjectScopeIsEnforced() throws Exception
    {
        for(String project:Arrays.asList(null,"","other-project")) {
            try {create(List.of("allowed-project"),project);fail("scope bypass accepted");}
            catch(ServiceException expected) {assertTrue(expected.getMessage().contains("外部项目"));}
        }
        assertTrue(String.valueOf(create(List.of("allowed-project"),"allowed-project").get("configJson"))
                .contains("project_code='allowed-project'"));
    }

    @Test public void projectCannotInjectSql() throws Exception
    {
        try {create(List.of(),"x' OR 1=1 --");fail("unsafe project accepted");}
        catch(ServiceException expected) {assertTrue(expected.getMessage().contains("外部项目编码"));}
    }

    @Test public void selectedDatasetFolderIsForwardedWithoutChangingProjectScope() throws Exception
    {
        Map<String,Object> dataset=create(List.of("allowed-project"),"allowed-project","existing-topic");
        assertEquals("existing-topic",dataset.get("groupCode"));
        assertTrue(String.valueOf(dataset.get("configJson")).contains("integration_id=42 AND project_code='allowed-project'"));
        assertEquals("DRAFT",dataset.get("status"));
    }
}
