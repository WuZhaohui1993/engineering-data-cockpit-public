package com.ruoyi.web.controller.dashboard;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.DashboardDataFolderService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复用各对象已有权限，目录管理不会授予连接、密钥或接入操作权限。 */
@RestController
@RequestMapping({"/dashboard/data-folder", "/api/dashboard/data-folder"})
public class DashboardDataFolderController extends BaseController
{
    @Autowired
    private DashboardDataFolderService folders;

    @PreAuthorize("((#scope == 'dataset' || #scope == 'source') && @ss.hasAnyPermi('dashboard:dataset:list,dashboard:dataset:edit')) || (#scope == 'integration' && @ss.hasAnyPermi('dashboard:integration:list,dashboard:integration:view,dashboard:integration:add,dashboard:integration:edit,dashboard:integration:delete')) || (#scope == 'page' && @ss.hasAnyPermi('dashboard:page:list,dashboard:page:add,dashboard:page:edit,dashboard:page:delete')) || (#scope == 'resource' && @ss.hasAnyPermi('dashboard:resource:list,dashboard:resource:edit,dashboard:resource:folder'))")
    @GetMapping("/{scope}/list")
    public AjaxResult list(@PathVariable String scope) { return success(folders.list(scope)); }

    @PreAuthorize("((#scope == 'dataset' || #scope == 'source') && @ss.hasPermi('dashboard:dataset:edit')) || (#scope == 'integration' && @ss.hasPermi('dashboard:integration:edit')) || (#scope == 'page' && @ss.hasPermi('dashboard:page:add')) || (#scope == 'resource' && @ss.hasPermi('dashboard:resource:folder'))")
    @Log(title = "数据文件夹", businessType = BusinessType.INSERT)
    @PostMapping("/{scope}")
    public AjaxResult create(@PathVariable String scope, @RequestBody Map<String, Object> body)
    { return success(folders.create(scope, body, getUsername())); }

    @PreAuthorize("((#scope == 'dataset' || #scope == 'source') && @ss.hasPermi('dashboard:dataset:edit')) || (#scope == 'integration' && @ss.hasPermi('dashboard:integration:edit')) || (#scope == 'page' && @ss.hasPermi('dashboard:page:edit')) || (#scope == 'resource' && @ss.hasPermi('dashboard:resource:folder'))")
    @Log(title = "数据文件夹", businessType = BusinessType.UPDATE)
    @PutMapping("/{scope}")
    public AjaxResult update(@PathVariable String scope, @RequestBody Map<String, Object> body)
    { return toAjax(folders.update(scope, body, getUsername())); }

    @PreAuthorize("((#scope == 'dataset' || #scope == 'source') && @ss.hasPermi('dashboard:dataset:edit')) || (#scope == 'integration' && @ss.hasPermi('dashboard:integration:delete')) || (#scope == 'page' && @ss.hasPermi('dashboard:page:delete')) || (#scope == 'resource' && @ss.hasPermi('dashboard:resource:folder'))")
    @Log(title = "数据文件夹", businessType = BusinessType.DELETE)
    @DeleteMapping("/{scope}/{folderId}")
    public AjaxResult delete(@PathVariable String scope, @PathVariable Long folderId)
    { return toAjax(folders.delete(scope, folderId)); }

    @PreAuthorize("((#scope == 'dataset' || #scope == 'source') && @ss.hasPermi('dashboard:dataset:edit')) || (#scope == 'integration' && @ss.hasPermi('dashboard:integration:edit')) || (#scope == 'page' && @ss.hasPermi('dashboard:page:edit')) || (#scope == 'resource' && @ss.hasPermi('dashboard:resource:edit'))")
    @Log(title = "数据归档移动", businessType = BusinessType.UPDATE)
    @PutMapping("/{scope}/move")
    public AjaxResult move(@PathVariable String scope, @RequestBody Map<String, Object> body)
    { return toAjax(folders.move(scope, body, getUsername())); }
}
