package com.ruoyi.web.controller.dashboard;

import java.util.Map;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.DashboardIntegrationPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dashboard/integration/operations/policy")
public class DashboardIntegrationPolicyController extends BaseController
{
    @Autowired private DashboardIntegrationPolicyService policies;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('dashboard:integration:monitor')")
    public AjaxResult getPolicy() { return success(policies.getPolicy()); }

    @PutMapping
    @PreAuthorize("@ss.hasPermi('dashboard:integration:policy')")
    @Log(title = "接入公共保留策略", businessType = BusinessType.UPDATE)
    public AjaxResult updatePolicy(@RequestBody Map<String, Object> body)
    { return success(policies.updatePolicy(body, getUsername())); }
}
