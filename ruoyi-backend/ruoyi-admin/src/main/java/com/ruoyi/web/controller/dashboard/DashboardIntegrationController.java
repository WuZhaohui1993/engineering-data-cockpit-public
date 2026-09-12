package com.ruoyi.web.controller.dashboard;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.DashboardIntegrationService;

/**
 * 第三方数据接入管理接口。
 *
 * 管理入口使用平台后台权限；外部系统推送由独立的 Open API Controller
 * 处理，不复用这里的 JWT 或页面权限。
 */
@RestController
@RequestMapping({"/dashboard/integration", "/api/dashboard/integration"})
public class DashboardIntegrationController extends BaseController
{
    @Autowired
    private DashboardIntegrationService integrationService;

    @PreAuthorize("@ss.hasPermi('dashboard:integration:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        return success(integrationService.listIntegrations(keyword, environment, status, folderId, includeChildren));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:list')")
    @GetMapping("/page")
    public TableDataInfo page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String environment, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long folderId, @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(integrationService.listIntegrationsPage(keyword, environment, status,
                folderId, includeChildren, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:view')")
    @GetMapping("/{integrationId:\\d+}")
    public AjaxResult detail(@PathVariable Long integrationId)
    {
        return success(integrationService.getIntegration(integrationId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:add')")
    @Log(title = "第三方接入方", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(integrationService.createIntegration(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:edit')")
    @Log(title = "第三方接入方", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(integrationService.updateIntegration(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:delete')")
    @Log(title = "第三方接入方", businessType = BusinessType.DELETE)
    @DeleteMapping("/{integrationId:\\d+}")
    public AjaxResult remove(@PathVariable Long integrationId)
    {
        return toAjax(integrationService.deleteIntegration(integrationId));
    }

    @PreAuthorize("@ss.hasAnyPermi('dashboard:integration:edit,dashboard:integration:pause,dashboard:integration:revoke')")
    @Log(title = "第三方接入方状态", businessType = BusinessType.UPDATE)
    @PostMapping("/{integrationId:\\d+}/status")
    public AjaxResult status(@PathVariable Long integrationId, @RequestBody Map<String, Object> body)
    {
        return success(integrationService.updateIntegrationStatus(integrationId,
                body == null ? "" : String.valueOf(body.get("status")), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:key')")
    @GetMapping("/{integrationId:\\d+}/keys")
    public AjaxResult keys(@PathVariable Long integrationId)
    {
        return success(integrationService.listIntegrationKeys(integrationId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:key')")
    @Log(title = "第三方接入密钥", businessType = BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    @PostMapping("/{integrationId:\\d+}/keys")
    public AjaxResult addKey(@PathVariable Long integrationId, @RequestBody Map<String, Object> body)
    {
        return success(integrationService.createIntegrationKey(integrationId,
                body == null ? Collections.emptyMap() : body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:key')")
    @Log(title = "第三方接入密钥", businessType = BusinessType.UPDATE)
    @DeleteMapping("/key/{integrationKeyId:\\d+}")
    public AjaxResult revokeKey(@PathVariable Long integrationKeyId)
    {
        return toAjax(integrationService.revokeIntegrationKey(integrationKeyId, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:view')")
    @GetMapping("/batches")
    public AjaxResult batches(@RequestParam(required = false) Long integrationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") Integer limit)
    {
        return success(integrationService.listBatches(integrationId, status, limit));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:view')")
    @GetMapping("/batches/page")
    public TableDataInfo batchesPage(@RequestParam(required = false) Long integrationId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(integrationService.listBatchesPage(integrationId, status, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:view')")
    @GetMapping("/batch/{batchId:\\d+}")
    public AjaxResult batch(@PathVariable Long batchId)
    {
        return success(integrationService.getBatch(batchId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:deadletter')")
    @GetMapping("/dead-letters")
    public AjaxResult deadLetters(@RequestParam(required = false) Long integrationId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit)
    {
        return success(integrationService.listDeadLetters(integrationId, keyword, limit));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:deadletter')")
    @GetMapping("/dead-letters/page")
    public TableDataInfo deadLettersPage(@RequestParam(required = false) Long integrationId,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(integrationService.listDeadLettersPage(integrationId, keyword, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:replay')")
    @Log(title = "第三方接入死信重放", businessType = BusinessType.UPDATE)
    @PostMapping("/dead-letters/{messageId:\\d+}/replay")
    public AjaxResult replay(@PathVariable Long messageId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        return success(integrationService.replayDeadLetter(messageId, reason, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:edit')")
    @Log(title="接口配置查看", businessType=BusinessType.OTHER, isSaveRequestData=false, isSaveResponseData=false)
    @GetMapping("/endpoints/item/{endpointId}/configuration")
    public ResponseEntity<AjaxResult> endpointConfiguration(@PathVariable Long endpointId)
    {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(success(integrationService.getEndpointConfiguration(endpointId)));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:key')")
    @Log(title="接入密钥查看", businessType=BusinessType.OTHER, isSaveRequestData=false, isSaveResponseData=false)
    @GetMapping("/key/{keyId}/configuration")
    public ResponseEntity<AjaxResult> keyConfiguration(@PathVariable Long keyId)
    {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(success(integrationService.getIntegrationKeyConfiguration(keyId)));
    }

    /* ----------------------------- 出站 endpoint 目录 ----------------------------- */

    @PreAuthorize("@ss.hasAnyPermi('dashboard:integration:list,dashboard:dataset:list')")
    @GetMapping("/endpoints/{sourceCode}")
    public AjaxResult endpointList(@PathVariable String sourceCode)
    {
        return success(integrationService.listEndpoints(sourceCode));
    }

    @PreAuthorize("@ss.hasAnyPermi('dashboard:integration:list,dashboard:dataset:list')")
    @GetMapping("/endpoints/{sourceCode}/page")
    public TableDataInfo endpointPage(@PathVariable String sourceCode,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(integrationService.listEndpointsPage(sourceCode, keyword, status, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:add')")
    @Log(title = "第三方出站 endpoint", businessType = BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    @PostMapping("/endpoints/{sourceCode}")
    public AjaxResult endpointAdd(@PathVariable String sourceCode, @RequestBody Map<String, Object> body)
    {
        return success(integrationService.createEndpoint(sourceCode, body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:edit')")
    @Log(title = "第三方出站 endpoint", businessType = BusinessType.UPDATE, isSaveRequestData=false, isSaveResponseData=false)
    @PutMapping("/endpoints/{sourceCode}")
    public AjaxResult endpointEdit(@PathVariable String sourceCode, @RequestBody Map<String, Object> body)
    {
        return success(integrationService.updateEndpoint(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:delete')")
    @Log(title = "第三方出站 endpoint", businessType = BusinessType.DELETE)
    @DeleteMapping("/endpoints/item/{endpointId:\\d+}")
    public AjaxResult endpointRemove(@PathVariable Long endpointId)
    {
        return toAjax(integrationService.deleteEndpoint(endpointId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:integration:test')")
    @Log(title = "第三方出站 endpoint 测试", businessType = BusinessType.OTHER, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/endpoints/{sourceCode}/{endpointCode}/test")
    public AjaxResult endpointTest(@PathVariable String sourceCode, @PathVariable String endpointCode,
            @RequestBody(required = false) Map<String, Object> testParams)
    {
        return success(integrationService.testEndpoint(sourceCode, endpointCode,
                testParams == null ? Collections.emptyMap() : testParams));
    }
}
