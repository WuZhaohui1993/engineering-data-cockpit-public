package com.ruoyi.web.controller.dashboard;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.DashboardIntegrationService;
import com.ruoyi.system.service.DashboardDataFolderService;
import com.ruoyi.system.service.DashboardService;

/**
 * 轻量大屏管理和运行接口。
 *
 * 同时保留 /dashboard 和 /api/dashboard 两个前缀，方便现有代理和外部
 * 只读入口逐步迁移；前端默认使用 /dashboard。
 */
@RestController
@RequestMapping({ "/dashboard", "/api/dashboard" })
public class DashboardController extends BaseController
{
    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private DashboardDataFolderService dataFolders;

    @Autowired
    private DashboardIntegrationService dashboardIntegrationService;
    @Autowired
    private com.ruoyi.system.service.DashboardIntegrationOperations integrationOperations;

    @PreAuthorize("@ss.hasPermi('dashboard:page:list')")
    @GetMapping("/page/list")
    public TableDataInfo pageList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        List<Long> folderIds = dataFolders.filterIds("page", folderId, includeChildren);
        startPage();
        List<Map<String, Object>> rows = dashboardService.selectPageListByFolders(keyword, folderIds);
        return getDataTable(rows);
    }

    /**
     * 页面跳转目标选择器使用的只读列表。这里只返回已经发布且可运行的页面，
     * 避免设计器把草稿、停用页或回收站页面写入交互配置。
     */
    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list')")
    @GetMapping({ "/page/published", "/page/published/list" })
    public AjaxResult publishedPageList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        return success(dashboardService.selectPublishedPageListByFolders(keyword,
                dataFolders.filterIds("page", folderId, includeChildren)));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:list')")
    @GetMapping("/page/recycle")
    public TableDataInfo pageRecycle(@RequestParam(required = false) String keyword)
    {
        startPage();
        return getDataTable(dashboardService.selectPageRecycleList(keyword));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:list')")
    @GetMapping("/page/folders")
    public AjaxResult pageFolders()
    {
        return success(dashboardService.selectPageFolders());
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:list')")
    @GetMapping("/page/{pageId}")
    public AjaxResult page(@PathVariable Long pageId)
    {
        return success(dashboardService.getPage(pageId));
    }

    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list')")
    @GetMapping("/page/{pageId}/runtime")
    public AjaxResult runtimePage(@PathVariable Long pageId)
    {
        return success(dashboardService.getRuntimePage(pageId));
    }

    /** 登录运行页申请不透明媒体引用；来源地址和凭证只留在服务端。 */
    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list,dashboard:page:preview')")
    @PostMapping("/runtime/media/ref")
    public AjaxResult issueRuntimeMediaRef(@RequestBody Map<String, Object> body)
    {
        if (body == null) return error("媒体引用参数不能为空");
        try
        {
            Long pageId = bodyLong(body, "pageId");
            Long revisionId = bodyLong(body, "revisionId");
            if (pageId == null) return error("pageId 不合法");
            if (revisionId == null) revisionId = bodyLong(dashboardService.getRuntimePage(pageId), "revisionId");
            String widgetId = String.valueOf(body.getOrDefault("widgetId", ""));
            String datasetCode = String.valueOf(body.getOrDefault("datasetCode", ""));
            dashboardService.validateRuntimeMediaDatasetAccess(pageId, revisionId, widgetId, datasetCode);
            return success(dashboardIntegrationService.issueMediaRef(
                    String.valueOf(body.getOrDefault("candidateRef", "")), datasetCode,
                    pageId, revisionId, null));
        }
        catch (NumberFormatException ex)
        {
            return error("媒体页面参数不合法");
        }
    }

    /** 登录运行页读取媒体，先按引用绑定的页面和发布版本重新校验权限。 */
    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list,dashboard:page:preview')")
    @GetMapping("/runtime/media/{mediaRef}")
    public ResponseEntity<byte[]> runtimeMedia(@PathVariable String mediaRef)
    {
        try
        {
            DashboardIntegrationService.MediaBinding binding = dashboardIntegrationService.getMediaBinding(mediaRef);
            if (binding.shareId() != null || binding.pageId() == null || binding.revisionId() == null) {
                auditMedia(mediaRef,"DENIED","",0);return ResponseEntity.notFound().build();
            }
            dashboardService.validateRuntimeMediaAccess(binding.pageId(), binding.revisionId());
            DashboardIntegrationService.MediaPayload payload=dashboardIntegrationService.fetchMedia(mediaRef,binding.pageId(),binding.revisionId(),null);
            auditMedia(mediaRef,"SUCCESS",getUsername(),payload.bytes().length);
            return mediaResponse(payload);
        }
        catch (Exception ex)
        {
            auditMedia(mediaRef,"DENIED","",0);
            return ResponseEntity.notFound().build();
        }
    }

    /** 将已发布页面自动注册为“大屏管理”下的若依菜单。 */
    @PreAuthorize("@ss.hasPermi('dashboard:page:menu')")
    @Log(title = "大屏页面菜单", businessType = BusinessType.INSERT)
    @PostMapping("/page/{pageId}/menu")
    public AjaxResult configurePageMenu(@PathVariable Long pageId)
    {
        return success(dashboardService.configurePageMenu(pageId, getUsername()));
    }

    /** 按稳定页面编码读取当前已发布版本，供跨页面跳转和外部集成使用。 */
    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list')")
    @GetMapping({ "/page/code/{pageCode}/runtime", "/runtime/page/{pageCode}" })
    public AjaxResult runtimePageByCode(@PathVariable String pageCode)
    {
        return success(dashboardService.getRuntimePageByCode(pageCode));
    }

    /** 按稳定页面编码读取当前用户可预览的草稿或已发布版本。 */
    @PreAuthorize("@ss.hasPermi('dashboard:page:preview')")
    @GetMapping("/page/code/{pageCode}/preview")
    public AjaxResult previewPageByCode(@PathVariable String pageCode)
    {
        return success(dashboardService.getRuntimePageByCode(pageCode, true));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:preview')")
    @GetMapping("/page/{pageId}/preview")
    public AjaxResult previewPage(@PathVariable Long pageId)
    {
        return success(dashboardService.getRuntimePage(pageId, true));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:preview')")
    @GetMapping("/page/{pageId}/revision/{revisionId}/preview")
    public AjaxResult previewPageRevision(@PathVariable Long pageId, @PathVariable Long revisionId)
    {
        return success(dashboardService.getRevisionPreview(pageId, revisionId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:add')")
    @Log(title = "大屏页面", businessType = BusinessType.INSERT)
    @PostMapping("/page")
    public AjaxResult createPage(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createPage(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:add')")
    @Log(title = "大屏页面", businessType = BusinessType.OTHER)
    @PostMapping("/page/{pageId}/copy")
    public AjaxResult copyPage(@PathVariable Long pageId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return success(dashboardService.copyPage(pageId, body == null ? Collections.emptyMap() : body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:edit')")
    @Log(title = "大屏页面", businessType = BusinessType.UPDATE)
    @PutMapping("/page")
    public AjaxResult updatePage(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updatePage(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:delete')")
    @Log(title = "大屏页面", businessType = BusinessType.DELETE)
    @DeleteMapping("/page/{pageId}")
    public AjaxResult deletePage(@PathVariable Long pageId)
    {
        return toAjax(dashboardService.deletePage(pageId, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:restore')")
    @Log(title = "大屏回收站", businessType = BusinessType.UPDATE)
    @PostMapping("/page/{pageId}/restore")
    public AjaxResult restorePage(@PathVariable Long pageId)
    {
        return toAjax(dashboardService.restorePage(pageId, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:purge')")
    @Log(title = "大屏回收站", businessType = BusinessType.DELETE)
    @DeleteMapping("/page/{pageId}/purge")
    public AjaxResult purgePage(@PathVariable Long pageId)
    {
        return toAjax(dashboardService.purgePage(pageId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:purge')")
    @Log(title = "大屏回收站", businessType = BusinessType.DELETE)
    @DeleteMapping("/page/recycle")
    public AjaxResult purgeRecycleBin()
    {
        return toAjax(dashboardService.purgeRecycleBin());
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:add')")
    @Log(title = "大屏页面文件夹", businessType = BusinessType.INSERT)
    @PostMapping("/page/folder")
    public AjaxResult createPageFolder(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createPageFolder(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:edit')")
    @Log(title = "大屏页面文件夹", businessType = BusinessType.UPDATE)
    @PutMapping("/page/folder")
    public AjaxResult updatePageFolder(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updatePageFolder(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:delete')")
    @Log(title = "大屏页面文件夹", businessType = BusinessType.DELETE)
    @DeleteMapping("/page/folder/{folderId}")
    public AjaxResult deletePageFolder(@PathVariable Long folderId)
    {
        return toAjax(dashboardService.deletePageFolder(folderId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:edit')")
    @Log(title = "大屏页面草稿", businessType = BusinessType.UPDATE)
    @PutMapping("/page/{pageId}/draft")
    public AjaxResult saveDraft(@PathVariable Long pageId, @RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.saveDraft(pageId, body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:publish')")
    @Log(title = "大屏页面", businessType = BusinessType.UPDATE)
    @PostMapping("/page/{pageId}/publish")
    public AjaxResult publish(@PathVariable Long pageId, @RequestBody(required = false) Map<String, Object> body)
    {
        String note = body == null ? null : String.valueOf(body.getOrDefault("publishNote", ""));
        return toAjax(dashboardService.publishPage(pageId, getUsername(), note));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:rollback')")
    @Log(title = "大屏页面", businessType = BusinessType.UPDATE)
    @PostMapping("/page/{pageId}/rollback/{revisionId}")
    public AjaxResult rollback(@PathVariable Long pageId, @PathVariable Long revisionId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        String note = body == null ? null : String.valueOf(body.getOrDefault("note", ""));
        return toAjax(dashboardService.rollbackPage(pageId, revisionId, getUsername(), note));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:share:list')")
    @GetMapping("/page/{pageId}/shares")
    public AjaxResult shareList(@PathVariable Long pageId)
    {
        return success(dashboardService.selectShareList(pageId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:share:create')")
    @Log(title = "大屏分享", businessType = BusinessType.INSERT)
    @PostMapping("/page/{pageId}/shares")
    public AjaxResult createShare(@PathVariable Long pageId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return success(dashboardService.createShare(pageId, body == null ? Collections.emptyMap() : body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:share:create')")
    @Log(title = "大屏分享版本模式", businessType = BusinessType.UPDATE)
    @PutMapping("/page/{pageId}/shares/{shareId}")
    public AjaxResult updateShareVersionMode(@PathVariable Long pageId, @PathVariable Long shareId,
            @RequestBody Map<String, Object> body)
    {
        return success(dashboardService.updateShareVersionMode(pageId, shareId, body));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:share:revoke')")
    @Log(title = "大屏分享", businessType = BusinessType.UPDATE)
    @DeleteMapping("/page/{pageId}/shares/{shareId}")
    public AjaxResult revokeShare(@PathVariable Long pageId, @PathVariable Long shareId)
    {
        return toAjax(dashboardService.revokeShare(pageId, shareId));
    }

    /** 限时或永久分享只读入口：不返回管理字段、数据源配置或令牌哈希。 */
    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}")
    public AjaxResult shareRuntime(@PathVariable String token)
    {
        return success(dashboardService.getShareRuntime(token));
    }

    /** 分享集合中的指定页面入口；页面编码不在集合内时由服务端拒绝。 */
    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}/page/{pageCode}")
    public AjaxResult sharePageRuntime(@PathVariable String token, @PathVariable String pageCode)
    {
        return success(dashboardService.getShareRuntime(token, pageCode));
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}/version")
    public AjaxResult shareVersion(@PathVariable String token)
    {
        return success(dashboardService.getShareVersion(token, null));
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}/page/{pageCode}/version")
    public AjaxResult sharePageVersion(@PathVariable String token, @PathVariable String pageCode)
    {
        return success(dashboardService.getShareVersion(token, pageCode));
    }

    /** 分享页面申请媒体引用；页面身份由分享令牌和可选 pageCode 决定。 */
    @PreAuthorize("permitAll()")
    @PostMapping("/public/share/{token}/media/ref")
    public AjaxResult issueShareMediaRef(@PathVariable String token, @RequestBody Map<String, Object> body)
    {
        return issueShareMediaRef(token, null, body);
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/public/share/{token}/page/{pageCode}/media/ref")
    public AjaxResult issueSharePageMediaRef(@PathVariable String token, @PathVariable String pageCode,
            @RequestBody Map<String, Object> body)
    {
        return issueShareMediaRef(token, pageCode, body);
    }

    private AjaxResult issueShareMediaRef(String token, String pageCode, Map<String, Object> body)
    {
        if (body == null) return error("媒体引用参数不能为空");
        DashboardService.ShareMediaContext context = dashboardService.getShareMediaContext(token, pageCode);
        String widgetId = String.valueOf(body.getOrDefault("widgetId", ""));
        String datasetCode = String.valueOf(body.getOrDefault("datasetCode", ""));
        dashboardService.validateRuntimeMediaDatasetAccess(
                context.pageId(), context.revisionId(), widgetId, datasetCode);
        return success(dashboardIntegrationService.issueMediaRef(
                String.valueOf(body.getOrDefault("candidateRef", "")), datasetCode,
                context.pageId(), context.revisionId(), context.shareId()));
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}/media/{mediaRef}")
    public ResponseEntity<byte[]> shareMedia(@PathVariable String token, @PathVariable String mediaRef)
    {
        return shareMedia(token, null, mediaRef);
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/public/share/{token}/page/{pageCode}/media/{mediaRef}")
    public ResponseEntity<byte[]> sharePageMedia(@PathVariable String token, @PathVariable String pageCode,
            @PathVariable String mediaRef)
    {
        return shareMedia(token, pageCode, mediaRef);
    }

    private ResponseEntity<byte[]> shareMedia(String token, String pageCode, String mediaRef)
    {
        try
        {
            DashboardService.ShareMediaContext context = dashboardService.getShareMediaContext(token, pageCode);
            DashboardIntegrationService.MediaPayload payload=dashboardIntegrationService.fetchMedia(mediaRef,context.pageId(),context.revisionId(),context.shareId());
            auditMedia(mediaRef,"SUCCESS","share:"+context.shareId(),payload.bytes().length);
            return mediaResponse(payload);
        }
        catch (Exception ex)
        {
            auditMedia(mediaRef,"DENIED","",0);
            return ResponseEntity.notFound().build();
        }
    }

    private void auditMedia(String mediaRef,String outcome,String actor,long bytes)
    {
        integrationOperations.auditMedia(mediaRef,outcome,actor,bytes);
    }

    private ResponseEntity<byte[]> mediaResponse(DashboardIntegrationService.MediaPayload payload)
    {
        MediaType type;
        try { type = MediaType.parseMediaType(payload.contentType()); }
        catch (RuntimeException ex) { type = MediaType.APPLICATION_OCTET_STREAM; }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentLength(payload.bytes().length);
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        String disposition = payload.inline() ? "inline" : "attachment";
        headers.set("Content-Disposition", disposition + "; filename=\"" + payload.filename() + "\"");
        return ResponseEntity.ok().headers(headers).body(payload.bytes());
    }

    private Long bodyLong(Map<String, Object> body, String key)
    {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        return Long.valueOf(String.valueOf(value));
    }

    /**
     * 提供内置、只读的 GeoJSON 资源。地图边界不允许由页面配置提交任意 URL，
     * 仅从这个白名单目录读取，避免把地图组件变成文件或网络代理。
     */
    @PreAuthorize("permitAll()")
    @GetMapping(value = "/assets/map/{mapCode}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mapAsset(@PathVariable String mapCode)
    {
        if (mapCode == null || !mapCode.matches("[a-z0-9][a-z0-9_-]{0,63}"))
        {
            return ResponseEntity.notFound().build();
        }
        try
        {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(dashboardService.mapResourceJson(mapCode));
        }
        catch (ServiceException ex)
        {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/resource/folders")
    public AjaxResult resourceFolders()
    {
        return success(dashboardService.selectResourceFolders());
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:folder')")
    @Log(title = "大屏资源文件夹", businessType = BusinessType.INSERT)
    @PostMapping("/resource/folder")
    public AjaxResult createResourceFolder(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createResourceFolder(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:folder')")
    @Log(title = "大屏资源文件夹", businessType = BusinessType.UPDATE)
    @PutMapping("/resource/folder")
    public AjaxResult updateResourceFolder(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updateResourceFolder(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:folder')")
    @Log(title = "大屏资源文件夹", businessType = BusinessType.DELETE)
    @DeleteMapping("/resource/folder/{folderId}")
    public AjaxResult deleteResourceFolder(@PathVariable Long folderId)
    {
        return toAjax(dashboardService.deleteResourceFolder(folderId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/asset/list")
    public AjaxResult assetList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        return success(dashboardService.listAssets(keyword, assetType, folderId, includeChildren));
    }

    /** 管理列表使用分页；原目录接口保留完整结果供设计器选择资源。 */
    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/asset/page")
    public TableDataInfo assetPage(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(dashboardService.listAssetsPage(keyword, assetType, folderId, includeChildren, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/map/list")
    public AjaxResult mapList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        return success(dashboardService.listMapResources(keyword, folderId, includeChildren));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/map/page")
    public TableDataInfo mapPage(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(dashboardService.listMapResourcesPage(keyword, folderId, includeChildren, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:list')")
    @GetMapping("/map/{mapId}")
    public AjaxResult map(@PathVariable Long mapId)
    {
        return success(dashboardService.getMapResource(mapId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:edit')")
    @Log(title = "大屏媒体资源", businessType = BusinessType.INSERT)
    @PostMapping("/asset")
    public AjaxResult createAsset(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createAsset(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:edit')")
    @Log(title = "大屏媒体资源", businessType = BusinessType.UPDATE)
    @PutMapping("/asset")
    public AjaxResult updateAsset(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updateAsset(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:delete')")
    @Log(title = "大屏媒体资源", businessType = BusinessType.DELETE)
    @DeleteMapping("/asset/{assetId}")
    public AjaxResult deleteAsset(@PathVariable Long assetId)
    {
        return toAjax(dashboardService.deleteAsset(assetId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:edit')")
    @Log(title = "大屏地图资源", businessType = BusinessType.INSERT)
    @PostMapping("/map")
    public AjaxResult createMap(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createMapResource(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:edit')")
    @Log(title = "大屏地图资源", businessType = BusinessType.UPDATE)
    @PutMapping("/map")
    public AjaxResult updateMap(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updateMapResource(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:resource:delete')")
    @Log(title = "大屏地图资源", businessType = BusinessType.DELETE)
    @DeleteMapping("/map/{mapId}")
    public AjaxResult deleteMap(@PathVariable Long mapId)
    {
        return toAjax(dashboardService.deleteMapResource(mapId));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/public/share/{token}/runtime/data")
    public AjaxResult shareRuntimeData(@PathVariable String token, @RequestBody Map<String, Object> body)
    {
        return shareRuntimeData(token, null, body);
    }

    /** 分享集合中指定页面的数据请求；pageId 可省略，服务端按 pageCode 解析。 */
    @PreAuthorize("permitAll()")
    @PostMapping("/public/share/{token}/page/{pageCode}/runtime/data")
    public AjaxResult sharePageRuntimeData(@PathVariable String token, @PathVariable String pageCode,
            @RequestBody Map<String, Object> body)
    {
        return shareRuntimeData(token, pageCode, body);
    }

    private AjaxResult shareRuntimeData(String token, String pageCode, Map<String, Object> body)
    {
        if (body == null) body = Collections.emptyMap();
        Object datasetCode = body.get("datasetCode");
        Object pageId = body.get("pageId");
        Object widgetId = body.get("widgetId");
        if (datasetCode == null || String.valueOf(datasetCode).isBlank() || widgetId == null
                || (pageCode == null && pageId == null))
            return error(pageCode == null ? "pageId、widgetId 和 datasetCode 不能为空" : "widgetId 和 datasetCode 不能为空");
        Object params = body.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryParams = params instanceof Map ? (Map<String, Object>) params : Collections.emptyMap();
        List<Map<String, Object>> filters = new ArrayList<>();
        Object rawFilters = body.get("filters");
        if (rawFilters instanceof List)
        {
            for (Object item : (List<?>) rawFilters)
            {
                if (item instanceof Map)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> filter = (Map<String, Object>) item;
                    filters.add(filter);
                }
            }
        }
        try
        {
            Long numericPageId = pageId == null ? null : Long.valueOf(String.valueOf(pageId));
            Map<String, Object> result = pageCode == null
                    ? dashboardService.executeSharePageDataset(token, numericPageId, String.valueOf(widgetId),
                            String.valueOf(datasetCode), queryParams, filters)
                    : dashboardService.executeSharePageDataset(token, pageCode, numericPageId,
                            String.valueOf(widgetId), String.valueOf(datasetCode), queryParams, filters);
            return success(result);
        }
        catch (NumberFormatException ex)
        {
            return error("pageId 不合法");
        }
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping("/dataset/list")
    public TableDataInfo datasetList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        // 先解析目录范围，避免目录查询提前消费 PageHelper 的本次分页。
        List<String> folderGroupCodes = dataFolders.datasetFilterCodes(folderId, includeChildren);
        startPage();
        return getDataTable(dashboardService.selectDatasetList(keyword, dataType, groupCode, folderGroupCodes));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping("/dataset/group/list")
    public AjaxResult datasetGroupList()
    {
        return success(dashboardService.selectDatasetGroups());
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集分组", businessType = BusinessType.INSERT)
    @PostMapping("/dataset/group")
    public AjaxResult createDatasetGroup(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createDatasetGroup(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集分组", businessType = BusinessType.UPDATE)
    @PutMapping("/dataset/group")
    public AjaxResult updateDatasetGroup(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updateDatasetGroup(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集分组", businessType = BusinessType.DELETE)
    @DeleteMapping("/dataset/group/{groupId}")
    public AjaxResult deleteDatasetGroup(@PathVariable Long groupId)
    {
        return toAjax(dashboardService.deleteDatasetGroup(groupId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping("/source/list")
    public AjaxResult sourceList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(required = false) String environment, @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren)
    {
        return success(dashboardService.listDataSources(keyword, type, status, environment, folderId, includeChildren));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping("/source/page")
    public TableDataInfo sourcePage(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(required = false) String environment, @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize)
    {
        return getDataTable(dashboardService.listDataSourcesPage(keyword, type, status, environment,
                folderId, includeChildren, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:test')")
    @PostMapping("/source/{sourceCode}/test")
    public AjaxResult testSource(@PathVariable String sourceCode)
    {
        return success(dashboardService.testDataSource(sourceCode));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping({"/source/detail/code/{sourceCode}", "/source/{sourceCode}"})
    public AjaxResult dataSource(@PathVariable String sourceCode)
    {
        return success(dashboardService.getDataSource(sourceCode));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title="数据源配置查看", businessType=BusinessType.OTHER, isSaveRequestData=false, isSaveResponseData=false)
    @GetMapping("/source/{sourceCode}/configuration")
    public ResponseEntity<AjaxResult> dataSourceConfiguration(@PathVariable String sourceCode)
    {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(success(dashboardService.getDataSourceConfiguration(sourceCode)));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据源", businessType = BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    @PostMapping("/source")
    public AjaxResult createDataSource(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createDataSource(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据源", businessType = BusinessType.UPDATE, isSaveRequestData=false, isSaveResponseData=false)
    @PutMapping("/source")
    public AjaxResult updateDataSource(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.updateDataSource(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据源", businessType = BusinessType.DELETE)
    @DeleteMapping("/source/{dataSourceId}")
    public AjaxResult deleteDataSource(@PathVariable Long dataSourceId)
    {
        return toAjax(dashboardService.deleteDataSource(dataSourceId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:list')")
    @GetMapping("/dataset/{datasetId}")
    public AjaxResult dataset(@PathVariable Long datasetId)
    {
        return success(dashboardService.getDataset(datasetId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集", businessType = BusinessType.INSERT)
    @PostMapping("/dataset")
    public AjaxResult createDataset(@RequestBody Map<String, Object> body)
    {
        return success(dashboardService.createDataset(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集", businessType = BusinessType.UPDATE)
    @PutMapping("/dataset")
    public AjaxResult updateDataset(@RequestBody Map<String, Object> body)
    {
        return toAjax(dashboardService.updateDataset(body, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:edit')")
    @Log(title = "大屏数据集", businessType = BusinessType.DELETE)
    @DeleteMapping("/dataset/{datasetId}")
    public AjaxResult deleteDataset(@PathVariable Long datasetId)
    {
        return toAjax(dashboardService.deleteDataset(datasetId));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:dataset:test')")
    @Log(title = "大屏数据集", businessType = BusinessType.OTHER, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/dataset/{datasetId}/test")
    public AjaxResult testDataset(@PathVariable Long datasetId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return success(dashboardService.testDataset(datasetId, body == null ? Collections.emptyMap() : body));
    }

    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:preview,dashboard:dataset:test')")
    @PostMapping("/dataset/{datasetCode}/preview")
    public AjaxResult previewDataset(@PathVariable String datasetCode,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return success(dashboardService.executeDatasetByCode(datasetCode,
                body == null ? Collections.emptyMap() : body));
    }

    public record WidgetPreviewRequest(Map<String, Object> params, List<Map<String, Object>> filters) {}

    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:preview,dashboard:dataset:test')")
    @PostMapping("/dataset/{datasetCode}/preview-widget")
    public AjaxResult previewWidgetDataset(@PathVariable String datasetCode,
            @RequestBody WidgetPreviewRequest body)
    {
        return success(dashboardService.executeDatasetByCode(datasetCode,
                body.params() == null ? Collections.emptyMap() : body.params(),
                body.filters() == null ? Collections.emptyList() : body.filters()));
    }

    @PreAuthorize("@ss.hasAnyPermi('dashboard:page:view,dashboard:page:list,dashboard:dataset:test')")
    @PostMapping("/runtime/data")
    public AjaxResult runtimeData(@RequestBody Map<String, Object> body)
    {
        return executeRuntimeData(body, false);
    }

    @PreAuthorize("@ss.hasPermi('dashboard:page:preview')")
    @PostMapping("/runtime/preview/data")
    public AjaxResult previewRuntimeData(@RequestBody Map<String, Object> body)
    {
        return executeRuntimeData(body, true);
    }

    private AjaxResult executeRuntimeData(Map<String, Object> body, boolean preview)
    {
        Object datasetCode = body.get("datasetCode");
        Object pageId = body.get("pageId");
        Object widgetId = body.get("widgetId");
        Object revisionId = body.get("revisionId");
        if (datasetCode == null || String.valueOf(datasetCode).isBlank() || pageId == null || widgetId == null)
        {
            return error("pageId、widgetId 和 datasetCode 不能为空");
        }
        Object params = body.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryParams = params instanceof Map ? (Map<String, Object>) params : Collections.emptyMap();
        List<Map<String, Object>> filters = new ArrayList<>();
        Object rawFilters = body.get("filters");
        if (rawFilters instanceof List)
        {
            for (Object item : (List<?>) rawFilters)
            {
                if (item instanceof Map)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> filter = (Map<String, Object>) item;
                    filters.add(filter);
                }
            }
        }
        try
        {
            Long historyRevisionId = revisionId == null || String.valueOf(revisionId).isBlank()
                    ? null : Long.valueOf(String.valueOf(revisionId));
            return success(dashboardService.executePageDataset(Long.valueOf(String.valueOf(pageId)),
                    String.valueOf(widgetId), String.valueOf(datasetCode), queryParams, filters, preview,
                    historyRevisionId));
        }
        catch (NumberFormatException ex)
        {
            return error("pageId 或 revisionId 不合法");
        }
    }
}
