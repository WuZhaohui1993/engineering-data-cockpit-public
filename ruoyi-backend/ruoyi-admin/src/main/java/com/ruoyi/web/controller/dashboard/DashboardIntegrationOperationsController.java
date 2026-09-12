package com.ruoyi.web.controller.dashboard;

import java.util.Map;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.DashboardIntegrationOperations;
import com.ruoyi.system.service.DashboardService;
import com.ruoyi.system.service.DashboardUpstreamWebSocketPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dashboard/integration/operations")
public class DashboardIntegrationOperationsController extends BaseController
{
    @Autowired private DashboardIntegrationOperations operations;
    @Autowired private DashboardService dashboard;
    @Autowired private DashboardUpstreamWebSocketPool websockets;
    @GetMapping("/metrics")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public AjaxResult metrics(@RequestParam(required=false) String category,@RequestParam(required=false) String resourceCode)
    { return success(operations.metrics(category,resourceCode)); }
    @GetMapping("/audits")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public TableDataInfo audits(@RequestParam(required=false) String category,@RequestParam(required=false) String resourceCode)
    { startPage(); return getDataTable(operations.audits(category,resourceCode)); }
    @GetMapping("/alerts")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public TableDataInfo alerts(@RequestParam(required=false) String category,@RequestParam(required=false) String resourceCode)
    { startPage(); return getDataTable(operations.alerts(category,resourceCode)); }
    @GetMapping("/resources")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public AjaxResult resources() { return success(operations.resources()); }
    @GetMapping("/websockets")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public AjaxResult websockets(@RequestParam(required=false) String resourceCode)
    { return success(websockets.monitoring(resourceCode)); }
    @PostMapping("/alerts/{id}/ack")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:ack')")
    @Log(title="接入告警确认",businessType=BusinessType.UPDATE)
    public AjaxResult acknowledge(@PathVariable Long id) { return toAjax(operations.acknowledge(id,getUsername())); }
    @PostMapping("/inbound/{integrationId}/dataset")
    @PreAuthorize("@ss.hasPermi('dashboard:integration:view') && @ss.hasPermi('dashboard:dataset:edit')")
    @Log(title="从推送接入创建数据集",businessType=BusinessType.INSERT)
    public AjaxResult createDataset(@PathVariable Long integrationId,@RequestBody Map<String,Object> body)
    { return success(dashboard.createIntegrationDataset(integrationId,body,getUsername())); }
}
