package com.ruoyi.system.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.Page;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardMapper;
import com.ruoyi.system.mapper.SysMenuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台内部轻量大屏服务。
 *
 * 这里故意将数据集执行限制在平台登记的来源和白名单能力内，避免把大屏变成任意 SQL
 * 或任意代理工具。已登记来源仅用于验证受控数据接入能力。
 */
@Service
public class DashboardService
{
    private static final String DEFAULT_SCHEMA = "{\"schemaVersion\":\"1.0\",\"canvas\":{\"width\":1920,\"height\":1080,\"scaleMode\":\"contain\",\"fullscreenScaleMode\":\"contain\",\"backgroundColor\":\"#101827\",\"theme\":\"dark\"},\"refresh\":{\"enabled\":true,\"mode\":\"interval\",\"seconds\":60,\"at\":\"08:00\"},\"widgets\":[]}";
    private static final Pattern DOLLAR_PARAMETER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern SAFE_PAGE_CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{2,63}");
    private static final Set<String> COMPONENT_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "metric-card", "number-flip", "statistics", "line-chart", "bar-chart", "pie-chart", "ring-chart", "gauge",
            "progress", "funnel", "radar", "scatter", "area-chart", "pictorial-chart", "word-cloud", "table", "rank-table", "carousel-table", "alert-list", "access-list",
            "treemap-chart", "calendar-chart", "bar3d-chart", "custom-chart", "filter-form", "designer-form", "online-form", "tabs", "text", "image", "realtime-list", "border", "decoration", "icon",
            "rich-text", "button", "current-time", "weather", "video", "iframe", "custom-html", "color-block", "ring-text", "advanced-table", "carousel", "milestone-timeline", "gantt-chart", "map-chart", "map-flow", "map-bar", "map-heat", "map-ranking", "map-timeline")));
    private static final Set<String> ICON_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "star", "monitor", "edit", "warning", "trend", "user", "setting",
            "clock", "document", "video", "shield", "check", "tools", "folder", "location", "cloud", "temperature",
            "wind", "truck", "drone", "helmet", "health", "ai", "bell", "search")));
    private static final Set<String> PALETTE_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "teal", "ocean", "amber", "purple", "mono")));
    private static final Set<String> DATASET_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SQL", "API", "JSON", "WEBSOCKET")));
    private static final Set<String> DATA_SOURCE_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MYSQL", "HTTP", "WEBSOCKET")));
    private static final Set<String> DATA_SOURCE_STATUSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "DRAFT", "ACTIVE", "DISABLED")));
    private static final Set<String> DATASET_TEST_PASSED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("SUCCESS", "NO_DATA")));
    private static final Set<String> ASSET_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("IMAGE", "VIDEO")));
    private static final Set<String> ASSET_STATUSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("0", "1")));
    private static final Set<String> FORBIDDEN_SQL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "drop", "alter", "truncate", "create", "replace", "call",
            "grant", "revoke", "load", "outfile", "dumpfile")));
    private static final int MAX_ROWS = 1000;
    private static final int MAX_JSON_CHARS = 512 * 1024;
    private static final int MAX_FILTER_FORM_FIELDS = 20;
    private static final int MAX_FILTER_FORM_TEXT = 128;
    private static final int MAX_GEOJSON_CHARS = 4 * 1024 * 1024;
    private static final int MAX_CUSTOM_HTML_CHARS = 256 * 1024;
    private static final Map<String, String> LABOR_ENDPOINT_PROPERTIES = Map.of(
            "labor.hik.events", "dashboard.api.labor.endpoints.hik-events",
            "labor.hik.organizations", "dashboard.api.labor.endpoints.hik-organizations",
            "labor.attendance.daily", "dashboard.api.labor.endpoints.attendance-daily",
            "labor.dashboard", "dashboard.api.labor.endpoints.dashboard");

    @Autowired
    private DashboardMapper dashboardMapper;

    @Autowired
    private DashboardDataFolderService dataFolders;

    /** 新版第三方 endpoint 执行器；旧数据集仍走兼容执行路径。 */
    @Autowired(required = false)
    private DashboardIntegrationService dashboardIntegrationService;
    @Autowired
    private DashboardUpstreamWebSocketPool upstreamWebSocketPool;

    @Autowired
    private SysMenuMapper sysMenuMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    private Environment environment;

    public List<Map<String, Object>> selectPageList(String keyword)
    {
        return selectPageList(keyword, null);
    }

    public List<Map<String, Object>> selectPageList(String keyword, Long folderId)
    {
        return dashboardMapper.selectPageList(keyword, folderId, false);
    }

    public List<Map<String, Object>> selectPageListByFolders(String keyword, List<Long> folderIds)
    {
        return dashboardMapper.selectPageListByFolders(keyword, folderIds, false);
    }

    /**
     * 返回可用于页面跳转选择器的页面目录。页面必须启用、未进入回收站，且至少
     * 存在一个已发布版本；版本号由 SQL 选择页面内最新的发布版本。
     */
    public List<Map<String, Object>> selectPublishedPageList(String keyword, Long folderId)
    {
        return dashboardMapper.selectPublishedPageList(keyword == null ? null : keyword.trim(), folderId);
    }

    public List<Map<String, Object>> selectPublishedPageListByFolders(String keyword, List<Long> folderIds)
    {
        return dashboardMapper.selectPublishedPageListByFolders(keyword == null ? null : keyword.trim(), folderIds);
    }

    public List<Map<String, Object>> selectPageRecycleList(String keyword)
    {
        return dashboardMapper.selectPageRecycleList(keyword);
    }

    public List<Map<String, Object>> selectPageFolders()
    {
        return dashboardMapper.selectPageFolderList();
    }

    public Map<String, Object> getPage(Long pageId)
    {
        Map<String, Object> page = requirePage(pageId);
        List<Map<String, Object>> revisions = dashboardMapper.selectRevisionList(pageId);
        page.put("revisions", revisions);
        Map<String, Object> current = currentRevision(page);
        if (current != null)
        {
            page.put("currentRevisionStatus", current.get("status"));
            Object currentSchema = readJson(current.get("schemaJson"), "页面配置");
            page.put("currentSchema", currentSchema);
            if ("DRAFT".equals(current.get("status")))
            {
                page.put("draftSchema", currentSchema);
            }
            else if ("PUBLISHED".equals(current.get("status")))
            {
                page.put("publishedSchema", currentSchema);
            }
        }
        // 运行版本指针始终指向已发布版本；存在草稿时单独返回草稿，避免设计器在
        // 保存草稿后丢失当前运行页面。
        Map<String, Object> draft = revisions.stream()
                .filter(item -> "DRAFT".equals(item.get("status")))
                .findFirst()
                .orElse(null);
        if (draft != null)
        {
            Map<String, Object> draftDetail = dashboardMapper.selectRevisionById(toLong(draft.get("revisionId")));
            if (draftDetail != null)
            {
                Object draftSchema = readJson(draftDetail.get("schemaJson"), "页面草稿配置");
                page.put("draftSchema", draftSchema);
                // 新页面尚未发布时没有 current_revision_id；保留 currentSchema 作为
                // 设计器读取草稿的兼容字段，但不把运行版本指针指向草稿。
                if (!page.containsKey("currentSchema"))
                {
                    page.put("currentSchema", draftSchema);
                    page.put("currentRevisionStatus", "DRAFT");
                }
            }
        }
        return page;
    }

    public Map<String, Object> getRuntimePage(Long pageId)
    {
        return getRuntimePage(pageId, false);
    }

    /**
     * 将已发布页面注册为若依菜单。新菜单默认位于“大屏管理”下，角色授权由
     * 若依角色管理维护；按稳定页面标识查找菜单，移动目录后重复配置不会新建或移回。
     */
    @Transactional
    public Map<String, Object> configurePageMenu(Long pageId, String username)
    {
        Map<String, Object> page = requirePage(pageId);
        if ("1".equals(String.valueOf(page.get("status"))))
            throw new ServiceException("页面已停用，不能配置菜单");
        Map<String, Object> published = getRuntimePage(pageId);
        String pageCode = text(page.get("pageCode"));
        if (!SAFE_PAGE_CODE.matcher(pageCode).matches())
            throw new ServiceException("页面编码不合法，不能配置菜单");

        String path = "runtime/code/" + pageCode;
        String routeName = "DashboardRuntime" + pageId;
        List<SysMenu> matches = sysMenuMapper.selectDashboardPageMenus(path, routeName, pageCode);
        if (matches.size() > 1)
            throw new ServiceException("该页面存在多个运行菜单，请先在菜单管理中处理重复菜单");
        SysMenu menu = matches.isEmpty() ? null : matches.get(0);
        boolean created = menu == null;
        if (created)
        {
            SysMenu parent = sysMenuMapper.selectMenuByParentAndPath(0L, "dashboard");
            if (parent == null || parent.getMenuId() == null)
                throw new ServiceException("未找到“大屏管理”菜单，请先执行大屏菜单初始化脚本");
            if (!sysMenuMapper.selectMenusByPathOrRouteName(path, routeName).isEmpty())
                throw new ServiceException("页面运行路由已被其他菜单使用，请先在菜单管理中调整");
            menu = new SysMenu();
            menu.setParentId(parent.getMenuId());
            Integer maxOrder = sysMenuMapper.selectMaxOrderNum(parent.getMenuId());
            menu.setOrderNum((maxOrder == null ? 0 : maxOrder) + 1);
            menu.setMenuName(text(page.get("pageName")));
            menu.setPath(path);
            menu.setComponent("dashboard/runtime/index");
            menu.setRouteName(routeName);
            menu.setIsFrame("1");
            menu.setIsCache("0");
            menu.setMenuType("C");
            menu.setVisible("0");
            menu.setStatus("0");
            menu.setPerms("dashboard:page:view");
            menu.setIcon("monitor");
        }

        // 业务过滤器可能使用 pageCode，页面身份使用专用参数。菜单管理中的父目录、
        // 路径、排序、展示设置和业务查询参数均保留，不把页面身份绑定到菜单层级。
        Map<String, Object> query = text(menu.getQuery()).isEmpty()
                ? new LinkedHashMap<>() : parseObject(menu.getQuery(), "菜单路由参数");
        query.put("dashboardPageCode", pageCode);
        menu.setQuery(jsonString(query, "{}"));
        if (created)
        {
            menu.setCreateBy(username);
            sysMenuMapper.insertMenu(menu);
        }
        else
        {
            menu.setUpdateBy(username);
            sysMenuMapper.updateMenu(menu);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("menuId", menu.getMenuId());
        result.put("menuName", menu.getMenuName());
        result.put("parentId", menu.getParentId());
        result.put("path", menu.getPath());
        result.put("routeName", menu.getRouteName());
        result.put("component", menu.getComponent());
        result.put("perms", menu.getPerms());
        result.put("query", menu.getQuery());
        // 独立运行入口保持稳定；真实菜单地址由若依依据当前父目录生成。
        result.put("url", "/dashboard/" + path);
        result.put("created", created);
        result.put("pageId", pageId);
        result.put("pageCode", pageCode);
        result.put("versionNo", published.get("versionNo"));
        return result;
    }

    /**
     * 按稳定页面编码读取已发布运行版本。页面编码是公开交互配置使用的稳定标识，
     * 解析后仍复用 pageId 运行逻辑，确保旧接口的版本选择和停用校验保持一致。
     */
    public Map<String, Object> getRuntimePageByCode(String pageCode)
    {
        return getRuntimePageByCode(pageCode, false);
    }

    /**
     * 按稳定页面编码读取页面运行/预览版本。预览模式沿用按主键读取的权限和
     * 草稿选择规则，避免编码入口与旧 pageId 入口出现不同的版本语义。
     */
    public Map<String, Object> getRuntimePageByCode(String pageCode, boolean preview)
    {
        String code = text(pageCode);
        if (!SAFE_PAGE_CODE.matcher(code).matches())
            throw new ServiceException("页面编码不合法");
        Map<String, Object> page = dashboardMapper.selectPageByCode(code);
        if (page == null) throw new ServiceException("页面不存在");
        return getRuntimePage(toLong(page.get("pageId")), preview);
    }

    /**
     * 预览态优先使用当前用户能够编辑的页面草稿；没有草稿时回退到当前已发布版本，
     * 让“只有发布版本”的页面也能从设计器直接预览。正式运行态始终只读取已发布版本。
     */
    public Map<String, Object> getRuntimePage(Long pageId, boolean preview)
    {
        Map<String, Object> page = requirePage(pageId);
        if (!preview && "1".equals(String.valueOf(page.get("status"))))
        {
            throw new ServiceException("页面已停用");
        }
        if (preview)
        {
            Map<String, Object> draft = draftRevision(pageId);
            Map<String, Object> revision = draft;
            boolean draftPreview = true;
            if (revision == null)
            {
                Object currentRevisionId = page.get("currentRevisionId");
                revision = currentRevisionId == null ? null
                        : dashboardMapper.selectRevisionById(toLong(currentRevisionId));
                if (revision == null || !"PUBLISHED".equals(revision.get("status")))
                {
                    revision = dashboardMapper.selectRevisionList(pageId).stream()
                            .filter(item -> "PUBLISHED".equals(item.get("status")))
                            .findFirst()
                            .map(item -> dashboardMapper.selectRevisionById(toLong(item.get("revisionId"))))
                            .orElse(null);
                }
                draftPreview = false;
            }
            if (revision == null || !"DRAFT".equals(revision.get("status")) && !"PUBLISHED".equals(revision.get("status")))
            {
                throw new ServiceException("页面没有可预览的草稿或已发布版本");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pageId", page.get("pageId"));
            result.put("pageCode", page.get("pageCode"));
            result.put("pageName", page.get("pageName"));
            result.put("revisionId", revision.get("revisionId"));
            result.put("versionNo", revision.get("versionNo"));
            result.put("preview", true);
            result.put("previewSource", draftPreview ? "DRAFT" : "PUBLISHED");
            result.put("previewReadOnly", !draftPreview);
            Object previewSchema = readJson(revision.get("schemaJson"), draftPreview ? "页面草稿配置" : "页面发布配置");
            result.put("schema", previewSchema);
            result.put("datasets", shareDatasetCatalog(previewSchema));
            return result;
        }
        Object revisionId = page.get("currentRevisionId");
        Map<String, Object> revision = revisionId == null ? null
                : dashboardMapper.selectRevisionById(toLong(revisionId));
        if (revision == null || !"PUBLISHED".equals(revision.get("status")))
        {
            // 兼容旧数据：历史实现可能曾把 current_revision_id 指向草稿，运行态应
            // 继续选择当前页面最新的已发布版本，而不是被未发布草稿阻断。
            revision = dashboardMapper.selectRevisionList(pageId).stream()
                    .filter(item -> "PUBLISHED".equals(item.get("status")))
                    .findFirst()
                    .map(item -> dashboardMapper.selectRevisionById(toLong(item.get("revisionId"))))
                    .orElse(null);
            if (revision == null) throw new ServiceException("页面尚未发布");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.get("pageId"));
        result.put("pageCode", page.get("pageCode"));
        result.put("pageName", page.get("pageName"));
        result.put("revisionId", revision.get("revisionId"));
        result.put("versionNo", revision.get("versionNo"));
        Object runtimeSchema = readJson(revision.get("schemaJson"), "页面配置");
        result.put("schema", runtimeSchema);
        result.put("datasets", shareDatasetCatalog(runtimeSchema));
        result.put("preview", false);
        return result;
    }

    /** 返回指定历史版本的只读预览，版本必须属于当前页面。 */
    public Map<String, Object> getRevisionPreview(Long pageId, Long revisionId)
    {
        Map<String, Object> page = requirePage(pageId);
        Map<String, Object> revision = revisionId == null ? null : dashboardMapper.selectRevisionById(revisionId);
        if (revision == null || !pageId.equals(toLong(revision.get("pageId"))))
        {
            throw new ServiceException("页面历史版本不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.get("pageId"));
        result.put("pageCode", page.get("pageCode"));
        result.put("pageName", page.get("pageName"));
        result.put("revisionId", revision.get("revisionId"));
        result.put("versionNo", revision.get("versionNo"));
        result.put("preview", true);
        result.put("previewSource", "REVISION");
        result.put("previewReadOnly", true);
        result.put("schema", readJson(revision.get("schemaJson"), "页面历史版本配置"));
        return result;
    }

    /** 复制页面及其当前草稿/发布配置，生成独立页面和草稿版本。 */
    @Transactional
    public Map<String, Object> copyPage(Long pageId, Map<String, Object> body, String username)
    {
        Map<String, Object> source = requirePage(pageId);
        String sourceCode = String.valueOf(source.get("pageCode"));
        String pageCode = body == null ? null : text(body.get("pageCode"));
        if (pageCode.isEmpty()) pageCode = sourceCode + "-copy-" + System.currentTimeMillis();
        String pageName = body == null ? null : text(body.get("pageName"));
        if (pageName.isEmpty()) pageName = String.valueOf(source.get("pageName")) + " 副本";
        Map<String, Object> sourceRevision = draftRevision(pageId);
        if (sourceRevision == null)
        {
            Object currentRevisionId = source.get("currentRevisionId");
            sourceRevision = currentRevisionId == null ? null : dashboardMapper.selectRevisionById(toLong(currentRevisionId));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pageCode", pageCode);
        payload.put("pageName", pageName);
        payload.put("remark", body == null ? "复制页面" : body.getOrDefault("remark", "复制自 " + sourceCode));
        if (sourceRevision != null) payload.put("schemaJson", sourceRevision.get("schemaJson"));
        return createPage(payload, username);
    }

    @Transactional
    public Map<String, Object> createPage(Map<String, Object> body, String username)
    {
        String pageCode = required(body, "pageCode");
        if (!SAFE_PAGE_CODE.matcher(pageCode).matches())
        {
            throw new ServiceException("页面编码只能使用小写字母、数字、下划线和短横线，长度 3-64");
        }
        if (dashboardMapper.selectPageByCode(pageCode) != null)
        {
            throw new ServiceException("页面编码已存在");
        }
        String pageName = required(body, "pageName");
        String schemaJson = jsonString(body.get("schemaJson"), DEFAULT_SCHEMA);
        validateSchema(schemaJson);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageCode", pageCode);
        page.put("pageName", pageName);
        page.put("status", "0");
        page.put("folderId", normalizeFolderId(body.get("folderId")));
        page.put("createBy", username);
        page.put("remark", body.get("remark"));
        dashboardMapper.insertPage(page);

        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("pageId", page.get("pageId"));
        revision.put("versionNo", 1);
        revision.put("status", "DRAFT");
        revision.put("schemaJson", schemaJson);
        revision.put("schemaHash", hash(schemaJson));
        revision.put("createBy", username);
        revision.put("publishNote", null);
        dashboardMapper.insertRevision(revision);
        // 新页面只有草稿，没有可供运行态使用的 current_revision_id；发布时再指向不可变版本。
        return getPage(toLong(page.get("pageId")));
    }

    @Transactional
    public int updatePage(Map<String, Object> body, String username)
    {
        Long pageId = toLong(body.get("pageId"));
        requirePage(pageId);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", pageId);
        if (body.containsKey("pageName"))
        {
            page.put("pageName", required(body, "pageName"));
        }
        if (body.containsKey("status"))
        {
            String status = String.valueOf(body.get("status"));
            if (!"0".equals(status) && !"1".equals(status))
            {
                throw new ServiceException("页面状态不合法");
            }
            page.put("status", status);
        }
        if (body.containsKey("folderId")) page.put("folderId", normalizeFolderId(body.get("folderId")));
        if (body.containsKey("remark"))
        {
            page.put("remark", body.get("remark"));
        }
        page.put("updateBy", username);
        return dashboardMapper.updatePage(page);
    }

    @Transactional
    public int deletePage(Long pageId, String username)
    {
        requirePage(pageId);
        int affected = dashboardMapper.softDeletePage(pageId, username);
        if (affected > 0 && dashboardIntegrationService != null)
            dashboardIntegrationService.revokeMediaForPage(pageId, null);
        return affected;
    }

    public int restorePage(Long pageId, String username)
    {
        Map<String, Object> page = dashboardMapper.selectPageById(pageId);
        if (page == null || !"1".equals(String.valueOf(page.get("isDeleted"))))
            throw new ServiceException("回收站中不存在该页面");
        return dashboardMapper.restorePage(pageId, username);
    }

    public int purgePage(Long pageId)
    {
        Map<String, Object> page = dashboardMapper.selectPageById(pageId);
        if (page == null || !"1".equals(String.valueOf(page.get("isDeleted"))))
            throw new ServiceException("只能彻底删除回收站中的页面");
        if (dashboardIntegrationService != null)
            dashboardIntegrationService.revokeMediaForPage(pageId, null);
        return dashboardMapper.purgePage(pageId);
    }

    public int purgeRecycleBin()
    {
        if (dashboardIntegrationService != null)
            dashboardIntegrationService.revokeMediaForDeletedPages();
        return dashboardMapper.purgeRecycleBin();
    }

    @Transactional
    public int saveDraft(Long pageId, Map<String, Object> body, String username)
    {
        requirePage(pageId);
        String schemaJson = jsonString(body.get("schemaJson"), null);
        if (schemaJson == null)
        {
            throw new ServiceException("schemaJson 不能为空");
        }
        validateSchema(schemaJson);
        Map<String, Object> current = draftRevision(pageId);
        if (current == null)
        {
            int nextVersion = nextVersion(pageId);
            current = new LinkedHashMap<>();
            current.put("pageId", pageId);
            current.put("versionNo", nextVersion);
            current.put("status", "DRAFT");
            current.put("schemaJson", schemaJson);
            current.put("schemaHash", hash(schemaJson));
            current.put("createBy", username);
            current.put("publishNote", null);
            dashboardMapper.insertRevision(current);
        }
        else
        {
            current.put("schemaJson", schemaJson);
            current.put("schemaHash", hash(schemaJson));
            current.put("updateBy", username);
            dashboardMapper.updateRevisionContent(current);
        }
        return 1;
    }

    @Transactional
    public int publishPage(Long pageId, String username, String publishNote)
    {
        requirePage(pageId);
        Map<String, Object> draft = draftRevision(pageId);
        if (draft == null)
        {
            throw new ServiceException("没有可发布的草稿，请先保存设计");
        }
        String schemaJson = String.valueOf(draft.get("schemaJson"));
        validateSchema(schemaJson);
        for (Map<String, Object> revision : dashboardMapper.selectRevisionList(pageId))
        {
            if ("PUBLISHED".equals(revision.get("status")))
            {
                Long archivedRevisionId = toLong(revision.get("revisionId"));
                dashboardMapper.updateRevisionStatus(toLong(revision.get("revisionId")), "ARCHIVED", username,
                        "发布新版本自动归档");
                if (dashboardIntegrationService != null)
                    dashboardIntegrationService.revokeMediaForPage(pageId, archivedRevisionId);
            }
        }
        dashboardMapper.updateRevisionStatus(toLong(draft.get("revisionId")), "PUBLISHED", username, publishNote);
        dashboardMapper.updateCurrentRevision(pageId, toLong(draft.get("revisionId")));
        return 1;
    }

    @Transactional
    public int rollbackPage(Long pageId, Long revisionId, String username, String note)
    {
        Map<String, Object> page = requirePage(pageId);
        Map<String, Object> target = dashboardMapper.selectRevisionById(revisionId);
        if (target == null || !pageId.equals(toLong(target.get("pageId")))
                || !("PUBLISHED".equals(target.get("status")) || "ARCHIVED".equals(target.get("status"))))
        {
            throw new ServiceException("只能回滚当前页面的历史发布版本");
        }
        for (Map<String, Object> revision : dashboardMapper.selectRevisionList(pageId))
        {
            if ("PUBLISHED".equals(revision.get("status")))
            {
                Long archivedRevisionId = toLong(revision.get("revisionId"));
                dashboardMapper.updateRevisionStatus(toLong(revision.get("revisionId")), "ARCHIVED", username,
                        "回滚生成新版本");
                if (dashboardIntegrationService != null)
                    dashboardIntegrationService.revokeMediaForPage(pageId, archivedRevisionId);
            }
        }
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("pageId", pageId);
        revision.put("versionNo", nextVersion(pageId));
        revision.put("status", "PUBLISHED");
        revision.put("schemaJson", target.get("schemaJson"));
        revision.put("schemaHash", hash(String.valueOf(target.get("schemaJson"))));
        revision.put("createBy", username);
        revision.put("publishBy", username);
        revision.put("publishNote", note == null ? "回滚自版本 " + target.get("versionNo") : note);
        dashboardMapper.insertRevision(revision);
        dashboardMapper.updateRevisionStatus(toLong(revision.get("revisionId")), "PUBLISHED", username,
                String.valueOf(revision.get("publishNote")));
        dashboardMapper.updateCurrentRevision(pageId, toLong(revision.get("revisionId")));
        return 1;
    }

    /**
     * 创建绑定到已发布版本的限时或永久只读分享。pageIds 可选，用于把多个页面
     * 固定到同一个分享令牌；未提供时保持旧版单页面分享行为。
     */
    @Transactional
    public Map<String, Object> createShare(Long pageId, Map<String, Object> body, String username)
    {
        Map<String, Object> request = body == null ? Collections.emptyMap() : body;
        String versionMode = shareVersionMode(request.get("versionMode"), "FOLLOW_PUBLISHED");
        Map<String, Object> runtime = getRuntimePage(pageId, false);
        List<Long> sharePageIds = resolveSharePageIds(pageId, request.get("pageIds"));
        Map<Long, Map<String, Object>> pageRuntimes = new LinkedHashMap<>();
        pageRuntimes.put(pageId, runtime);
        for (Long targetPageId : sharePageIds)
        {
            if (!pageRuntimes.containsKey(targetPageId))
                pageRuntimes.put(targetPageId, getRuntimePage(targetPageId, false));
        }
        boolean permanent = Boolean.TRUE.equals(request.get("permanent"))
                || "true".equalsIgnoreCase(text(request.get("permanent")));
        Timestamp expiresAt = null;
        if (!permanent)
        {
            String unit = text(request.get("expiresUnit")).toUpperCase(Locale.ROOT);
            if (unit.isBlank()) unit = "HOUR";
            if (!Set.of("HOUR", "DAY").contains(unit))
                throw new ServiceException("分享有效期单位只支持小时或天");
            Object rawValue = request.containsKey("expiresValue") ? request.get("expiresValue") : request.get("expiresHours");
            int value;
            try
            {
                value = rawValue == null || text(rawValue).isBlank() ? 24 : Integer.parseInt(text(rawValue));
            }
            catch (NumberFormatException ex)
            {
                throw new ServiceException("分享有效期必须是整数");
            }
            int maximum = "DAY".equals(unit) ? 365 : 8760;
            if (value < 1 || value > maximum)
                throw new ServiceException("分享有效期必须为 1-" + maximum + ("DAY".equals(unit) ? " 天" : " 小时"));
            long seconds = value * ("DAY".equals(unit) ? 86400L : 3600L);
            expiresAt = Timestamp.from(Instant.now().plusSeconds(seconds));
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("pageId", pageId);
        share.put("revisionId", toLong(runtime.get("revisionId")));
        share.put("tokenHash", hash(token));
        share.put("token", token);
        share.put("expiresAt", expiresAt);
        share.put("status", "ACTIVE");
        share.put("versionMode", versionMode);
        share.put("createdBy", username);
        dashboardMapper.insertShare(share);
        int sortOrder = 0;
        for (Long targetPageId : sharePageIds)
        {
            Map<String, Object> targetRuntime = pageRuntimes.get(targetPageId);
            Map<String, Object> membership = new LinkedHashMap<>();
            membership.put("shareId", share.get("shareId"));
            membership.put("pageId", targetPageId);
            membership.put("revisionId", toLong(targetRuntime.get("revisionId")));
            membership.put("isEntry", pageId.equals(targetPageId) ? "1" : "0");
            membership.put("sortOrder", sortOrder++);
            dashboardMapper.insertSharePage(membership);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shareId", share.get("shareId"));
        result.put("pageId", pageId);
        result.put("revisionId", runtime.get("revisionId"));
        result.put("versionMode", versionMode);
        result.put("expiresAt", share.get("expiresAt"));
        result.put("permanent", permanent);
        result.put("token", token);
        result.put("pages", sharePageSummaries(pageRuntimes, sharePageIds));
        return result;
    }

    /** 解析分享页面集合，入口页始终排在第一位并自动去重。 */
    private List<Long> resolveSharePageIds(Long entryPageId, Object rawPageIds)
    {
        LinkedHashMap<Long, Boolean> ids = new LinkedHashMap<>();
        ids.put(entryPageId, Boolean.TRUE);
        if (rawPageIds == null || text(rawPageIds).isBlank()) return new ArrayList<>(ids.keySet());
        if (!(rawPageIds instanceof List))
            throw new ServiceException("分享页面必须是 pageIds 数组");
        if (((List<?>) rawPageIds).size() > 100)
            throw new ServiceException("一次分享最多包含 100 个页面");
        for (Object item : (List<?>) rawPageIds)
        {
            Object value = item instanceof Map ? ((Map<?, ?>) item).get("pageId") : item;
            Long id = parsePositiveId(value, "分享页面编号");
            if (!ids.containsKey(id) && ids.size() >= 100)
                throw new ServiceException("一次分享最多包含 100 个页面");
            ids.put(id, Boolean.TRUE);
        }
        return new ArrayList<>(ids.keySet());
    }

    private Long parsePositiveId(Object value, String label)
    {
        if (value == null || text(value).isBlank()) throw new ServiceException(label + "不能为空");
        try
        {
            long id = Long.parseLong(text(value));
            if (id <= 0) throw new NumberFormatException();
            return id;
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException(label + "不合法");
        }
    }

    private List<Map<String, Object>> sharePageSummaries(Map<Long, Map<String, Object>> pageRuntimes,
            List<Long> pageIds)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : pageIds)
        {
            Map<String, Object> runtime = pageRuntimes.get(id);
            if (runtime == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pageId", runtime.get("pageId"));
            item.put("pageCode", runtime.get("pageCode"));
            item.put("pageName", runtime.get("pageName"));
            item.put("revisionId", runtime.get("revisionId"));
            item.put("versionNo", runtime.get("versionNo"));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> selectShareList(Long pageId)
    {
        requirePage(pageId);
        List<Map<String, Object>> rows = dashboardMapper.selectShareList(pageId);
        rows.forEach(row -> row.put("versionMode", shareVersionMode(row.get("versionMode"), "FIXED")));
        return rows;
    }

    private String shareVersionMode(Object value, String fallback)
    {
        String mode = value == null ? fallback : text(value).toUpperCase(Locale.ROOT);
        if (!"FOLLOW_PUBLISHED".equals(mode) && !"FIXED".equals(mode))
            throw new ServiceException("分享版本模式只支持跟随发布或固定版本");
        return mode;
    }

    /** 更改策略不改变令牌、有效期或成员。先验证全集，再原子更新版本绑定。 */
    @Transactional
    public Map<String, Object> updateShareVersionMode(Long pageId, Long shareId, Map<String, Object> body)
    {
        String targetMode = shareVersionMode(body == null ? null : body.get("versionMode"), null);
        requirePage(pageId);
        Map<String, Object> share = requireActiveShare(dashboardMapper.selectShareForUpdate(pageId, shareId));
        String previousMode = shareVersionMode(share.get("versionMode"), "FIXED");
        List<Map<String, Object>> memberships = dashboardMapper.selectShareMemberships(shareId);
        LinkedHashMap<Long, Long> bindings = new LinkedHashMap<>();
        bindings.put(pageId, toLong(share.get("revisionId")));
        memberships.forEach(member -> bindings.put(toLong(member.get("pageId")), toLong(member.get("revisionId"))));
        Map<Long, Map<String, Object>> resolved = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> binding : bindings.entrySet())
        {
            Map<String, Object> page = requirePage(binding.getKey());
            try
            {
                // 同模式重试必须幂等；已经固定的旧版本不能被重复提交更新。
                Map<String, Object> revision = resolveShareRevision(page, binding.getValue(),
                        targetMode.equals(previousMode) ? previousMode : "FOLLOW_PUBLISHED");
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("pageId", page.get("pageId"));
                summary.put("pageCode", page.get("pageCode"));
                summary.put("pageName", page.get("pageName"));
                summary.put("revisionId", revision.get("revisionId"));
                summary.put("versionNo", revision.get("versionNo"));
                resolved.put(binding.getKey(), summary);
            }
            catch (ServiceException ex)
            {
                throw new ServiceException("分享页面“" + text(page.get("pageName")) + "”不可更改模式：" + ex.getMessage());
            }
        }
        Long entryRevisionId = toLong(resolved.get(pageId).get("revisionId"));
        if (!targetMode.equals(previousMode))
        {
            if ("FIXED".equals(targetMode))
            {
                for (Map<String, Object> member : memberships)
                {
                    Long memberId = toLong(member.get("pageId"));
                    if (dashboardMapper.updateSharePageRevision(shareId, memberId,
                            toLong(resolved.get(memberId).get("revisionId"))) != 1)
                        throw new ServiceException("分享授权页面已变化，请刷新后重试");
                }
            }
            if (dashboardMapper.updateShareVersionMode(pageId, shareId, targetMode, entryRevisionId) != 1)
                throw new ServiceException("分享状态已变化，请刷新后重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shareId", shareId);
        result.put("pageId", pageId);
        result.put("revisionId", entryRevisionId);
        result.put("versionMode", targetMode);
        result.put("pages", new ArrayList<>(resolved.values()));
        return result;
    }

    @Transactional
    public int revokeShare(Long pageId, Long shareId)
    {
        requirePage(pageId);
        int affected = dashboardMapper.revokeShare(pageId, shareId);
        if (affected > 0 && dashboardIntegrationService != null)
            dashboardIntegrationService.revokeMediaForShare(shareId);
        return affected;
    }

    /** 解析分享令牌，只返回令牌绑定的发布版本，不返回明文令牌或管理凭证。 */
    public Map<String, Object> getShareRuntime(String token)
    {
        return getShareRuntime(token, null);
    }

    /**
     * 解析分享集合中的指定页面。pageCode 为空时保持旧版入口页行为；指定编码时
     * 必须存在于 dashboard_share_page，不能借分享令牌访问集合外页面。
     */
    public Map<String, Object> getShareRuntime(String token, String pageCode)
    {
        return shareRuntime(resolveShareContext(token, pageCode), true);
    }

    /** 轻量版本检查只读取元数据；与运行和取数复用同一授权与版本选择。 */
    public Map<String, Object> getShareVersion(String token, String pageCode)
    {
        ShareContext context = resolveShareContext(token, pageCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", context.page().get("pageId"));
        result.put("revisionId", context.revision().get("revisionId"));
        result.put("versionNo", context.revision().get("versionNo"));
        result.put("versionMode", context.versionMode());
        return result;
    }

    /**
     * 为媒体引用解析已经通过分享令牌授权的页面和版本。调用方只拿到内部编号，
     * 不会把分享表或令牌信息暴露给浏览器。
     */
    public ShareMediaContext getShareMediaContext(String token, String pageCode)
    {
        ShareContext context = resolveShareContext(token, pageCode);
        return new ShareMediaContext(toLong(context.share().get("shareId")),
                toLong(context.page().get("pageId")), toLong(context.revision().get("revisionId")));
    }

    /** 登录媒体入口使用已发布页面和指定版本重新校验引用绑定。 */
    public void validateRuntimeMediaAccess(Long pageId, Long revisionId)
    {
        if (pageId == null || revisionId == null) throw new ServiceException("媒体页面绑定无效");
        Map<String, Object> page = requirePage(pageId);
        if (!"0".equals(String.valueOf(page.get("status")))) throw new ServiceException("页面已停用");
        Map<String, Object> revision = dashboardMapper.selectRevisionMetadata(revisionId);
        if (revision == null || !pageId.equals(toLong(revision.get("pageId")))
                || !("PUBLISHED".equals(revision.get("status")) || "ARCHIVED".equals(revision.get("status"))
                    || "DRAFT".equals(revision.get("status")) && com.ruoyi.common.utils.SecurityUtils.hasPermi("dashboard:page:preview")))
            throw new ServiceException("媒体页面版本不可用");
    }

    /** 媒体候选只能绑定到该页面版本中真实引用指定数据集的组件。 */
    public void validateRuntimeMediaDatasetAccess(Long pageId, Long revisionId,
            String widgetId, String datasetCode)
    {
        validateRuntimeMediaAccess(pageId, revisionId);
        String normalizedWidget = text(widgetId);
        String normalizedDataset = text(datasetCode);
        if (normalizedWidget.isBlank() || normalizedDataset.isBlank())
            throw new ServiceException("媒体组件和数据集不能为空");
        Map<String, Object> revision = dashboardMapper.selectRevisionById(revisionId);
        if (revision == null) throw new ServiceException("媒体页面版本不可用");
        JsonNode widgets = objectMapper.valueToTree(
                readJson(revision.get("schemaJson"), "媒体页面配置"));
        widgets = widgets == null ? null : widgets.path("widgets");
        boolean bound = false;
        if (widgets != null && widgets.isArray())
        {
            for (JsonNode widget : widgets)
            {
                if (normalizedWidget.equals(widget.path("id").asText(""))
                        && normalizedDataset.equals(widget.path("binding").path("datasetCode").asText("")))
                {
                    bound = true;
                    break;
                }
            }
        }
        if (!bound) throw new ServiceException("媒体数据集未绑定到该页面组件");
        Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(normalizedDataset);
        boolean preview="DRAFT".equals(revision.get("status")) && com.ruoyi.common.utils.SecurityUtils.hasPermi("dashboard:page:preview");
        if (dataset == null || !("ACTIVE".equalsIgnoreCase(text(dataset.get("status"))) || preview && "DRAFT".equalsIgnoreCase(text(dataset.get("status")))))
            throw new ServiceException("媒体数据集未启用");
    }

    private record ShareContext(Map<String, Object> share, Map<String, Object> page,
            Map<String, Object> revision, String versionMode) { }

    public record ShareMediaContext(Long shareId, Long pageId, Long revisionId) { }

    private ShareContext resolveShareContext(String token, String pageCode)
    {
        Map<String, Object> share = requireShare(token);
        String mode = shareVersionMode(share.get("versionMode"), "FIXED");
        String requestedCode = text(pageCode);
        Long pageId = toLong(share.get("pageId"));
        Long revisionId = toLong(share.get("revisionId"));
        if (!requestedCode.isBlank())
        {
            if (!SAFE_PAGE_CODE.matcher(requestedCode).matches())
                throw new ServiceException("分享页面编码不合法");
            Map<String, Object> member = dashboardMapper.selectSharePageByCode(toLong(share.get("shareId")), requestedCode);
            if (member != null)
            {
                pageId = toLong(member.get("pageId"));
                revisionId = toLong(member.get("revisionId"));
            }
            else if (!requestedCode.equals(text(share.get("pageCode"))))
            {
                // 仅保留旧单页链接的入口兼容，不能借令牌扩展到集合外页面。
                throw new ServiceException("分享未授权该页面");
            }
        }
        Map<String, Object> page = requirePage(pageId);
        return new ShareContext(share, page, resolveShareRevision(page, revisionId, mode), mode);
    }

    private Map<String, Object> resolveShareRevision(Map<String, Object> page, Long fixedRevisionId, String mode)
    {
        Long pageId = toLong(page.get("pageId"));
        if (!"0".equals(String.valueOf(page.get("status")))) throw new ServiceException("页面已停用");
        boolean follows = "FOLLOW_PUBLISHED".equals(mode);
        Map<String, Object> revision = follows
                ? dashboardMapper.selectRuntimeRevisionMetadata(pageId, toLong(page.get("currentRevisionId")))
                : dashboardMapper.selectRevisionMetadata(fixedRevisionId);
        if (revision == null || !pageId.equals(toLong(revision.get("pageId")))
                || !("PUBLISHED".equals(revision.get("status"))
                    || !follows && "ARCHIVED".equals(revision.get("status"))))
            throw new ServiceException(follows ? "页面尚未发布" : "分享版本不可用");
        return revision;
    }

    private Map<String, Object> shareRuntime(ShareContext context, boolean includeCatalog)
    {
        Map<String, Object> share = context.share();
        Map<String, Object> page = context.page();
        Map<String, Object> revision = dashboardMapper.selectRevisionById(toLong(context.revision().get("revisionId")));
        if (revision == null || !toLong(page.get("pageId")).equals(toLong(revision.get("pageId")))
                || !("PUBLISHED".equals(revision.get("status")) || "ARCHIVED".equals(revision.get("status"))))
            throw new ServiceException("分享版本不可用");
        dashboardMapper.touchShare(toLong(share.get("shareId")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.get("pageId"));
        result.put("pageCode", page.get("pageCode"));
        result.put("pageName", page.get("pageName"));
        result.put("revisionId", revision.get("revisionId"));
        result.put("versionNo", revision.get("versionNo"));
        result.put("versionMode", context.versionMode());
        result.put("preview", false);
        result.put("shared", true);
        result.put("expiresAt", share.get("expiresAt"));
        result.put("entryPageCode", text(share.get("pageCode")));
        Object schema = readJson(revision.get("schemaJson"), "分享页面配置");
        result.put("schema", schema);
        result.put("datasets", shareDatasetCatalog(schema));
        if (includeCatalog) result.put("pages", sharePageCatalog(share));
        return result;
    }

    private List<Map<String, Object>> sharePageCatalog(Map<String, Object> share)
    {
        Long entryPageId = toLong(share.get("pageId"));
        List<Map<String, Object>> members = new ArrayList<>(dashboardMapper.selectShareMemberships(toLong(share.get("shareId"))));
        if (members.stream().noneMatch(member -> entryPageId.equals(toLong(member.get("pageId")))))
        {
            // 旧单页链接可能尚无成员记录，入口授权仍由主表表达。
            members.add(0, Map.of("pageId", entryPageId, "revisionId", share.get("revisionId"), "isEntry", "1", "sortOrder", 0));
        }
        String mode = shareVersionMode(share.get("versionMode"), "FIXED");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> member : members)
        {
            try
            {
                Map<String, Object> page = requirePage(toLong(member.get("pageId")));
                Map<String, Object> revision = resolveShareRevision(page, toLong(member.get("revisionId")), mode);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pageId", page.get("pageId"));
                item.put("pageCode", page.get("pageCode"));
                item.put("pageName", page.get("pageName"));
                item.put("revisionId", revision.get("revisionId"));
                item.put("versionNo", revision.get("versionNo"));
                item.put("isEntry", member.get("isEntry"));
                item.put("sortOrder", member.get("sortOrder"));
                result.add(item);
            }
            catch (ServiceException unavailable)
            {
                // 目录省略已失效成员，直接访问仍由同一解析器拒绝，不增加任何授权。
            }
        }
        return result;
    }

    public Map<String, Object> executeSharePageDataset(String token, Long pageId, String widgetId,
            String datasetCode, Map<String, Object> params, List<Map<String, Object>> requestFilters)
    {
        Map<String, Object> runtime = shareRuntime(resolveShareContext(token, null), false);
        if (!pageId.equals(toLong(runtime.get("pageId"))))
        {
            return errorResult(datasetCode, "FORBIDDEN", "分享页面不匹配");
        }
        return executePageDatasetFromRuntime(runtime, widgetId, datasetCode, params, requestFilters);
    }

    /** 分享集合中按页面编码执行数据集；pageId 仅作为可选的一致性校验。 */
    public Map<String, Object> executeSharePageDataset(String token, String pageCode, Long pageId,
            String widgetId, String datasetCode, Map<String, Object> params,
            List<Map<String, Object>> requestFilters)
    {
        Map<String, Object> runtime = shareRuntime(resolveShareContext(token, pageCode), false);
        if (pageId != null && !pageId.equals(toLong(runtime.get("pageId"))))
            return errorResult(datasetCode, "FORBIDDEN", "分享页面不匹配");
        return executePageDatasetFromRuntime(runtime, widgetId, datasetCode, params, requestFilters);
    }

    public List<Map<String, Object>> selectDatasetList(String keyword, String dataType, String groupCode)
    {
        return dashboardMapper.selectDatasetList(keyword, dataType == null ? null : dataType.toUpperCase(Locale.ROOT),
                groupCode == null ? null : groupCode.trim());
    }

    public List<Map<String, Object>> selectDatasetList(String keyword, String dataType, String groupCode,
            List<String> folderGroupCodes)
    {
        return dashboardMapper.selectDatasetListByFolders(keyword,
                dataType == null ? null : dataType.toUpperCase(Locale.ROOT),
                groupCode == null ? null : groupCode.trim(), folderGroupCodes);
    }

    public List<Map<String, Object>> selectDatasetGroups()
    {
        return dashboardMapper.selectDatasetGroupList();
    }

    public Map<String, Object> createPageFolder(Map<String, Object> body, String username)
    {
        return dataFolders.create("page", body, username);
    }

    public int updatePageFolder(Map<String, Object> body, String username)
    {
        return dataFolders.update("page", body, username);
    }

    public int deletePageFolder(Long folderId)
    {
        return dataFolders.delete("page", folderId);
    }

    public Map<String, Object> createDatasetGroup(Map<String, Object> body, String username)
    {
        return dataFolders.create("dataset", body, username);
    }

    public int updateDatasetGroup(Map<String, Object> body, String username)
    {
        return dataFolders.update("dataset", body, username);
    }

    public int deleteDatasetGroup(Long groupId)
    {
        return dataFolders.delete("dataset", groupId);
    }

    public Map<String, Object> getDataset(Long datasetId)
    {
        return requireDataset(datasetId);
    }

    public Map<String, Object> getDatasetByCode(String datasetCode)
    {
        Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(datasetCode);
        if (dataset == null) throw new ServiceException("数据集不存在");
        return dataset;
    }

    @Transactional
    public Map<String, Object> createDataset(Map<String, Object> body, String username)
    {
        Map<String, Object> dataset = normalizeDataset(body, username, false);
        if ("ACTIVE".equalsIgnoreCase(text(dataset.get("status"))))
            throw new ServiceException("新数据集必须先保存为草稿并测试通过后启用");
        if (dashboardMapper.selectDatasetByCode(String.valueOf(dataset.get("datasetCode"))) != null)
        {
            throw new ServiceException("数据集编码已存在");
        }
        dashboardMapper.insertDataset(dataset);
        return requireDataset(toLong(dataset.get("datasetId")));
    }

    @Transactional
    public int updateDataset(Map<String, Object> body, String username)
    {
        if (body == null) throw new ServiceException("数据集配置不能为空");
        Long datasetId = toLong(body.get("datasetId"));
        Map<String, Object> existing = requireDataset(datasetId);
        if (body.containsKey("datasetCode")
                && !text(body.get("datasetCode")).equalsIgnoreCase(text(existing.get("datasetCode"))))
            throw new ServiceException("数据集编码创建后不可修改");
        // 部分更新（例如只切换状态）也需要使用现有数据类型和配置完成
        // endpoint 生命周期校验；不能因为请求省略 configJson 就解析空字符串。
        Map<String, Object> normalizedBody = new LinkedHashMap<>(body);
        normalizedBody.putIfAbsent("dataType", existing.get("dataType"));
        normalizedBody.putIfAbsent("configJson", existing.get("configJson"));
        normalizedBody.putIfAbsent("fieldSchemaJson", existing.get("fieldSchemaJson"));
        normalizedBody.putIfAbsent("paramSchemaJson", existing.get("paramSchemaJson"));
        Map<String, Object> dataset = normalizeDataset(normalizedBody, username, true);
        dataset.put("datasetId", datasetId);
        boolean contractChanged = !text(existing.get("dataType")).equalsIgnoreCase(text(dataset.get("dataType")))
                || !jsonEquivalent(existing.get("configJson"), dataset.get("configJson"))
                || !jsonEquivalent(existing.get("fieldSchemaJson"), dataset.get("fieldSchemaJson"))
                || !jsonEquivalent(existing.get("paramSchemaJson"), dataset.get("paramSchemaJson"));
        String targetStatus = body.containsKey("status")
                ? text(dataset.get("status")).toUpperCase(Locale.ROOT)
                : text(existing.get("status")).toUpperCase(Locale.ROOT);
        if ("ACTIVE".equals(targetStatus))
        {
            if (contractChanged)
                throw new ServiceException("数据集配置、字段或参数变更后必须先保存草稿并重新测试");
            if (!DATASET_TEST_PASSED.contains(text(existing.get("lastTestStatus")).toUpperCase(Locale.ROOT)))
                throw new ServiceException("数据集必须测试通过后才能启用");
            validateActiveDatasetReference(dataset);
        }
        if (contractChanged) dataset.put("clearLastTest", true);
        return dashboardMapper.updateDataset(dataset);
    }

    private void validateActiveDatasetReference(Map<String, Object> dataset)
    {
        if (!"API".equalsIgnoreCase(text(dataset.get("dataType"))) || dashboardIntegrationService == null) return;
        Map<String, Object> config = parseObject(text(dataset.get("configJson")), "数据集配置");
        String sourceCode = text(config.get("sourceCode"));
        String endpointCode = text(config.get("endpointCode"));
        if (!sourceCode.isBlank() && !endpointCode.isBlank())
        {
            dashboardIntegrationService.validateDatasetReference(sourceCode, endpointCode, true);
            validateApiMediaProjections(sourceCode, config.get("mediaProjections"), true);
        }
    }

    private boolean jsonEquivalent(Object left, Object right)
    {
        try
        {
            return Objects.equals(objectMapper.readTree(left == null ? "null" : String.valueOf(left)),
                    objectMapper.readTree(right == null ? "null" : String.valueOf(right)));
        }
        catch (JsonProcessingException ex)
        {
            return Objects.equals(text(left), text(right));
        }
    }

    public int deleteDataset(Long datasetId)
    {
        requireDataset(datasetId);
        return dashboardMapper.deleteDataset(datasetId);
    }

    public Map<String, Object> testDataset(Long datasetId, Map<String, Object> params)
    {
        Map<String, Object> dataset = requireDataset(datasetId);
        Map<String, Object> result = executeDataset(dataset, params == null ? Collections.emptyMap() : params);
        dashboardMapper.updateDatasetTestResult(datasetId, String.valueOf(result.get("quality")));
        autoCompleteFieldSchema(dataset, datasetId, result);
        return result;
    }

    /**
     * 数据集测试成功后，根据返回行的列名和样本值补齐字段定义。已有字段的标题、
     * 脱敏和展示设置保持不变，只追加来源新返回的字段，避免测试过程覆盖人工配置。
     */
    @SuppressWarnings("unchecked")
    private void autoCompleteFieldSchema(Map<String, Object> dataset, Long datasetId,
            Map<String, Object> result)
    {
        Object rawRows = result.get("rows");
        if (!(rawRows instanceof List) || ((List<?>) rawRows).isEmpty()) return;
        List<Map<String, Object>> inferred = inferFieldSchema((List<?>) rawRows);
        if (inferred.isEmpty()) return;
        List<Map<String, Object>> existing = parseFieldSchema(dataset.get("fieldSchemaJson"));
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> field : existing)
        {
            String name = text(field.get("name"));
            if (!name.isEmpty()) byName.put(name, new LinkedHashMap<>(field));
        }
        boolean changed = false;
        for (Map<String, Object> field : inferred)
        {
            String name = text(field.get("name"));
            if (!byName.containsKey(name))
            {
                byName.put(name, field);
                changed = true;
            }
        }
        List<Map<String, Object>> merged = new ArrayList<>(byName.values());
        result.put("inferredFields", inferred);
        result.put("fieldSchema", merged);
        result.put("fieldsUpdated", changed);
        if (changed)
        {
            try
            {
                dashboardMapper.updateDatasetFieldSchema(datasetId, objectMapper.writeValueAsString(merged));
            }
            catch (JsonProcessingException ex)
            {
                throw new ServiceException("测试结果字段无法保存");
            }
        }
    }

    private List<Map<String, Object>> parseFieldSchema(Object raw)
    {
        String json = raw == null ? "[]" : String.valueOf(raw);
        try
        {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return Collections.emptyList();
            List<Map<String, Object>> fields = new ArrayList<>();
            for (JsonNode item : node)
            {
                if (item.isObject()) fields.add(objectMapper.convertValue(item,
                        new TypeReference<Map<String, Object>>() {}));
            }
            return fields;
        }
        catch (JsonProcessingException ex)
        {
            return Collections.emptyList();
        }
    }

    /**
     * 返回数据集可被组件绑定的字段。API 数据集声明了 totalPath 时，统一结果
     * 还会提供一个顶层 total 虚拟字段，允许指标卡绑定分页总数而不伪造行数据。
     */
    private Set<String> declaredDatasetFields(Map<String, Object> dataset)
    {
        Set<String> fields = parseFieldSchema(dataset.get("fieldSchemaJson")).stream()
                .map(item -> text(item.get("name"))).filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if ("API".equalsIgnoreCase(text(dataset.get("dataType"))))
        {
            String configJson = text(dataset.get("configJson"));
            if (!configJson.isBlank())
            {
                Map<String, Object> config = parseObject(configJson, "数据集配置");
                Object responseValue = config.get("response");
                if (responseValue instanceof Map<?, ?> response
                        && !text(response.get("totalPath")).isBlank())
                {
                    fields.add("total");
                }
            }
        }
        return fields;
    }

    private List<Map<String, Object>> parseParamSchema(Object raw)
    {
        String json = raw == null ? "[]" : String.valueOf(raw);
        try
        {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return Collections.emptyList();
            List<Map<String, Object>> params = new ArrayList<>();
            for (JsonNode item : node)
            {
                if (item.isObject()) params.add(objectMapper.convertValue(item,
                        new TypeReference<Map<String, Object>>() {}));
            }
            return params;
        }
        catch (JsonProcessingException ex)
        {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> inferFieldSchema(List<?> rows)
    {
        LinkedHashMap<String, String> types = new LinkedHashMap<>();
        for (Object item : rows.subList(0, Math.min(rows.size(), 100)))
        {
            if (!(item instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) item;
            for (Map.Entry<?, ?> entry : row.entrySet())
            {
                String name = text(entry.getKey());
                if (name.isEmpty() || !SAFE_FIELD.matcher(name).matches()) continue;
                String candidate = inferFieldType(name, entry.getValue());
                types.put(name, mergeFieldType(types.get(name), candidate));
            }
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : types.entrySet())
        {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", entry.getKey());
            field.put("title", entry.getKey());
            field.put("type", entry.getValue());
            field.put("show", true);
            field.put("sortable", false);
            field.put("aggregate", "none");
            field.put("mask", false);
            fields.add(field);
        }
        return fields;
    }

    private String inferFieldType(String name, Object value)
    {
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        String lower = name.toLowerCase(Locale.ROOT);
        String valueText = text(value);
        if ((lower.contains("time") || lower.contains("date") || lower.endsWith("at"))
                && looksLikeDateTime(valueText)) return "datetime";
        return "string";
    }

    private String mergeFieldType(String current, String candidate)
    {
        if (current == null || current.isEmpty()) return candidate;
        if ("string".equals(current) || "string".equals(candidate)) return "string";
        if ("datetime".equals(current) || "datetime".equals(candidate)) return "datetime";
        if ("number".equals(current) || "number".equals(candidate)) return "number";
        return candidate;
    }

    private boolean looksLikeDateTime(String value)
    {
        if (value.isEmpty()) return false;
        try { LocalDateTime.parse(value.replace("Z", "")); return true; }
        catch (RuntimeException ignored) { }
        try { OffsetDateTime.parse(value); return true; }
        catch (RuntimeException ignored) { }
        try { LocalDate.parse(value); return true; }
        catch (RuntimeException ignored) { return false; }
    }

    /** 返回已登记的数据源目录，只暴露可展示的元数据，不返回连接串和凭证。 */
    public List<Map<String, Object>> listDataSources()
    {
        List<Map<String, Object>> result = new ArrayList<>(systemDataSources());
        for (Map<String, Object> source : dashboardMapper.selectDataSourceList())
            result.add(registeredSourceDescriptor(source));
        return result;
    }

    private List<Map<String, Object>> systemDataSources()
    {
        return List.of(dataSourceDescriptor("labor", "外部业务只读库", "MySQL",
                        "dashboard.datasource.labor.url", "dashboard.datasource.labor.username"),
                dataSourceDescriptor("master", "平台主库（只读）", "MySQL",
                        "spring.datasource.druid.master.url", "spring.datasource.druid.master.username"));
    }

    private Map<String, Object> registeredSourceDescriptor(Map<String, Object> source)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dataSourceId", source.get("dataSourceId"));
        item.put("code", source.get("sourceCode"));
        item.put("name", source.get("sourceName"));
        item.put("type", source.get("sourceType"));
        item.put("folderId", source.getOrDefault("folderId", 0L));
        item.put("folderName", source.get("folderName"));
        item.put("systemSource", false);
        item.put("environment", dataSourceEnvironment(source));
        item.put("configured", true);
        item.put("status", source.get("status"));
        item.put("host", sourceHost(source));
        item.put("remark", source.get("remark"));
        return item;
    }

    public List<Map<String, Object>> listDataSources(String keyword, String type, String status,
            String environmentName, Long folderId, boolean includeChildren)
    {
        List<Long> folderIds = dataFolders.filterIds("source", folderId, includeChildren);
        return listDataSources().stream().filter(item -> matchesSourceFilters(item, keyword, type,
                status, environmentName, folderIds)).toList();
    }

    /** 内置来源至多两条，登记来源只查询当前页需要的区间，避免全量查询再截断。 */
    public List<Map<String, Object>> listDataSourcesPage(String keyword, String type, String status,
            String environmentName, Long folderId, boolean includeChildren, Integer pageNum, Integer pageSize)
    {
        validateListPage(pageNum, pageSize);
        List<Long> folderIds = dataFolders.filterIds("source", folderId, includeChildren);
        List<Map<String, Object>> builtin = systemDataSources().stream().filter(item -> matchesSourceFilters(
                item, keyword, type, status, environmentName, folderIds)).toList();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", text(keyword).trim());
        filters.put("type", text(type).trim());
        filters.put("status", text(status).trim());
        filters.put("environment", text(environmentName).trim());
        filters.put("folderIds", folderIds);
        boolean systemOnly = folderIds != null && folderIds.contains(-1L);
        long registeredCount = systemOnly ? 0 : dashboardMapper.countDataSourcesByFilters(filters);
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize, false);
        page.setTotal(registeredCount + builtin.size());
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset >= page.getTotal()) return page;
        for (long index = offset; index < builtin.size() && page.size() < pageSize; index++)
            page.add(builtin.get((int) index));
        long databaseOffset = Math.max(0L, offset - builtin.size());
        int remaining = pageSize - page.size();
        if (remaining > 0 && databaseOffset < registeredCount)
        {
            filters.put("offset", databaseOffset);
            filters.put("limit", remaining);
            for (Map<String, Object> source : dashboardMapper.selectDataSourcePage(filters))
                page.add(registeredSourceDescriptor(source));
        }
        return page;
    }

    private boolean matchesSourceFilters(Map<String, Object> item, String keyword, String type, String status,
            String environmentName, List<Long> folderIds)
    {
        String search = text(keyword).trim().toLowerCase(Locale.ROOT);
        return (folderIds == null || folderIds.contains(toLong(item.get("folderId"))))
                && (search.isBlank() || (text(item.get("code")) + " " + text(item.get("name"))).toLowerCase(Locale.ROOT).contains(search))
                && (text(type).trim().isBlank() || text(type).trim().equalsIgnoreCase(text(item.get("type"))))
                && (text(status).trim().isBlank() || text(status).trim().equalsIgnoreCase(text(item.get("status"))))
                && (text(environmentName).trim().isBlank() || text(environmentName).trim().equalsIgnoreCase(text(item.get("environment"))));
    }

    private String dataSourceEnvironment(Map<String, Object> source)
    {
        try { return text(parseObject(text(source.get("configJson")), "数据源配置").get("environment")); }
        catch (RuntimeException ignored) { return ""; }
    }

    public List<Map<String, Object>> selectResourceFolders()
    {
        return dashboardMapper.selectResourceFolderList();
    }

    public Map<String, Object> createResourceFolder(Map<String, Object> body, String username)
    {
        return dataFolders.create("resource", body, username);
    }

    public int updateResourceFolder(Map<String, Object> body, String username)
    {
        return dataFolders.update("resource", body, username);
    }

    public int deleteResourceFolder(Long folderId)
    {
        return dataFolders.delete("resource", folderId);
    }

    /** 返回图库资源目录；只返回平台资源路径，不接受也不返回外部 URL。 */
    public List<Map<String, Object>> listAssets(String keyword, String assetType, Long folderId)
    {
        String normalizedType = text(assetType).toUpperCase(Locale.ROOT);
        if (!normalizedType.isEmpty() && !ASSET_TYPES.contains(normalizedType))
            throw new ServiceException("资源类型不支持");
        Long normalizedFolderId = folderId == null ? null : normalizeResourceFolderId(folderId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> asset : dashboardMapper.selectAssetList(text(keyword), normalizedType.isEmpty() ? null : normalizedType, normalizedFolderId))
        {
            Map<String, Object> item = new LinkedHashMap<>(asset);
            item.put("resourceUrl", item.get("resourcePath"));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> listAssetsPage(String keyword, String assetType, Long folderId,
            Integer pageNum, Integer pageSize)
    {
        String normalizedType = text(assetType).toUpperCase(Locale.ROOT);
        if (!normalizedType.isEmpty() && !ASSET_TYPES.contains(normalizedType))
            throw new ServiceException("资源类型不支持");
        // 文件夹校验可能查询数据库，必须先完成，再为实际列表开启分页。
        Long normalizedFolderId = folderId == null ? null : normalizeResourceFolderId(folderId);
        startResourcePage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = dashboardMapper.selectAssetList(text(keyword),
                    normalizedType.isEmpty() ? null : normalizedType, normalizedFolderId);
            rows.forEach(item -> item.put("resourceUrl", item.get("resourcePath")));
            // 保留 PageHelper 返回的 Page 类型及 total，不能复制到普通 ArrayList。
            return rows;
        }
        finally
        {
            PageHelper.clearPage();
        }
    }

    public List<Map<String, Object>> listAssets(String keyword, String assetType, Long folderId, boolean includeChildren)
    {
        String normalizedType = text(assetType).toUpperCase(Locale.ROOT);
        if (!normalizedType.isEmpty() && !ASSET_TYPES.contains(normalizedType)) throw new ServiceException("资源类型不支持");
        List<Map<String, Object>> rows = dashboardMapper.selectAssetListByFolders(text(keyword), normalizedType,
                dataFolders.filterIds("resource", folderId, includeChildren));
        rows.forEach(item -> item.put("resourceUrl", item.get("resourcePath")));
        return rows;
    }

    public List<Map<String, Object>> listAssetsPage(String keyword, String assetType, Long folderId,
            boolean includeChildren, Integer pageNum, Integer pageSize)
    {
        String normalizedType = text(assetType).toUpperCase(Locale.ROOT);
        if (!normalizedType.isEmpty() && !ASSET_TYPES.contains(normalizedType)) throw new ServiceException("资源类型不支持");
        List<Long> folderIds = dataFolders.filterIds("resource", folderId, includeChildren);
        startResourcePage(pageNum, pageSize);
        try
        {
            List<Map<String, Object>> rows = dashboardMapper.selectAssetListByFolders(text(keyword), normalizedType, folderIds);
            rows.forEach(item -> item.put("resourceUrl", item.get("resourcePath")));
            return rows;
        }
        finally { PageHelper.clearPage(); }
    }

    private void startResourcePage(Integer pageNum, Integer pageSize)
    {
        validateListPage(pageNum, pageSize);
        PageHelper.startPage(pageNum, pageSize).setReasonable(false);
    }

    private void validateListPage(Integer pageNum, Integer pageSize)
    {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 1000)
            throw new ServiceException("分页参数不合法，页码至少为 1，每页条数须在 1-1000 之间");
    }

    @Transactional
    public Map<String, Object> createAsset(Map<String, Object> body, String username)
    {
        Map<String, Object> asset = normalizeAsset(body, false, username, null);
        String code = text(asset.get("assetCode"));
        if (dashboardMapper.selectAssetByCode(code) != null)
            throw new ServiceException("资源编码已存在");
        dashboardMapper.insertAsset(asset);
        return dashboardMapper.selectAssetById(toLong(asset.get("assetId")));
    }

    @Transactional
    public int updateAsset(Map<String, Object> body, String username)
    {
        Long assetId = toLong(body == null ? null : body.get("assetId"));
        Map<String, Object> existing = assetId == null ? null : dashboardMapper.selectAssetById(assetId);
        if (existing == null)
            throw new ServiceException("资源不存在");
        Map<String, Object> asset = normalizeAsset(body, true, username, text(existing.get("assetType")));
        asset.put("assetId", assetId);
        return dashboardMapper.updateAsset(asset);
    }

    public int deleteAsset(Long assetId)
    {
        if (assetId == null || dashboardMapper.selectAssetById(assetId) == null)
            throw new ServiceException("资源不存在");
        return dashboardMapper.deleteAsset(assetId);
    }

    /** 返回地图资源目录；内置 classpath 地图和管理端登记地图统一暴露相同路径。 */
    public List<Map<String, Object>> listMapResources(String keyword, Long folderId)
    {
        Long normalizedFolderId = folderId == null ? null : normalizeResourceFolderId(folderId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (normalizedFolderId == null || normalizedFolderId == 0)
        {
            Map<String, Object> builtin = new LinkedHashMap<>();
            builtin.put("mapCode", "demo-region");
            builtin.put("mapName", "内置示例区域");
            builtin.put("folderId", 0L);
            builtin.put("folderName", null);
            builtin.put("status", "0");
            builtin.put("builtin", true);
            builtin.put("resourcePath", "/dashboard/assets/map/demo-region.json");
            result.add(builtin);
        }
        for (Map<String, Object> resource : dashboardMapper.selectMapResourceList(text(keyword), normalizedFolderId))
        {
            Map<String, Object> item = new LinkedHashMap<>(resource);
            item.put("builtin", false);
            item.put("resourcePath", "/dashboard/assets/map/" + text(item.get("mapCode")) + ".json");
            result.add(item);
        }
        if (!text(keyword).isEmpty())
        {
            String value = text(keyword).toLowerCase(Locale.ROOT);
            result.removeIf(item -> !(text(item.get("mapCode")).toLowerCase(Locale.ROOT).contains(value)
                    || text(item.get("mapName")).toLowerCase(Locale.ROOT).contains(value)));
        }
        return result;
    }

    public List<Map<String, Object>> listMapResourcesPage(String keyword, Long folderId,
            Integer pageNum, Integer pageSize)
    {
        Long normalizedFolderId = folderId == null ? null : normalizeResourceFolderId(folderId);
        startResourcePage(pageNum, pageSize);
        try
        {
            // SQL 先合并内置地图，再统一筛选、计数和分页，内置项只占第一条。
            List<Map<String, Object>> rows = dashboardMapper.selectMapResourcePage(text(keyword), normalizedFolderId);
            rows.forEach(item -> {
                item.put("builtin", "1".equals(String.valueOf(item.get("builtin"))));
                item.put("resourcePath", "/dashboard/assets/map/" + text(item.get("mapCode")) + ".json");
            });
            return rows;
        }
        finally
        {
            PageHelper.clearPage();
        }
    }

    public List<Map<String, Object>> listMapResources(String keyword, Long folderId, boolean includeChildren)
    {
        return mapResourceRows(dashboardMapper.selectMapResourcePageByFolders(text(keyword),
                dataFolders.filterIds("resource", folderId, includeChildren)));
    }

    public List<Map<String, Object>> listMapResourcesPage(String keyword, Long folderId, boolean includeChildren,
            Integer pageNum, Integer pageSize)
    {
        List<Long> folderIds = dataFolders.filterIds("resource", folderId, includeChildren);
        startResourcePage(pageNum, pageSize);
        try { return mapResourceRows(dashboardMapper.selectMapResourcePageByFolders(text(keyword), folderIds)); }
        finally { PageHelper.clearPage(); }
    }

    private List<Map<String, Object>> mapResourceRows(List<Map<String, Object>> rows)
    {
        rows.forEach(item -> {
            item.put("builtin", "1".equals(String.valueOf(item.get("builtin"))));
            item.put("resourcePath", "/dashboard/assets/map/" + text(item.get("mapCode")) + ".json");
        });
        return rows;
    }

    public Map<String, Object> getMapResource(Long mapId)
    {
        Map<String, Object> resource = dashboardMapper.selectMapResourceById(mapId);
        if (resource == null) throw new ServiceException("地图资源不存在");
        resource.put("resourcePath", "/dashboard/assets/map/" + text(resource.get("mapCode")) + ".json");
        resource.put("geojson", readJson(resource.remove("geojsonJson"), "地图 GeoJSON"));
        return resource;
    }

    @Transactional
    public Map<String, Object> createMapResource(Map<String, Object> body, String username)
    {
        Map<String, Object> resource = normalizeMapResource(body, false, username);
        String code = text(resource.get("mapCode"));
        if ("demo-region".equals(code) || dashboardMapper.selectMapResourceByCode(code) != null)
            throw new ServiceException("地图编码已存在或为内置编码");
        dashboardMapper.insertMapResource(resource);
        return getMapResource(toLong(resource.get("mapId")));
    }

    @Transactional
    public int updateMapResource(Map<String, Object> body, String username)
    {
        Long mapId = toLong(body == null ? null : body.get("mapId"));
        Map<String, Object> existing = mapId == null ? null : dashboardMapper.selectMapResourceById(mapId);
        if (existing == null) throw new ServiceException("地图资源不存在");
        Map<String, Object> resource = normalizeMapResource(body, true, username);
        resource.put("mapId", mapId);
        return dashboardMapper.updateMapResource(resource);
    }

    public int deleteMapResource(Long mapId)
    {
        Map<String, Object> resource = mapId == null ? null : dashboardMapper.selectMapResourceById(mapId);
        if (resource == null) throw new ServiceException("地图资源不存在");
        return dashboardMapper.deleteMapResource(mapId);
    }

    /** 地图接口只返回受控 GeoJSON，不允许把它变成任意文件读取或网络代理。 */
    public String mapResourceJson(String mapCode)
    {
        String code = text(mapCode);
        if (!code.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new ServiceException("地图编码不合法");
        Map<String, Object> registered = dashboardMapper.selectMapResourceByCode(code);
        if (registered != null)
        {
            if (!"0".equals(String.valueOf(registered.get("status")))) throw new ServiceException("地图资源已停用");
            return text(registered.get("geojsonJson"));
        }
        ClassPathResource resource = new ClassPathResource("dashboard/maps/" + code + ".json");
        if (!resource.exists() || !resource.isReadable()) throw new ServiceException("地图资源不存在");
        try (InputStream input = resource.getInputStream())
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex)
        {
            throw new ServiceException("地图资源读取失败");
        }
    }

    private Map<String, Object> normalizeAsset(Map<String, Object> body, boolean partial, String username, String existingType)
    {
        if (body == null) throw new ServiceException("资源配置不能为空");
        Map<String, Object> asset = new LinkedHashMap<>();
        String code = text(body.get("assetCode"));
        if (!partial || body.containsKey("assetCode"))
        {
            if (!code.matches("[a-z0-9][a-z0-9_-]{2,63}")) throw new ServiceException("资源编码格式不合法");
            asset.put("assetCode", code);
        }
        String name = text(body.get("assetName"));
        if (!partial || body.containsKey("assetName"))
        {
            if (name.isBlank() || name.length() > 100) throw new ServiceException("资源名称不能为空且不能超过 100 个字符");
            asset.put("assetName", name);
        }
        if (!partial || body.containsKey("folderId"))
        {
            asset.put("folderId", normalizeResourceFolderId(body.get("folderId")));
        }
        String type = text(body.get("assetType")).toUpperCase(Locale.ROOT);
        if (!partial || body.containsKey("assetType"))
        {
            if (!ASSET_TYPES.contains(type)) throw new ServiceException("资源类型只支持 IMAGE 或 VIDEO");
            asset.put("assetType", type);
        }
        if (!partial || body.containsKey("resourcePath"))
        {
            validateAssetPath(text(body.get("resourcePath")), type.isBlank() ? existingType : type);
            asset.put("resourcePath", text(body.get("resourcePath")));
        }
        if (!partial || body.containsKey("category"))
        {
            String category = text(body.get("category"));
            if (category.length() > 64) throw new ServiceException("资源分类不能超过 64 个字符");
            asset.put("category", category);
        }
        if (body.containsKey("mimeType")) asset.put("mimeType", text(body.get("mimeType")));
        if (body.containsKey("fileSize"))
        {
            Long size = toLong(body.get("fileSize"));
            if (size != null && (size < 0 || size > 100L * 1024 * 1024)) throw new ServiceException("资源大小不合法");
            asset.put("fileSize", size);
        }
        if (!partial || body.containsKey("status"))
        {
            String status = text(body.get("status"));
            if (!ASSET_STATUSES.contains(status)) throw new ServiceException("资源状态不合法");
            asset.put("status", status);
        }
        if (!partial || body.containsKey("remark"))
        {
            String remark = text(body.get("remark"));
            if (remark.length() > 500) throw new ServiceException("资源备注不能超过 500 个字符");
            asset.put("remark", remark);
        }
        asset.put("createBy", username);
        asset.put("updateBy", username);
        return asset;
    }

    private void validateAssetPath(String path, String assetType)
    {
        if (path.isBlank() || path.length() > 500
                || !(path.startsWith("/profile/") || path.startsWith("/static/")) || path.contains("..")
                || path.contains("\\") || path.contains("://"))
            throw new ServiceException("资源路径必须是平台 /profile/ 或 /static/ 路径");
        String lower = path.toLowerCase(Locale.ROOT);
        String[] extensions = "IMAGE".equals(assetType)
                ? new String[] { ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg" }
                : new String[] { ".mp4", ".webm", ".ogg", ".m3u8" };
        boolean matched = Arrays.stream(extensions).anyMatch(lower::endsWith);
        if (!matched) throw new ServiceException("资源文件扩展名与类型不匹配");
    }

    private Map<String, Object> normalizeMapResource(Map<String, Object> body, boolean partial, String username)
    {
        if (body == null) throw new ServiceException("地图配置不能为空");
        Map<String, Object> resource = new LinkedHashMap<>();
        String code = text(body.get("mapCode"));
        if (!partial || body.containsKey("mapCode"))
        {
            if (!code.matches("[a-z0-9][a-z0-9_-]{2,63}")) throw new ServiceException("地图编码格式不合法");
            resource.put("mapCode", code);
        }
        String name = text(body.get("mapName"));
        if (!partial || body.containsKey("mapName"))
        {
            if (name.isBlank() || name.length() > 100) throw new ServiceException("地图名称不能为空且不能超过 100 个字符");
            resource.put("mapName", name);
        }
        if (!partial || body.containsKey("folderId"))
        {
            resource.put("folderId", normalizeResourceFolderId(body.get("folderId")));
        }
        if (!partial || body.containsKey("geojsonJson"))
        {
            String json = jsonString(body.get("geojsonJson"), "");
            validateGeoJson(json);
            resource.put("geojsonJson", json);
        }
        if (!partial || body.containsKey("status"))
        {
            String status = text(body.get("status"));
            if (!ASSET_STATUSES.contains(status)) throw new ServiceException("地图状态不合法");
            resource.put("status", status);
        }
        if (!partial || body.containsKey("remark"))
        {
            String remark = text(body.get("remark"));
            if (remark.length() > 500) throw new ServiceException("地图备注不能超过 500 个字符");
            resource.put("remark", remark);
        }
        resource.put("createBy", username);
        resource.put("updateBy", username);
        return resource;
    }

    private void validateGeoJson(String json)
    {
        if (json.isBlank() || json.length() > MAX_GEOJSON_CHARS) throw new ServiceException("GeoJSON 不能为空且不能超过 4 MB");
        JsonNode root;
        try { root = objectMapper.readTree(json); }
        catch (JsonProcessingException ex) { throw new ServiceException("GeoJSON 格式不合法"); }
        if (root == null || !root.isObject() || !"FeatureCollection".equals(root.path("type").asText())
                || !root.path("features").isArray() || root.path("features").size() > 20000)
            throw new ServiceException("GeoJSON 必须是 FeatureCollection 且 features 不超过 20000 个");
        String lower = json.toLowerCase(Locale.ROOT);
        if (lower.contains("javascript:") || lower.contains("<script") || lower.contains("<iframe"))
            throw new ServiceException("GeoJSON 包含不允许的脚本内容");
    }

    /** 返回数据源编辑信息；连接串和密码/令牌永远不返回。 */
    public Map<String, Object> getDataSource(String code)
    {
        Map<String, Object> source = dashboardMapper.selectDataSourceByCode(text(code));
        if (source == null) throw new ServiceException("数据源不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSourceId", source.get("dataSourceId"));
        result.put("sourceCode", source.get("sourceCode"));
        result.put("sourceName", source.get("sourceName"));
        result.put("sourceType", source.get("sourceType"));
        result.put("folderId", source.getOrDefault("folderId", 0L));
        result.put("folderName", source.get("folderName"));
        result.put("systemSource", false);
        result.put("status", source.get("status"));
        // 详情接口也不能回传 JDBC 地址、HTTP 基础地址或账号；编辑连接配置时由
        // 管理员重新填写完整值，名称/状态等非敏感字段仍可在原配置上直接修改。
        result.put("configJson", safeDataSourceConfig(source));
        result.put("configMasked", true);
        result.put("hasSecret", !text(source.get("secretCiphertext")).isEmpty());
        result.put("remark", source.get("remark"));
        return result;
    }

    /** 配置编辑专用入口：由 Controller 校验编辑权限，不供普通列表和运行页面使用。 */
    public Map<String,Object> getDataSourceConfiguration(String code)
    {
        Map<String,Object> source=dashboardMapper.selectDataSourceByCode(text(code));
        if(source==null) throw new ServiceException("数据源不存在");
        Map<String,Object> result=getDataSource(code);
        Map<String,Object> config=parseObject(text(source.get("configJson")),"数据源配置");
        result.put("configJson",source.get("configJson"));result.put("configMasked",false);
        result.put("secret",decryptSecret(text(source.get("secretCiphertext"))));
        if(!text(config.get("credentialRef")).isBlank()) {
            try {
                result.put("resolvedSecret",DashboardIntegrationCredentials.resolve(text(config.get("credentialRef")),text(source.get("secretCiphertext")),environment));
                result.put("credentialResolved",true);
            } catch(ServiceException ex) {result.put("credentialResolved",false);}
        }
        return result;
    }

    private String safeDataSourceConfig(Map<String, Object> source)
    {
        Map<String, Object> safe = new LinkedHashMap<>();
        try
        {
            Map<String, Object> config = parseObject(text(source.get("configJson")), "数据源配置");
            if ("HTTP".equalsIgnoreCase(text(source.get("sourceType"))))
            {
                // 路径和默认方法不是凭证，但不返回基础地址，避免泄露内部网络拓扑。
                Set<String> allowed = Set.of("path", "method", "networkProfile", "allowedHosts",
                        "allowedCidrs", "allowedPorts", "authProvider", "identity", "credentialRef",
                        "timeoutSeconds", "concurrencyLimit", "timezone", "environment", "rateLimit");
                for (String key : allowed) if (config.containsKey(key)) safe.put(key, config.get(key));
            }
            else if ("WEBSOCKET".equalsIgnoreCase(text(source.get("sourceType"))))
            {
                // WebSocket 只返回路径摘要；协议、主机和请求头属于服务端拓扑。
                safe.put("path", text(config.get("path")));
            }
        }
        catch (RuntimeException ignored)
        {
            // 详情接口宁可返回空的脱敏摘要，也不能把原始配置透传到浏览器。
        }
        return jsonString(safe, "{}");
    }

    @Transactional
    public Map<String, Object> createDataSource(Map<String, Object> body, String username)
    {
        Map<String, Object> source = normalizeDataSource(body, username, false);
        String code = text(source.get("sourceCode"));
        if (dashboardMapper.selectDataSourceByCode(code) != null || isReservedSourceCode(code))
            throw new ServiceException("数据源编码已存在或为系统保留编码");
        dashboardMapper.insertDataSource(source);
        return getDataSource(code);
    }

    @Transactional
    public Map<String, Object> updateDataSource(Map<String, Object> body, String username)
    {
        Long id = toLong(body == null ? null : body.get("dataSourceId"));
        if (id == null) throw new ServiceException("数据源编号不能为空");
        Map<String, Object> existing = dashboardMapper.selectDataSourceById(id);
        if (existing == null) throw new ServiceException("数据源不存在");
        if (body != null && body.containsKey("sourceCode"))
        {
            String sourceCode = text(body.get("sourceCode"));
            if (!sourceCode.equalsIgnoreCase(text(existing.get("sourceCode"))))
                throw new ServiceException("数据源编码创建后不可修改");
        }
        if (body != null && body.containsKey("sourceType")
                && !text(body.get("sourceType")).equalsIgnoreCase(text(existing.get("sourceType"))))
            throw new ServiceException("数据源类型创建后不可修改");
        Map<String, Object> normalizedBody = new LinkedHashMap<>(body);
        String existingType = text(existing.get("sourceType")).toUpperCase(Locale.ROOT);
        normalizedBody.putIfAbsent("sourceType", existingType);
        if (body.containsKey("configJson"))
        {
            Map<String, Object> config = parseObject(text(existing.get("configJson")), "原数据源配置");
            Map<String, Object> configPatch = parseObject(
                    jsonString(body.get("configJson"), "{}"), "数据源配置");
            config.putAll(configPatch);
            validateDataSourceConfig(existingType, config);
            normalizedBody.put("configJson", jsonString(config, "{}"));
        }
        Map<String, Object> source = normalizeDataSource(normalizedBody, username, true);
        source.put("dataSourceId", id);
        // 未提交新 secret 时让 MyBatis 保留原密文；元数据编辑不能意外清空凭证。
        dashboardMapper.updateDataSource(source);
        return getDataSource(text(existing.get("sourceCode")));
    }

    @Transactional
    public int deleteDataSource(Long id)
    {
        Map<String, Object> source = dashboardMapper.selectDataSourceById(id);
        if (source == null) throw new ServiceException("数据源不存在");
        String code = text(source.get("sourceCode"));
        if (dashboardMapper.countDatasetReferences(code) > 0)
            throw new ServiceException("数据源仍被数据集引用，停用后再删除");
        return dashboardMapper.deleteDataSource(id);
    }

    private Map<String, Object> dataSourceDescriptor(String code, String name, String type,
            String urlKey, String usernameKey)
    {
        String url = text(environment.getProperty(urlKey));
        String username = text(environment.getProperty(usernameKey));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("type", type);
        item.put("folderId", -1L);
        item.put("folderName", "系统数据源");
        item.put("systemSource", true);
        item.put("environment", "");
        item.put("configured", !url.isEmpty() && !username.isEmpty());
        item.put("status", url.isEmpty() || username.isEmpty() ? "NOT_CONNECTED" : "READY");
        item.put("host", safeJdbcHost(url));
        return item;
    }

    private Map<String, Object> normalizeDataSource(Map<String, Object> body, String username, boolean partial)
    {
        if (body == null) throw new ServiceException("数据源配置不能为空");
        Map<String, Object> source = new LinkedHashMap<>();
        if (!partial || body.containsKey("folderId"))
            source.put("folderId", body.containsKey("folderId") ? dataFolders.validateTarget("source", body.get("folderId")) : 0L);
        String code = text(body.get("sourceCode"));
        if (!partial || body.containsKey("sourceCode"))
        {
            if (!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) throw new ServiceException("数据源编码格式不合法");
            source.put("sourceCode", code);
        }
        String name = text(body.get("sourceName"));
        if (!partial || body.containsKey("sourceName"))
        {
            if (name.isBlank() || name.length() > 100) throw new ServiceException("数据源名称不能为空且不能超过 100 个字符");
            source.put("sourceName", name);
        }
        String type = text(body.get("sourceType")).toUpperCase(Locale.ROOT);
        if (!partial || body.containsKey("sourceType"))
        {
            if (!DATA_SOURCE_TYPES.contains(type)) throw new ServiceException("数据源类型只支持 MYSQL、HTTP 或 WEBSOCKET");
            source.put("sourceType", type);
        }
        if (!partial || body.containsKey("configJson"))
        {
            Map<String, Object> config = parseObject(jsonString(body.get("configJson"), "{}"), "数据源配置");
            validateDataSourceConfig(type, config);
            source.put("configJson", jsonString(config, "{}"));
        }
        if (body.containsKey("secret") && !text(body.get("secret")).isBlank())
            source.put("secretCiphertext", encryptSecret(text(body.get("secret"))));
        String status = text(body.get("status")).toUpperCase(Locale.ROOT);
        if (!partial || body.containsKey("status"))
        {
            if (!DATA_SOURCE_STATUSES.contains(status)) throw new ServiceException("数据源状态不合法");
            source.put("status", status);
        }
        if (!partial || body.containsKey("remark"))
        {
            String remark = text(body.get("remark"));
            if (remark.length() > 500) throw new ServiceException("数据源备注不能超过 500 个字符");
            source.put("remark", remark);
        }
        if (!partial) source.put("createBy", username);
        source.put("updateBy", username);
        return source;
    }

    private void validateDataSourceConfig(String type, Map<String, Object> config)
    {
        if (config == null) throw new ServiceException("数据源配置不能为空");
        if ("MYSQL".equals(type))
        {
            String jdbcUrl = text(config.get("jdbcUrl"));
            String dbUser = text(config.get("username"));
            if (!jdbcUrl.startsWith("jdbc:mysql://") || dbUser.isBlank()) throw new ServiceException("MySQL 数据源必须填写 jdbcUrl 和用户名");
            validateJdbcUrl(jdbcUrl);
            Set<String> allowed = Set.of("jdbcUrl", "username");
            config.keySet().forEach(key -> { if (!allowed.contains(key)) throw new ServiceException("MySQL 数据源配置属性不支持: " + key); });
        }
        else if ("HTTP".equals(type))
        {
            String baseUrl = text(config.get("baseUrl"));
            String path = text(config.get("path"));
            String method = text(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
            if (baseUrl.isBlank() || path.isBlank() || !path.startsWith("/")) throw new ServiceException("HTTP 数据源必须填写基础地址和路径");
            validateHttpSourceConfig(config);
            validateConfiguredEndpoint(baseUrl, config);
            if (!Set.of("GET", "POST").contains(method)) throw new ServiceException("HTTP 数据源只允许 GET 或受控 POST");
            if (path.length() > 512) throw new ServiceException("HTTP 数据源路径过长");
            Set<String> allowed = Set.of("baseUrl", "path", "method", "networkProfile", "allowedHosts",
                    "allowedCidrs", "allowedPorts", "authProvider", "identity", "credentialRef",
                    "timeoutSeconds", "concurrencyLimit", "timezone", "environment", "rateLimit");
            config.keySet().forEach(key -> { if (!allowed.contains(key)) throw new ServiceException("HTTP 数据源配置属性不支持: " + key); });
            config.put("method", method);
        }
        else if ("WEBSOCKET".equals(type))
        {
            String baseUrl = text(config.get("baseUrl"));
            String path = text(config.get("path"));
            if (baseUrl.isBlank() || path.isBlank() || !path.startsWith("/"))
                throw new ServiceException("WebSocket 数据源必须填写基础地址和路径");
            validateConfiguredWebSocketEndpoint(baseUrl);
            if (path.length() > 512) throw new ServiceException("WebSocket 数据源路径过长");
            // 前端编辑器沿用 HTTP 表单字段，会带一个无意义的 method；允许并忽略
            // 该字段以兼容旧页面，不把它当作上游消息或脚本入口。
            Set<String> allowed = Set.of("baseUrl", "path", "method");
            config.keySet().forEach(key -> { if (!allowed.contains(key)) throw new ServiceException("WebSocket 数据源配置属性不支持: " + key); });
        }
    }

    private void validateJdbcUrl(String jdbcUrl)
    {
        try
        {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            if (!Set.of("mysql", "mariadb").contains(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null)
                throw new ServiceException("MySQL 数据源地址不合法");
            String allowlist = text(environment.getProperty("dashboard.datasource.allowed-hosts", "127.0.0.1,localhost"));
            boolean allowed = Arrays.stream(allowlist.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                    .anyMatch(item -> item.equalsIgnoreCase(uri.getHost()));
            if (!allowed) throw new ServiceException("MySQL 数据源主机不在服务端白名单");
        }
        catch (IllegalArgumentException ex)
        {
            throw new ServiceException("MySQL 数据源地址不合法");
        }
    }

    private boolean isReservedSourceCode(String code)
    {
        return "labor".equalsIgnoreCase(code) || "master".equalsIgnoreCase(code) || LABOR_ENDPOINT_PROPERTIES.containsKey(code);
    }

    private String sourceHost(Map<String, Object> source)
    {
        try
        {
            Map<String, Object> config = parseObject(text(source.get("configJson")), "数据源配置");
            if ("MYSQL".equalsIgnoreCase(text(source.get("sourceType")))) return safeJdbcHost(text(config.get("jdbcUrl")));
            URI uri = URI.create(text(config.get("baseUrl")));
            return uri.getHost() == null ? "已配置" : uri.getHost();
        }
        catch (RuntimeException ex) { return "已配置"; }
    }

    /** 仅执行 SELECT 1 的连接检查，返回耗时和质量状态。 */
    public Map<String, Object> testDataSource(String code)
    {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", text(code));
        try
        {
            Map<String, Object> registered = dashboardMapper.selectDataSourceByCode(text(code));
            if (registered != null && "HTTP".equalsIgnoreCase(text(registered.get("sourceType"))))
            {
                List<Map<String,Object>> endpoints=dashboardIntegrationService.listEndpoints(code);
                Map<String,Object> endpoint=endpoints.stream().filter(item->"JSON".equals(item.get("responseType"))).findFirst().orElse(null);
                if(endpoint==null) throw new ServiceException("请先在该数据源下登记接口，再使用接口测试");
                Map<String,Object> tested=dashboardIntegrationService.testEndpoint(code,text(endpoint.get("endpointCode")),Map.of());
                String quality=text(tested.get("quality"));
                result.put("status",Set.of("SUCCESS","NO_DATA").contains(quality)?"CONNECTED":"NOT_CONNECTED");
                result.put("quality",quality);result.put("message",tested.getOrDefault("message","接口测试已执行"));
            }
            else if (registered != null && "WEBSOCKET".equalsIgnoreCase(text(registered.get("sourceType"))))
            {
                EndpointSettings endpoint = resolveRegisteredWebsocketEndpoint(registered);
                testWebsocketEndpoint(endpoint);
                result.put("status", "CONNECTED");
                result.put("quality", "SUCCESS");
            }
            else
            {
                DataSourceSettings settings = resolveDataSource(code);
                int previousTimeout = 0;
                try
                {
                    previousTimeout = DriverManager.getLoginTimeout();
                    DriverManager.setLoginTimeout(5);
                    try (Connection connection = DriverManager.getConnection(settings.url, settings.username, settings.password);
                            java.sql.Statement statement = connection.createStatement();
                            ResultSet ignored = statement.executeQuery("SELECT 1"))
                    {
                        result.put("status", "CONNECTED");
                        result.put("quality", "SUCCESS");
                    }
                }
                finally
                {
                    DriverManager.setLoginTimeout(previousTimeout);
                }
            }
        }
        catch (Exception ex)
        {
            result.put("status", "NOT_CONNECTED");
            result.put("quality", classifyError(ex));
            result.put("message", safeMessage(ex));
        }
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    private String safeJdbcHost(String url)
    {
        if (url == null || url.isBlank()) return "-";
        try
        {
            String value = url.startsWith("jdbc:") ? url.substring(5) : url;
            URI uri = URI.create(value);
            return uri.getHost() == null ? "已配置" : uri.getHost();
        }
        catch (RuntimeException ex)
        {
            return "已配置";
        }
    }

    public Map<String, Object> executeDatasetByCode(String datasetCode, Map<String, Object> params)
    {
        return executeDatasetByCode(datasetCode, params, Collections.emptyList());
    }

    /** Designer preview applies the same declared-field filters as runtime, including unsaved widget edits. */
    public Map<String, Object> executeDatasetByCode(String datasetCode, Map<String, Object> params,
            List<Map<String, Object>> filters)
    {
        Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(datasetCode);
        if (dataset == null)
        {
            return errorResult(datasetCode, "DATASET_NOT_FOUND", "数据集不存在");
        }
        if ("DISABLED".equals(dataset.get("status")))
        {
            return errorResult(datasetCode, "DISABLED", "数据集已停用");
        }
        return executeDataset(dataset, params == null ? Collections.emptyMap() : params, filters == null ? Collections.emptyList() : filters);
    }

    /** 运行态只允许读取已发布页面中明确绑定到组件的数据集。 */
    public Map<String, Object> executePageDataset(Long pageId, String widgetId, String datasetCode,
            Map<String, Object> params)
    {
        return executePageDataset(pageId, widgetId, datasetCode, params, Collections.emptyList(), false);
    }

    public Map<String, Object> executePageDataset(Long pageId, String widgetId, String datasetCode,
            Map<String, Object> params, boolean preview)
    {
        return executePageDataset(pageId, widgetId, datasetCode, params, Collections.emptyList(), preview);
    }

    /**
     * 执行页面组件绑定的数据集。过滤条件由服务端按数据集字段声明校验并执行，
     * 页面请求不能借此提交任意 SQL、URL 或未声明参数。
     */
    public Map<String, Object> executePageDataset(Long pageId, String widgetId, String datasetCode,
            Map<String, Object> params, List<Map<String, Object>> requestFilters, boolean preview)
    {
        Map<String, Object> runtime = getRuntimePage(pageId, preview);
        return executePageDatasetFromRuntime(runtime, widgetId, datasetCode, params, requestFilters);
    }

    public Map<String, Object> executePageDataset(Long pageId, String widgetId, String datasetCode,
            Map<String, Object> params, List<Map<String, Object>> requestFilters, boolean preview,
            Long revisionId)
    {
        Map<String, Object> runtime = preview && revisionId != null
                ? getRevisionPreview(pageId, revisionId)
                : getRuntimePage(pageId, preview);
        return executePageDatasetFromRuntime(runtime, widgetId, datasetCode, params, requestFilters);
    }

    private Map<String, Object> executePageDatasetFromRuntime(Map<String, Object> runtime, String widgetId,
            String datasetCode, Map<String, Object> params, List<Map<String, Object>> requestFilters)
    {
        Object schemaValue = runtime.get("schema");
        if (!(schemaValue instanceof Map)) return errorResult(datasetCode, "FORBIDDEN", "页面配置不可用");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemaValue;
        JsonNode widgets = objectMapper.valueToTree(schema.get("widgets"));
        boolean bound = false;
        List<Map<String, Object>> bindingFilters = new ArrayList<>();
        if (widgets != null && widgets.isArray())
        {
            for (JsonNode widget : widgets)
            {
                if (!widgetId.equals(widget.path("id").asText(""))) continue;
                bound = datasetCode.equals(widget.path("binding").path("datasetCode").asText(""));
                JsonNode filters = widget.path("binding").path("filters");
                if (filters.isArray())
                {
                    bindingFilters = filterMaps(filters);
                }
                break;
            }
        }
        if (!bound) return errorResult(datasetCode, "FORBIDDEN", "数据集未绑定到该页面组件");
        Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(datasetCode);
        if (dataset == null)
        {
            return errorResult(datasetCode, "DATASET_NOT_FOUND", "数据集不存在");
        }
        if (!"ACTIVE".equals(dataset.get("status")))
        {
            return errorResult(datasetCode, "DISABLED", "数据集未启用");
        }
        List<Map<String, Object>> filters = new ArrayList<>(bindingFilters);
        if (requestFilters != null) filters.addAll(requestFilters);
        Map<String, Object> result = executeDataset(dataset, params == null ? Collections.emptyMap() : params, filters);
        result.put("pageId", runtime.get("pageId"));
        result.put("widgetId", widgetId);
        result.put("revisionId", runtime.get("revisionId"));
        return result;
    }

    public Map<String, Object> executeDataset(Map<String, Object> dataset, Map<String, Object> params)
    {
        return executeDataset(dataset, params, Collections.emptyList());
    }

    public Map<String, Object> executeDataset(Map<String, Object> dataset, Map<String, Object> params,
            List<Map<String, Object>> filters)
    {
        String datasetCode = String.valueOf(dataset.get("datasetCode"));
        try
        {
            String dataType = String.valueOf(dataset.get("dataType")).toUpperCase(Locale.ROOT);
            Map<String, Object> config = parseObject(String.valueOf(dataset.get("configJson")), "数据集配置");
            // 执行超时属于数据集元数据，执行器统一从配置副本读取，避免前端把超时写入
            // 任意来源配置或绕过服务端上限。
            if (!config.containsKey("timeoutSeconds")) config.put("timeoutSeconds", dataset.get("timeoutSeconds"));
            boolean rejectUnknownParams = "API".equals(dataType) && !text(config.get("sourceCode")).isBlank();
            Map<String, Object> executionParams = normalizeExecutionParams(dataset, params, rejectUnknownParams);
            List<Map<String, Object>> safeFilters = normalizeFilters(dataset, filters);
            Map<String, Object> result;
            switch (dataType)
            {
                case "SQL":
                    result = executeSql(datasetCode, config, executionParams);
                    break;
                case "API":
                    if (!text(config.get("sourceCode")).isBlank()
                            && !text(config.get("endpointCode")).isBlank()
                            && dashboardIntegrationService != null)
                    {
                        result = dashboardIntegrationService.executeApiDataset(datasetCode, dataset, config,
                                executionParams);
                    }
                    else
                    {
                        result = executeApi(datasetCode, config, executionParams);
                    }
                    break;
                case "JSON":
                    result = executeJson(datasetCode, config, executionParams);
                    break;
                case "WEBSOCKET":
                    result = executeWebsocketSource(datasetCode, config, executionParams);
                    break;
                default:
                    return errorResult(datasetCode, "INVALID_DATA", "不支持的数据集类型");
            }
            applyFilters(result, safeFilters);
            limitRows(result, config);
            if (!result.containsKey("requestId")) result.put("requestId", UUID.randomUUID().toString());
            if (!result.containsKey("fetchedAt")) result.put("fetchedAt", Instant.now().toString());
            return result;
        }
        catch (Exception ex)
        {
            return errorResult(datasetCode, classifyError(ex), safeMessage(ex));
        }
    }

    private Map<String, Object> normalizeExecutionParams(Map<String, Object> dataset, Map<String, Object> params,
            boolean rejectUnknown)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        try
        {
            JsonNode schema = objectMapper.readTree(String.valueOf(dataset.getOrDefault("paramSchemaJson", "[]")));
            if (schema != null && schema.isArray())
            {
                for (JsonNode item : schema)
                {
                    String name = item.path("name").asText("");
                    if (name.isBlank()) continue;
                    if (!SAFE_FIELD.matcher(name).matches()) throw new ServiceException("数据集参数名称不合法: " + name);
                    declared.add(name);
                    if (item.has("default") && !item.get("default").isNull())
                    {
                        result.put(name, convertParameter(item.get("default"), item.path("type").asText("STRING"), name));
                    }
                }
            }
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("数据集参数定义格式不合法");
        }
        if (params != null)
        {
            params.forEach((name, value) -> {
                if (rejectUnknown && !declared.contains(name))
                    throw new ServiceException("数据集参数未声明: " + name);
                if (declared.contains(name) && value != null
                        && (!(value instanceof String) || !((String) value).isBlank()))
                {
                    result.put(name, value);
                }
            });
        }
        if (declared.contains("projectCode") && !result.containsKey("projectCode"))
        {
            String projectCode = text(environment.getProperty("dashboard.api.labor.project-code"));
            if (!projectCode.isEmpty()) result.put("projectCode", projectCode);
        }
        try
        {
            JsonNode schema = objectMapper.readTree(String.valueOf(dataset.getOrDefault("paramSchemaJson", "[]")));
            if (schema != null && schema.isArray())
            {
                for (JsonNode item : schema)
                {
                    String name = item.path("name").asText("");
                    Object value = result.get(name);
                    if (name.isBlank() || value == null || (value instanceof String && ((String) value).isBlank()))
                    {
                        if (item.path("required").asBoolean(false))
                        {
                            throw new ServiceException("缺少数据集参数: " + name);
                        }
                        continue;
                    }
                    result.put(name, convertParameter(objectMapper.valueToTree(result.get(name)),
                            item.path("type").asText("STRING"), name));
                }
            }
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("数据集参数定义格式不合法");
        }
        return result;
    }

    private Object convertParameter(JsonNode value, String type, String name)
    {
        if (value == null || value.isNull()) return null;
        String textValue = value.isValueNode() ? value.asText() : value.toString();
        String normalized = type == null ? "STRING" : type.toUpperCase(Locale.ROOT);
        try
        {
            switch (normalized)
            {
                case "NUMBER":
                case "DECIMAL":
                    return new BigDecimal(textValue);
                case "INTEGER":
                    return new BigDecimal(textValue).longValueExact();
                case "ENUM":
                    return textValue;
                case "DATE":
                    LocalDate.parse(textValue);
                    return textValue;
                case "DATETIME":
                    try { LocalDateTime.parse(textValue); }
                    catch (java.time.format.DateTimeParseException ex) { OffsetDateTime.parse(textValue); }
                    return textValue;
                case "BOOLEAN":
                    if (!"true".equalsIgnoreCase(textValue) && !"false".equalsIgnoreCase(textValue))
                        throw new IllegalArgumentException();
                    return Boolean.parseBoolean(textValue);
                case "STRING":
                    return textValue;
                default:
                    throw new ServiceException("参数类型不支持: " + normalized);
            }
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (RuntimeException ex)
        {
            throw new ServiceException("数据集参数格式不合法: " + name);
        }
    }

    private List<Map<String, Object>> filterMaps(JsonNode node)
    {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (node == null || !node.isArray()) return filters;
        for (JsonNode item : node)
        {
            if (!item.isObject()) throw new ServiceException("过滤条件必须是对象");
            filters.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
        }
        return filters;
    }

    private List<Map<String, Object>> normalizeFilters(Map<String, Object> dataset,
            List<Map<String, Object>> filters)
    {
        if (filters == null || filters.isEmpty()) return Collections.emptyList();
        if (filters.size() > 20) throw new ServiceException("过滤条件不能超过 20 个");
        Set<String> fields = new HashSet<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        try
        {
            JsonNode fieldSchema = objectMapper.readTree(String.valueOf(dataset.getOrDefault("fieldSchemaJson", "[]")));
            if (fieldSchema != null && fieldSchema.isArray())
            {
                for (JsonNode field : fieldSchema)
                {
                    String name = field.path("name").asText("");
                    if (!name.isBlank())
                    {
                        fields.add(name);
                        fieldTypes.put(name, field.path("type").asText("string"));
                    }
                }
            }
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("数据集字段定义格式不合法");
        }
        if (fields.isEmpty()) throw new ServiceException("数据集未声明可过滤字段");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : filters)
        {
            String field = text(source.get("field"));
            String operator = text(source.getOrDefault("operator", "eq")).toLowerCase(Locale.ROOT);
            if (!SAFE_FIELD.matcher(field).matches() || !fields.contains(field))
                throw new ServiceException("过滤字段未声明: " + field);
            if (!("eq".equals(operator) || "contains".equals(operator) || "gt".equals(operator)
                    || "lt".equals(operator) || "gte".equals(operator) || "lte".equals(operator)
                    || "ne".equals(operator) || "isnull".equals(operator) || "notnull".equals(operator)))
                throw new ServiceException("过滤操作符不支持: " + operator);
            if (!"isnull".equals(operator) && !"notnull".equals(operator) && !source.containsKey("value"))
                throw new ServiceException("过滤条件缺少值: " + field);
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("field", field);
            safe.put("operator", operator);
            if (source.containsKey("value"))
            {
                String type = fieldTypes.getOrDefault(field, "string");
                safe.put("value", convertParameter(objectMapper.valueToTree(source.get("value")), type, field));
            }
            result.add(safe);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyFilters(Map<String, Object> result, List<Map<String, Object>> filters)
    {
        if (filters.isEmpty() || !"SUCCESS".equals(result.get("quality"))) return;
        Object rawRows = result.get("rows");
        if (!(rawRows instanceof List)) return;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rawRows;
        rows.removeIf(row -> filters.stream().anyMatch(filter -> !matchesFilter(row, filter)));
        if (rows.isEmpty()) result.put("quality", "NO_DATA");
    }

    private boolean matchesFilter(Map<String, Object> row, Map<String, Object> filter)
    {
        String field = String.valueOf(filter.get("field"));
        Object actual = row.get(field);
        String operator = String.valueOf(filter.get("operator"));
        if ("isnull".equals(operator)) return actual == null;
        if ("notnull".equals(operator)) return actual != null;
        Object expected = filter.get("value");
        if ("contains".equals(operator)) return actual != null && String.valueOf(actual).contains(String.valueOf(expected));
        int compared = compareValues(actual, expected);
        switch (operator)
        {
            case "eq": return compared == 0;
            case "ne": return compared != 0;
            case "gt": return compared > 0;
            case "lt": return compared < 0;
            case "gte": return compared >= 0;
            case "lte": return compared <= 0;
            default: return false;
        }
    }

    private int compareValues(Object actual, Object expected)
    {
        if (actual == null || expected == null) return actual == expected ? 0 : actual == null ? -1 : 1;
        try { return new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(String.valueOf(expected))); }
        catch (NumberFormatException ex) { return String.valueOf(actual).compareTo(String.valueOf(expected)); }
    }

    @SuppressWarnings("unchecked")
    private void limitRows(Map<String, Object> result, Map<String, Object> config)
    {
        Object rawRows = result.get("rows");
        if (!(rawRows instanceof List)) return;
        int limit = MAX_ROWS;
        try
        {
            if (config != null && config.get("rowLimit") != null)
                limit = Math.min(Math.max(1, Integer.parseInt(String.valueOf(config.get("rowLimit")))), MAX_ROWS);
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException("行数上限不合法");
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rawRows;
        if (rows.size() > limit) result.put("rows", new ArrayList<>(rows.subList(0, limit)));
    }

    private Map<String, Object> executeSql(String datasetCode, Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        String sql = text(config.get("sql"));
        validateSql(sql);
        String dataSourceCode = text(config.get("dataSourceCode"));
        DataSourceSettings settings = resolveDataSource(dataSourceCode);
        PreparedSql preparedSql = prepareSql(sql, params);
        String normalized = preparedSql.sql;
        List<SqlParameterBinding> bindings = preparedSql.bindings;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(settings.url, settings.username, settings.password);
                PreparedStatement statement = connection.prepareStatement(normalized.toString()))
        {
            statement.setQueryTimeout(integer(config.get("timeoutSeconds"), 10));
            int rowLimit = MAX_ROWS;
            if (config.get("rowLimit") != null)
            {
                try { rowLimit = Math.min(Math.max(1, Integer.parseInt(String.valueOf(config.get("rowLimit")))), MAX_ROWS); }
                catch (NumberFormatException ex) { throw new ServiceException("行数上限不合法"); }
            }
            statement.setMaxRows(rowLimit);
            for (int i = 0; i < bindings.size(); i++)
            {
                statement.setObject(i + 1, bindings.get(i).value(params));
            }
            try (ResultSet resultSet = statement.executeQuery())
            {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int count = metadata.getColumnCount();
                while (resultSet.next())
                {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= count; i++)
                    {
                        row.put(metadata.getColumnLabel(i), resultSet.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        Map<String, Object> result = baseResult(datasetCode, rows);
        result.put("dataSourceCode", dataSourceCode);
        return result;
    }

    /**
     * 将 :name 和官方示例中的 ${name} 都转换为 JDBC 绑定变量。
     * ${name} 出现在字符串字面量中时，将字面量的前后固定文本并入绑定值，
     * 因此 LIKE '%${name}%' 会安全地绑定为 %实际值%，不会把 ? 留在引号内。
     */
    private PreparedSql prepareSql(String sql, Map<String, Object> params)
    {
        StringBuilder normalized = new StringBuilder(sql.length());
        List<SqlParameterBinding> bindings = new ArrayList<>();
        int length = sql.length();
        int index = 0;
        while (index < length)
        {
            char current = sql.charAt(index);
            if (current == '\'')
            {
                int end = index + 1;
                StringBuilder literal = new StringBuilder();
                while (end < length)
                {
                    char value = sql.charAt(end);
                    if (value == '\'' && end + 1 < length && sql.charAt(end + 1) == '\'')
                    {
                        literal.append('\'');
                        end += 2;
                        continue;
                    }
                    if (value == '\'') break;
                    literal.append(value);
                    end++;
                }
                if (end >= length) throw new ServiceException("SQL 字符串引号不完整");
                Matcher matcher = DOLLAR_PARAMETER.matcher(literal);
                if (matcher.find())
                {
                    String name = matcher.group(1);
                    int placeholderStart = matcher.start();
                    int placeholderEnd = matcher.end();
                    if (matcher.find()) throw new ServiceException("同一 SQL 字符串中只能使用一个动态参数");
                    String prefix = literal.substring(0, placeholderStart);
                    String suffix = literal.substring(placeholderEnd);
                    normalized.append('?');
                    bindings.add(new SqlParameterBinding(name, prefix, suffix));
                }
                else
                {
                    normalized.append('\'').append(escapeSqlLiteral(literal.toString())).append('\'');
                }
                index = end + 1;
                continue;
            }
            if (current == '$' && index + 1 < length && sql.charAt(index + 1) == '{')
            {
                int end = sql.indexOf('}', index + 2);
                if (end < 0) throw new ServiceException("SQL 动态参数格式不合法");
                String name = sql.substring(index + 2, end);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ServiceException("SQL 动态参数名称不合法");
                normalized.append('?');
                bindings.add(new SqlParameterBinding(name, "", ""));
                index = end + 1;
                continue;
            }
            if (current == ':' && (index == 0 || sql.charAt(index - 1) != ':'))
            {
                int end = index + 1;
                if (end < length && (Character.isLetter(sql.charAt(end)) || sql.charAt(end) == '_'))
                {
                    end++;
                    while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) end++;
                    String name = sql.substring(index + 1, end);
                    normalized.append('?');
                    bindings.add(new SqlParameterBinding(name, "", ""));
                    index = end;
                    continue;
                }
            }
            normalized.append(current);
            index++;
        }
        // 在转换阶段统一检查参数是否存在，避免部分绑定后才得到模糊的 JDBC 错误。
        for (SqlParameterBinding binding : bindings)
        {
            if (params == null || !params.containsKey(binding.name))
                throw new ServiceException("缺少数据集参数: " + binding.name);
        }
        return new PreparedSql(normalized.toString(), bindings);
    }

    private String escapeSqlLiteral(String value)
    {
        return value.replace("'", "''");
    }

    private static final class PreparedSql
    {
        private final String sql;
        private final List<SqlParameterBinding> bindings;

        private PreparedSql(String sql, List<SqlParameterBinding> bindings)
        {
            this.sql = sql;
            this.bindings = bindings;
        }
    }

    private static final class SqlParameterBinding
    {
        private final String name;
        private final String prefix;
        private final String suffix;

        private SqlParameterBinding(String name, String prefix, String suffix)
        {
            this.name = name;
            this.prefix = prefix;
            this.suffix = suffix;
        }

        private Object value(Map<String, Object> params)
        {
            Object raw = params.get(name);
            if (raw == null || (prefix.isEmpty() && suffix.isEmpty())) return raw;
            return prefix + raw + suffix;
        }
    }

    private Map<String, Object> executeApi(String datasetCode, Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        String endpointCode = text(config.get("endpointCode"));
        String property = LABOR_ENDPOINT_PROPERTIES.get(endpointCode);
        String baseUrl;
        String path;
        String registeredSecret = "";
        String registeredMethod = "";
        Map<String, Object> sourceConfig = Collections.emptyMap();
        Map<String, Object> registeredSource = property == null ? dashboardMapper.selectDataSourceByCode(endpointCode) : null;
        if (property != null)
        {
            baseUrl = text(environment.getProperty("dashboard.api.labor.base-url"));
            path = text(environment.getProperty(property));
        }
        else if (registeredSource != null)
        {
            EndpointSettings endpointSettings = resolveRegisteredEndpoint(registeredSource);
            baseUrl = endpointSettings.baseUrl;
            path = endpointSettings.path;
            registeredSecret = endpointSettings.secret;
            registeredMethod = endpointSettings.method;
            sourceConfig = parseObject(text(registeredSource.get("configJson")), "HTTP 数据源配置");
        }
        else
        {
            if (!endpointCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                throw new ServiceException("API endpointCode 未登记");
            String prefix = "dashboard.api.endpoints." + endpointCode;
            baseUrl = text(environment.getProperty(prefix + ".base-url"));
            path = text(environment.getProperty(prefix + ".path"));
        }
        if (baseUrl.isEmpty() || path.isEmpty()) throw new ServiceException("API 来源未接入");
        URI endpoint = resolveApiUri(baseUrl, path, params, sourceConfig);
        int timeoutSeconds = integer(config.get("timeoutSeconds"), 15);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("X-Requested-By", "engineering-data-cockpit-dashboard");
        String projectCode = text(environment.getProperty("dashboard.api.labor.project-code"));
        if (!projectCode.isEmpty()) requestBuilder.header("X-Project-Code", projectCode);
        String token = registeredSecret.isBlank() ? text(environment.getProperty("dashboard.api.labor.token")) : registeredSecret;
        if (!token.isEmpty()) requestBuilder.header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
        String method = text(config.get("method")).toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = registeredMethod;
        if (method.isEmpty() || "GET".equals(method)) requestBuilder.GET();
        else if ("POST".equals(method)) requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
        else throw new ServiceException("API 数据集只允许 GET 或受控 POST");
        DashboardIntegrationNetwork.Target approved = validateConfiguredEndpoint(endpoint.toString(), sourceConfig);
        HttpResponse<InputStream> response = DashboardPinnedHttp.send(requestBuilder.build(), approved);
        String responseBody;
        try (InputStream body = response.body())
        {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new ServiceException("外部接口鉴权失败");
            if (response.statusCode() == 408 || response.statusCode() == 429) throw new ServiceException("外部接口请求受限");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ServiceException("外部接口返回 HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes(MAX_JSON_CHARS * 4 + 1);
            if (bytes.length > MAX_JSON_CHARS * 4) throw new ServiceException("API 返回体过大");
            responseBody = new String(bytes, StandardCharsets.UTF_8);
            if (responseBody.length() > MAX_JSON_CHARS) throw new ServiceException("API 返回体过大");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode responseConfig = objectMapper.valueToTree(config.get("response"));
        String rowsPath = text(config.get("rowsPath"));
        if (rowsPath.isEmpty()) rowsPath = text(config.get("responsePath"));
        if (rowsPath.isEmpty() && responseConfig != null && responseConfig.isObject()) rowsPath = responseConfig.path("rowsPath").asText("");
        if (rowsPath.isEmpty() && responseConfig != null && responseConfig.isObject()) rowsPath = responseConfig.path("responsePath").asText("");
        JsonNode rowsNode = atPath(root, rowsPath);
        Map<String, Object> result = baseResult(datasetCode, rowsFromNode(rowsNode));
        result.put("endpointCode", endpointCode);
        String totalPath = text(config.get("totalPath"));
        if (totalPath.isEmpty() && responseConfig != null && responseConfig.isObject()) totalPath = responseConfig.path("totalPath").asText("");
        if (!totalPath.isEmpty()) result.put("total", atPath(root, totalPath).asLong(0));
        String businessTimePath = text(config.get("businessTimePath"));
        if (businessTimePath.isEmpty() && responseConfig != null && responseConfig.isObject()) businessTimePath = responseConfig.path("businessTimePath").asText("");
        if (!businessTimePath.isEmpty()) result.put("businessTime", atPath(root, businessTimePath).asText(null));
        result.put("businessDate", businessDate(root));
        result.put("sourceStatus", "CONNECTED");
        return result;
    }

    /**
     * 解析 API 路径中的声明式动态参数。JimuReport 的 API 示例允许在路径或
     * 查询串中使用 ${name}，这里保留相同的使用习惯，但所有值都经过 URI 编码，
     * 且只来自数据集参数白名单；浏览器不能提交任意 URL 或请求头。
     */
    private URI resolveApiUri(String baseUrl, String path, Map<String, Object> params)
    {
        return resolveApiUri(baseUrl, path, params, Collections.emptyMap());
    }

    private URI resolveApiUri(String baseUrl, String path, Map<String, Object> params, Map<String, Object> sourceConfig)
    {
        validateConfiguredEndpoint(baseUrl, sourceConfig);
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String normalizedPath = path == null ? "" : path.trim();
        if (normalizedPath.isBlank()) normalizedPath = "/";
        if (!normalizedPath.startsWith("/") || normalizedPath.startsWith("//")
                || normalizedPath.contains("\\") || normalizedPath.contains("://")
                || normalizedPath.contains("\r") || normalizedPath.contains("\n"))
            throw new ServiceException("API 路径不合法");

        Set<String> templatedParameters = new HashSet<>();
        String resolvedPath = resolveUriTemplate(normalizedPath, params, templatedParameters);
        StringBuilder url = new StringBuilder(base.toString()).append(resolvedPath);
        String queryPart = "";
        int queryIndex = resolvedPath.indexOf('?');
        if (queryIndex >= 0 && queryIndex + 1 < resolvedPath.length()) queryPart = resolvedPath.substring(queryIndex + 1);
        Set<String> existingQueryNames = queryParameterNames(queryPart);
        String separator = queryIndex >= 0 ? (queryPart.isEmpty() ? "" : "&") : "?";
        if (params != null)
        {
            for (Map.Entry<String, Object> entry : params.entrySet())
            {
                String name = entry.getKey();
                Object value = entry.getValue();
                if (name == null || name.isBlank() || templatedParameters.contains(name)
                        || existingQueryNames.contains(name) || value == null || String.valueOf(value).isBlank()) continue;
                url.append(separator).append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
                url.append('=').append(java.net.URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
                separator = "&";
            }
        }
        return URI.create(url.toString());
    }

    private String resolveUriTemplate(String value, Map<String, Object> params, Set<String> used)
    {
        Matcher matcher = DOLLAR_PARAMETER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find())
        {
            String name = matcher.group(1);
            used.add(name);
            Object raw = params == null ? null : params.get(name);
            String replacement = raw == null ? "" : java.net.URLEncoder.encode(String.valueOf(raw), StandardCharsets.UTF_8);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Set<String> queryParameterNames(String query)
    {
        Set<String> names = new HashSet<>();
        if (query == null || query.isBlank()) return names;
        for (String pair : query.split("&"))
        {
            if (pair.isBlank()) continue;
            String key = pair.split("=", 2)[0];
            if (!key.isBlank()) names.add(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8));
        }
        return names;
    }

    private void validateHttpSourceConfig(Map<String, Object> config)
    {
        if(config.containsKey("environment")&&!Set.of("TEST","SANDBOX","PRODUCTION").contains(text(config.get("environment")))) throw new ServiceException("数据源环境不合法");
        if(config.containsKey("rateLimit")) {
            int limit=integer(config.get("rateLimit"),-1);
            if(limit<1||limit>1000) throw new ServiceException("来源速率必须在 1-1000 范围内");
            config.put("rateLimit",limit);
        }
        String profile;
        try { profile = DashboardIntegrationNetwork.normalizeProfile(text(config.get("networkProfile"))); }
        catch (IllegalArgumentException ex) { throw new ServiceException(ex.getMessage()); }
        config.put("networkProfile", profile);
        normalizeSourceStringList(config, "allowedHosts", false);
        normalizeSourceStringList(config, "allowedCidrs", true);
        if (config.containsKey("allowedPorts"))
        {
            Object raw = config.get("allowedPorts");
            if (!(raw instanceof List<?> list) || list.size() > 16)
                throw new ServiceException("HTTP 数据源端口白名单必须是数组");
            List<Integer> ports = new ArrayList<>();
            for (Object item : list)
            {
                try
                {
                    int port = Integer.parseInt(text(item));
                    if (port < 1 || port > 65535) throw new NumberFormatException();
                    ports.add(port);
                }
                catch (NumberFormatException ex) { throw new ServiceException("HTTP 数据源端口白名单不合法"); }
            }
            config.put("allowedPorts", ports);
        }
        String provider = text(config.getOrDefault("authProvider", "")).toUpperCase(Locale.ROOT);
        if (!provider.isBlank() && !Set.of("NO_AUTH", "BEARER", "API_KEY", "HMAC_V1", "HMAC_APPKEY_TIMESTAMP_V1").contains(provider))
            throw new ServiceException("HTTP 数据源认证方式不支持");
        if (config.containsKey("authProvider")) config.put("authProvider", provider);
        for (String key : List.of("identity", "credentialRef"))
        {
            String value = text(config.get(key));
            if (!value.isBlank() && !value.matches("[A-Za-z0-9._:@/+\\-]{1,256}"))
                throw new ServiceException("HTTP 数据源 " + key + " 不合法");
        }
        if (config.containsKey("timeoutSeconds")) config.put("timeoutSeconds", integer(config.get("timeoutSeconds"), 10));
        if (config.containsKey("concurrencyLimit"))
        {
            int value;
            try { value = Integer.parseInt(text(config.get("concurrencyLimit"))); }
            catch (NumberFormatException ex) { throw new ServiceException("HTTP 数据源并发上限必须为整数"); }
            if (value < 1 || value > 100) throw new ServiceException("HTTP 数据源并发上限超出范围");
            config.put("concurrencyLimit", value);
        }
        if (config.containsKey("timezone"))
        {
            try { ZoneId.of(text(config.get("timezone"))); }
            catch (RuntimeException ex) { throw new ServiceException("HTTP 数据源时区不合法"); }
        }
    }

    private void normalizeSourceStringList(Map<String, Object> config, String name, boolean cidr)
    {
        if (!config.containsKey(name) || config.get(name) == null) return;
        Object raw = config.get(name);
        if (!(raw instanceof List<?> list) || list.size() > 32)
            throw new ServiceException("HTTP 数据源 " + name + " 必须是数组");
        List<String> values = new ArrayList<>();
        for (Object item : list)
        {
            String value = text(item);
            boolean valid = cidr ? value.matches("[0-9A-Fa-f:.]+(?:/[0-9]{1,3})?")
                    : value.matches("(?:\\*\\.)?[A-Za-z0-9.-]{1,255}");
            if (!valid) throw new ServiceException("HTTP 数据源 " + name + " 包含不合法值");
            values.add(value);
        }
        config.put(name, values);
    }

    private void validateConfiguredEndpoint(String url)
    {
        validateConfiguredEndpoint(url, Collections.emptyMap());
    }

    private DashboardIntegrationNetwork.Target validateConfiguredEndpoint(String url, Map<String, Object> sourceConfig)
    {
        try
        {
            String profile = DashboardIntegrationNetwork.normalizeProfile(text(sourceConfig.get("networkProfile")));
            List<String> hosts = sourceConfig.get("allowedHosts") instanceof List<?> list
                    ? list.stream().map(this::text).toList() : Collections.emptyList();
            if (hosts.isEmpty() && !"PUBLIC_HTTP".equals(profile))
                hosts = Arrays.stream(environment.getProperty("dashboard.api.allowed-hosts", "").split(","))
                        .map(String::trim).filter(item -> !item.isEmpty()).toList();
            List<String> cidrs = sourceConfig.get("allowedCidrs") instanceof List<?> list
                    ? list.stream().map(this::text).toList() : Collections.emptyList();
            List<Integer> ports = sourceConfig.get("allowedPorts") instanceof List<?> list
                    ? list.stream().map(item -> Integer.parseInt(text(item))).toList() : Collections.emptyList();
            return DashboardIntegrationNetwork.validate(url, profile, hosts, cidrs, ports,
                    Boolean.parseBoolean(environment.getProperty("dashboard.integration.allow-plain-http", "false")),
                    Boolean.parseBoolean(environment.getProperty("dashboard.integration.allow-local-development-targets", "false")));
        }
        catch (IllegalArgumentException ex) { throw new ServiceException(ex.getMessage()); }
    }

    private String businessDate(JsonNode root)
    {
        for (String path : List.of("businessDate", "data.businessDate", "data.date"))
        {
            JsonNode value = atPath(root, path);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private Map<String, Object> executeJson(String datasetCode, Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        if ("URL".equalsIgnoreCase(text(config.get("mode"))))
        {
            String sourceRef = text(config.get("urlRef"));
            if (sourceRef.isEmpty()) throw new ServiceException("JSON 来源编码不能为空");
            Map<String, Object> apiConfig = new LinkedHashMap<>(config);
            apiConfig.put("endpointCode", sourceRef);
            return executeApi(datasetCode, apiConfig, params == null ? Collections.emptyMap() : params);
        }
        JsonNode root = objectMapper.valueToTree(config.get("payload"));
        JsonNode rowsNode = atPath(root, text(config.get("rowsPath")));
        return baseResult(datasetCode, rowsFromNode(rowsNode));
    }

    private Map<String, Object> executeWebsocketSource(String datasetCode, Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        String sourceType = text(config.get("sourceType")).toUpperCase(Locale.ROOT);
        if (sourceType.isEmpty())
        {
            String endpointCode = text(config.get("endpointCode"));
            Map<String, Object> source = endpointCode.isBlank() ? null : dashboardMapper.selectDataSourceByCode(endpointCode);
            sourceType = source != null && "WEBSOCKET".equalsIgnoreCase(text(source.get("sourceType")))
                    ? "WEBSOCKET" : "SQL";
        }
        switch (sourceType)
        {
            case "SQL":
                return executeSql(datasetCode, config, params);
            case "API":
                return executeApi(datasetCode, config, params);
            case "JSON":
                return executeJson(datasetCode, config, params);
            case "WEBSOCKET":
                return executeDirectWebsocket(datasetCode, config, params);
            default:
                throw new ServiceException("WebSocket 数据集 sourceType 不支持");
        }
    }

    /**
     * 从复用的上游 WebSocket 读取当前消息。正文只驻留实例内存，平台前端不会接触
     * 上游地址或凭证；连接由池按空闲时间回收，运维只接收来源和生命周期元数据。
     */
    private Map<String, Object> executeDirectWebsocket(String datasetCode, Map<String, Object> config,
            Map<String, Object> params) throws Exception
    {
        String code=text(config.getOrDefault("endpointCode",config.get("dataSourceCode")));
        Map<String,Object> source=dashboardMapper.selectDataSourceByCode(code);
        if(source==null) throw new ServiceException("WebSocket 来源未登记");
        EndpointSettings endpoint=resolveRegisteredWebsocketEndpoint(source);
        URI uri=resolveWebsocketUri(endpoint.baseUrl,endpoint.path,params);
        String subscription=websocketSubscription(config,params);
        String key=DashboardIntegrationSecurity.sha256((code+uri+subscription+endpoint.secret+jsonString(config,"{}")).getBytes(StandardCharsets.UTF_8));
        DashboardUpstreamWebSocketPool.Snapshot snapshot=upstreamWebSocketPool.read(code,key,()-> {
            Map<String,Object> current=dashboardMapper.selectDataSourceByCode(code);
            if(current==null) throw new ServiceException("WebSocket 来源已删除");
            EndpointSettings target=resolveRegisteredWebsocketEndpoint(current);
            Map<String,Object> connection=config.get("connection") instanceof Map<?,?> nested?objectMapper.convertValue(nested,new TypeReference<Map<String,Object>>(){}):Map.of();
            String authorization=target.secret.isBlank()?"":target.secret.startsWith("Bearer ")?target.secret:"Bearer "+target.secret;
            return new DashboardUpstreamWebSocketPool.Spec(resolveWebsocketUri(target.baseUrl,target.path,params),authorization,subscription,
                    integer(connection.get("heartbeatSeconds"),30),integer(connection.get("reconnectLimit"),5));
        },integer(config.get("timeoutSeconds"),15));
        JsonNode root=objectMapper.readTree(snapshot.payload());
        Map<String,Object> messageConfig=config.get("message") instanceof Map<?,?> nested?objectMapper.convertValue(nested,new TypeReference<Map<String,Object>>(){}):Map.of();
        String rowsPath=text(messageConfig.getOrDefault("rowsPath",config.get("rowsPath")));
        Map<String,Object> result=baseResult(datasetCode,rowsFromNode(atPath(root,rowsPath)));
        result.put("endpointCode",code);result.put("sourceStatus",snapshot.connected()?"CONNECTED":"RECONNECTING");
        result.put("fetchedAt",snapshot.receivedAt().toString());result.put("stale",!snapshot.connected());
        if(!snapshot.connected()) result.put("quality","STALE");
        for(String field:List.of("eventTime","businessTime")) {
            String path=text(messageConfig.get(field+"Path"));if(!path.isBlank()) result.put(field,atPath(root,path).asText(null));
        }
        return result;
    }

    private URI resolveWebsocketUri(String baseUrl, String path, Map<String, Object> params)
    {
        validateConfiguredWebSocketEndpoint(baseUrl);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path == null ? "" : path.trim();
        if (normalizedPath.isBlank()) normalizedPath = "/";
        if (!normalizedPath.startsWith("/") || normalizedPath.startsWith("//")
                || normalizedPath.contains("\\") || normalizedPath.contains("://")
                || normalizedPath.contains("\r") || normalizedPath.contains("\n"))
            throw new ServiceException("WebSocket 路径不合法");
        Set<String> templatedParameters = new HashSet<>();
        String resolvedPath = resolveUriTemplate(normalizedPath, params, templatedParameters);
        StringBuilder url = new StringBuilder(base).append(resolvedPath);
        int queryIndex = resolvedPath.indexOf('?');
        String queryPart = queryIndex >= 0 && queryIndex + 1 < resolvedPath.length()
                ? resolvedPath.substring(queryIndex + 1) : "";
        Set<String> existingQueryNames = queryParameterNames(queryPart);
        String separator = queryIndex >= 0 ? (queryPart.isEmpty() ? "" : "&") : "?";
        if (params != null)
        {
            for (Map.Entry<String, Object> entry : params.entrySet())
            {
                String name = entry.getKey();
                Object value = entry.getValue();
                if (name == null || name.isBlank() || templatedParameters.contains(name)
                        || existingQueryNames.contains(name) || value == null || String.valueOf(value).isBlank()) continue;
                url.append(separator).append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
                url.append('=').append(java.net.URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
                separator = "&";
            }
        }
        return URI.create(url.toString());
    }

    private String websocketSubscription(Map<String, Object> config, Map<String, Object> params)
    {
        Object raw = config.get("subscribe");
        if (!(raw instanceof Map)) return "";
        Map<String, Object> subscribe = objectMapper.convertValue(raw, new TypeReference<Map<String, Object>>() {});
        if (Boolean.FALSE.equals(subscribe.get("enabled"))) return "";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "subscribe");
        String channel = text(subscribe.get("channel"));
        if (!channel.isBlank()) payload.put("channel", channel);
        payload.put("params", params == null ? Collections.emptyMap() : params);
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException ex) { throw new ServiceException("WebSocket 订阅配置无法序列化"); }
    }

    private Map<String, Object> baseResult(String datasetCode, List<Map<String, Object>> rows)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetCode", datasetCode);
        result.put("rows", rows);
        result.put("quality", rows.isEmpty() ? "NO_DATA" : "SUCCESS");
        result.put("stale", false);
        result.put("fetchedAt", Instant.now().toString());
        result.put("requestId", UUID.randomUUID().toString());
        return result;
    }

    private Map<String, Object> errorResult(String datasetCode, String quality, String message)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetCode", datasetCode);
        result.put("rows", Collections.emptyList());
        result.put("quality", quality);
        result.put("stale", false);
        result.put("message", message);
        result.put("requestId", UUID.randomUUID().toString());
        result.put("fetchedAt", Instant.now().toString());
        return result;
    }

    /** 从已登记接入方生成固定范围查询，仍经过正常数据集测试和启用流程。 */
    @Transactional
    public Map<String,Object> createIntegrationDataset(Long integrationId,Map<String,Object> body,String username)
    {
        Map<String,Object> integration=dashboardIntegrationService.getIntegration(integrationId);
        String project=text(body.get("projectCode"));
        if(!project.isBlank() && !project.matches("[A-Za-z0-9._-]{1,128}"))
            throw new ServiceException("外部项目编码只能包含字母、数字、点、下划线和短横线");
        // getIntegration 返回脱敏后的 projectScope 数组，不再返回数据库 JSON 字段。
        JsonNode scopes=objectMapper.valueToTree(integration.getOrDefault("projectScope", Collections.emptyList()));
        if(scopes==null || !scopes.isArray()) throw new ServiceException("接入方外部项目范围不合法");
        if(scopes.size()>0 && project.isBlank()) throw new ServiceException("该接入方限制了外部项目范围，请选择允许的项目编码");
        if(scopes.size()>0 && !java.util.stream.StreamSupport.stream(scopes.spliterator(),false).anyMatch(item->project.equals(item.asText())))
            throw new ServiceException("外部项目编码不在接入方允许范围");
        List<Map<String,Object>> fields=new ArrayList<>();
        fields.add(Map.of("name","businessKey","title","业务主键","type","string"));
        fields.add(Map.of("name","projectCode","title","外部项目编码","type","string"));
        fields.add(Map.of("name","businessTime","title","业务时间","type","datetime"));
        StringBuilder sql=new StringBuilder("SELECT business_key AS businessKey,project_code AS projectCode,source_business_time AS businessTime");
        if(body.get("fields") instanceof List<?> selected) {
            if(selected.size()>50) throw new ServiceException("最多选择 50 个标准字段");
            Set<String> names=new HashSet<>(Set.of("businessKey","projectCode","businessTime"));
            for(Object item:selected) {
                if(!(item instanceof Map<?,?> field)) throw new ServiceException("字段声明不合法");
                String name=text(field.get("name")),path=text(field.get("sourcePath")),type=text(field.get("type"));
                if(!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")||!names.add(name)||!path.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw new ServiceException("字段名或路径不合法");
                if(!Set.of("string","number","integer","datetime","boolean").contains(type)) throw new ServiceException("字段类型不支持");
                String expression="JSON_UNQUOTE(JSON_EXTRACT(data_json,'$."+path+"'))";
                if("number".equals(type)||"integer".equals(type)) expression="CAST("+expression+" AS DECIMAL(30,6))";
                sql.append(",").append(expression).append(" AS `").append(name).append("`");
                fields.add(Map.of("name",name,"title",name,"type",type));
            }
        }
        sql.append(" FROM dashboard_integration_record WHERE integration_id=").append(integrationId);
        if(!project.isBlank()) sql.append(" AND project_code='").append(project).append("'");
        sql.append(" AND status='ACTIVE' ORDER BY source_business_time DESC,record_id DESC");
        Map<String,Object> config=new LinkedHashMap<>();
        config.put("dataSourceCode","master"); config.put("sql",sql.toString()); config.put("rowLimit",1000);
        config.put("integrationCode",integration.get("integrationCode")); config.put("sourceType","SQL");
        Map<String,Object> dataset=new LinkedHashMap<>();
        dataset.put("datasetCode",body.get("datasetCode"));dataset.put("datasetName",body.get("datasetName"));
        dataset.put("groupCode",body.get("groupCode"));
        dataset.put("dataType",Boolean.TRUE.equals(body.get("realtime"))?"WEBSOCKET":"SQL");
        dataset.put("configJson",jsonString(config,"{}"));dataset.put("fieldSchemaJson",jsonString(fields,"[]"));
        dataset.put("paramSchemaJson","[]");dataset.put("status","DRAFT");dataset.put("refreshSeconds",30);
        return createDataset(dataset,username);
    }

    public long integrationDatasetVersion(String datasetCode)
    {
        Map<String,Object> dataset=dashboardMapper.selectDatasetByCode(datasetCode);
        if(dataset==null) return -1;
        Map<String,Object> config=parseObject(text(dataset.get("configJson")),"数据集配置");
        String code=text(config.get("integrationCode"));
        return code.isBlank()?-1:dashboardIntegrationService.integrationVersion(code);
    }

    private Map<String, Object> normalizeDataset(Map<String, Object> body, String username, boolean partial)
    {
        Map<String, Object> dataset = new LinkedHashMap<>();
        if (!partial || body.containsKey("datasetCode"))
        {
            String code = required(body, "datasetCode");
            if (!code.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}"))
            {
                throw new ServiceException("数据集编码格式不合法");
            }
            dataset.put("datasetCode", code);
        }
        if (!partial || body.containsKey("datasetName")) dataset.put("datasetName", required(body, "datasetName"));
        if (!partial || body.containsKey("groupCode"))
        {
            String groupCode = text(body.get("groupCode")).trim();
            if (!groupCode.isBlank())
            {
                if (!groupCode.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}"))
                    throw new ServiceException("数据集分组编码不合法");
                Map<String, Object> group = dashboardMapper.selectDatasetGroupByCode(groupCode);
                if (group == null)
                    throw new ServiceException("数据集分组不存在: " + groupCode);
                dataFolders.validateTarget("dataset", group.get("groupId"));
            }
            dataset.put("groupCode", groupCode);
        }
        if (!partial || body.containsKey("dataType"))
        {
            String dataType = required(body, "dataType").toUpperCase(Locale.ROOT);
            if (!DATASET_TYPES.contains(dataType)) throw new ServiceException("数据集类型不支持");
            dataset.put("dataType", dataType);
        }
        if (!partial || body.containsKey("configJson"))
        {
            String configJson = jsonString(body.get("configJson"), "{}");
            if (configJson.length() > MAX_JSON_CHARS) throw new ServiceException("数据集配置过大");
            Map<String, Object> config = parseObject(configJson, "数据集配置");
            validateDatasetConfig(text(dataset.get("dataType")).toUpperCase(Locale.ROOT), config);
            String serializedConfig = configJson.toLowerCase(Locale.ROOT);
            if (serializedConfig.contains("<script") || serializedConfig.contains("javascript:")
                    || serializedConfig.contains("function(") || serializedConfig.contains("=>"))
                throw new ServiceException("数据集配置包含不允许的脚本内容");
            dataset.put("configJson", configJson);
        }
        if (!partial || body.containsKey("fieldSchemaJson"))
        {
            String fieldSchema = jsonArrayString(body.get("fieldSchemaJson"), "字段定义");
            validateFieldSchema(fieldSchema);
            dataset.put("fieldSchemaJson", fieldSchema);
        }
        if (!partial || body.containsKey("paramSchemaJson"))
        {
            String paramSchema = jsonArrayString(body.get("paramSchemaJson"), "参数定义");
            validateParamSchema(paramSchema);
            dataset.put("paramSchemaJson", paramSchema);
        }
        if (!partial || body.containsKey("status"))
        {
            String status = String.valueOf(body.getOrDefault("status", "DRAFT")).toUpperCase(Locale.ROOT);
            if (!("DRAFT".equals(status) || "ACTIVE".equals(status) || "DISABLED".equals(status))) throw new ServiceException("数据集状态不合法");
            dataset.put("status", status);
            String sourceCode = text(parseObject(text(dataset.get("configJson")), "数据集配置").get("sourceCode"));
            String endpointCode = text(parseObject(text(dataset.get("configJson")), "数据集配置").get("endpointCode"));
            if (!sourceCode.isBlank() && !endpointCode.isBlank() && dashboardIntegrationService != null
                    && "ACTIVE".equals(status))
                dashboardIntegrationService.validateDatasetReference(sourceCode, endpointCode, true);
        }
        if (!partial || body.containsKey("timeoutSeconds")) dataset.put("timeoutSeconds", integer(body.get("timeoutSeconds"), 10));
        if (!partial || body.containsKey("refreshSeconds")) dataset.put("refreshSeconds", integer(body.get("refreshSeconds"), 30));
        if (!partial || body.containsKey("remark")) dataset.put("remark", body.get("remark"));
        if (!partial) dataset.put("createBy", username);
        dataset.put("updateBy", username);
        return dataset;
    }

    private void validateDatasetConfig(String dataType, Map<String, Object> config)
    {
        if (config == null) throw new ServiceException("数据集配置不能为空");
        if ("API".equals(dataType))
        {
            String sourceCode = text(config.get("sourceCode"));
            String endpointCode = text(config.get("endpointCode"));
            if (endpointCode.isBlank() || !endpointCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                throw new ServiceException("API 数据集必须引用合法 endpointCode");
            if (!sourceCode.isBlank())
            {
                if (!sourceCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                    throw new ServiceException("API 数据集必须引用合法 sourceCode");
                if (dashboardIntegrationService != null)
                    dashboardIntegrationService.validateDatasetReference(sourceCode, endpointCode, false);
                if (config.containsKey("method"))
                    throw new ServiceException("新版 API 数据集不能覆盖 endpoint 方法");
                Set<String> allowed = Set.of("sourceCode", "endpointCode", "response", "rowLimit",
                        "mediaProjections");
                for (String key : config.keySet())
                    if (!allowed.contains(key))
                        throw new ServiceException("新版 API 数据集配置属性不支持: " + key);
                if (config.containsKey("rowLimit"))
                {
                    int rowLimit = integer(config.get("rowLimit"), 0);
                    if (rowLimit < 1 || rowLimit > MAX_ROWS)
                        throw new ServiceException("新版 API 数据集行上限必须在 1-" + MAX_ROWS + " 之间");
                }
                validateApiMediaProjections(sourceCode, config.get("mediaProjections"), false);
            }
            String method = text(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
            if (!("GET".equals(method) || "POST".equals(method))) throw new ServiceException("API 数据集只允许 GET 或受控 POST");
            for (String key : List.of("rowsPath", "responsePath", "totalPath", "businessTimePath")) validateDataPath(config.get(key), "API " + key);
            Object response = config.get("response");
            if (response != null && !(response instanceof Map)) throw new ServiceException("API response 配置必须是对象");
            if (response instanceof Map)
            {
                if (!sourceCode.isBlank())
                {
                    Set<String> allowed = Set.of("rowsPath", "totalPath", "businessTimePath",
                            "requiredPaths", "rowErrorMode");
                    for (Object key : ((Map<?, ?>) response).keySet())
                        if (!allowed.contains(String.valueOf(key)))
                            throw new ServiceException("新版 API response 配置属性不支持: " + key);
                    Object requiredPaths = ((Map<?, ?>) response).get("requiredPaths");
                    if (requiredPaths != null)
                    {
                        if (!(requiredPaths instanceof List<?> paths) || paths.size() > 32)
                            throw new ServiceException("API response.requiredPaths 必须是数组");
                        for (Object path : paths) validateDataPath(path, "API response.requiredPaths");
                    }
                    String rowErrorMode = text(((Map<?, ?>) response).get("rowErrorMode"));
                    if (!rowErrorMode.isBlank() && !Set.of("PARTIAL", "REJECT").contains(
                            rowErrorMode.toUpperCase(Locale.ROOT)))
                        throw new ServiceException("API response.rowErrorMode 不支持");
                }
                for (String key : List.of("rowsPath", "responsePath", "totalPath", "businessTimePath"))
                    validateDataPath(((Map<?, ?>) response).get(key), "API response." + key);
            }
            return;
        }
        if ("JSON".equals(dataType))
        {
            String mode = text(config.getOrDefault("mode", "STATIC")).toUpperCase(Locale.ROOT);
            if (!("STATIC".equals(mode) || "URL".equals(mode))) throw new ServiceException("JSON 数据集模式不支持");
            if ("URL".equals(mode))
            {
                String sourceRef = text(config.getOrDefault("urlRef", config.get("endpointCode")));
                if (sourceRef.isBlank() || !sourceRef.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                    throw new ServiceException("JSON 数据集必须引用合法来源编码");
            }
            validateDataPath(config.get("rowsPath"), "JSON rowsPath");
            return;
        }
        if (!"WEBSOCKET".equals(dataType)) return;
        String sourceType = text(config.get("sourceType")).toUpperCase(Locale.ROOT);
        if (sourceType.isBlank()) sourceType = "WEBSOCKET";
        if (!Set.of("WEBSOCKET", "SQL", "API", "JSON", "POLL_BRIDGE").contains(sourceType))
            throw new ServiceException("WebSocket 数据集来源模式不支持: " + sourceType);
        if ("WEBSOCKET".equals(sourceType))
        {
            String endpointCode = text(config.get("endpointCode"));
            if (endpointCode.isBlank()) endpointCode = text(config.get("dataSourceCode"));
            if (!endpointCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                throw new ServiceException("WebSocket 数据集必须引用已登记来源编码");
            Map<String, Object> source = dashboardMapper.selectDataSourceByCode(endpointCode);
            if (source == null || !"WEBSOCKET".equalsIgnoreCase(text(source.get("sourceType"))))
                throw new ServiceException("WebSocket 数据集来源类型不匹配: " + endpointCode);
            Object subscribeValue = config.get("subscribe");
            if (subscribeValue != null && !(subscribeValue instanceof Map))
                throw new ServiceException("WebSocket 订阅配置必须是对象");
            if (subscribeValue instanceof Map)
            {
                Map<?, ?> subscribe = (Map<?, ?>) subscribeValue;
                String channel = text(subscribe.get("channel"));
                if (channel.length() > 128 || (!channel.isBlank() && !SAFE_FIELD.matcher(channel).matches()))
                    throw new ServiceException("WebSocket 订阅频道不合法");
                Object enabled = subscribe.get("enabled");
                if (enabled != null && !(enabled instanceof Boolean)) throw new ServiceException("WebSocket 订阅开关不合法");
                subscribe.keySet().forEach(key -> {
                    if (!("enabled".equals(String.valueOf(key)) || "channel".equals(String.valueOf(key))))
                        throw new ServiceException("WebSocket 订阅属性不支持: " + key);
                });
            }
            Object messageValue = config.get("message");
            if (messageValue != null && !(messageValue instanceof Map))
                throw new ServiceException("WebSocket 消息配置必须是对象");
            if (messageValue instanceof Map)
            {
                Map<?, ?> message = (Map<?, ?>) messageValue;
                message.keySet().forEach(key -> {
                    if (!("rowsPath".equals(String.valueOf(key)) || "eventTimePath".equals(String.valueOf(key))
                            || "businessTimePath".equals(String.valueOf(key))))
                        throw new ServiceException("WebSocket 消息属性不支持: " + key);
                });
                for (String key : List.of("rowsPath", "eventTimePath", "businessTimePath"))
                {
                    String path = text(message.get(key));
                    if (path.length() > 128 || (!path.isBlank() && !path.matches("[A-Za-z0-9_.-]+")))
                        throw new ServiceException("WebSocket 消息路径不合法: " + key);
                }
            }
        }
    }

    private void validateApiMediaProjections(String sourceCode, Object value, boolean requireActive)
    {
        if (value == null) return;
        if (!(value instanceof List<?> projections) || projections.size() > 4)
            throw new ServiceException("媒体引用投影必须是最多 4 项的数组");
        Set<String> outputFields = new HashSet<>();
        for (Object raw : projections)
        {
            if (!(raw instanceof Map<?, ?> projection))
                throw new ServiceException("媒体引用投影项必须是对象");
            Set<String> allowed = Set.of("outputField", "endpointCode", "businessKeyPath",
                    "formFieldMappings");
            for (Object key : projection.keySet())
                if (!allowed.contains(String.valueOf(key)))
                    throw new ServiceException("媒体引用投影属性不支持: " + key);
            String outputField = text(projection.get("outputField"));
            if (!SAFE_FIELD.matcher(outputField).matches() || !outputFields.add(outputField))
                throw new ServiceException("媒体引用输出字段不合法或重复");
            String mediaEndpoint = text(projection.get("endpointCode"));
            if (!mediaEndpoint.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
                throw new ServiceException("媒体 endpointCode 不合法");
            validateDataPath(projection.get("businessKeyPath"), "媒体业务主键路径");
            if (text(projection.get("businessKeyPath")).isBlank())
                throw new ServiceException("媒体业务主键路径不能为空");
            Object mappingsValue = projection.get("formFieldMappings");
            if (!(mappingsValue instanceof Map<?, ?> mappings) || mappings.isEmpty() || mappings.size() > 16)
                throw new ServiceException("媒体表单字段映射必须包含 1-16 项");
            for (Map.Entry<?, ?> entry : mappings.entrySet())
            {
                String formField = text(entry.getKey());
                if (!formField.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                    throw new ServiceException("媒体表单字段名不合法");
                validateDataPath(entry.getValue(), "媒体表单来源路径");
                if (text(entry.getValue()).isBlank())
                    throw new ServiceException("媒体表单来源路径不能为空");
            }
            if (dashboardIntegrationService != null)
                dashboardIntegrationService.validateMediaEndpointReference(sourceCode, mediaEndpoint, requireActive);
        }
    }

    private void validateDataPath(Object value, String label)
    {
        String path = text(value);
        if (path.length() > 128 || (!path.isBlank() && !path.matches("[A-Za-z0-9_.-]+")))
            throw new ServiceException(label + " 不合法");
    }

    private String jsonArrayString(Object value, String label)
    {
        String json = jsonString(value, "[]");
        try
        {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) throw new ServiceException(label + "必须是 JSON 数组");
            return json;
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException(label + "格式不合法");
        }
    }

    private void validateFieldSchema(String json)
    {
        try
        {
            JsonNode fields = objectMapper.readTree(json);
            if (!fields.isArray() || fields.size() > 100) throw new ServiceException("字段定义数量不能超过 100 个");
            Set<String> names = new HashSet<>();
            for (JsonNode field : fields)
            {
                if (!field.isObject()) throw new ServiceException("字段定义必须是对象");
                String name = field.path("name").asText("");
                if (!SAFE_FIELD.matcher(name).matches() || !names.add(name)) throw new ServiceException("字段名不合法或重复: " + name);
                String type = field.path("type").asText("string").toLowerCase(Locale.ROOT);
                if (!("string".equals(type) || "number".equals(type) || "decimal".equals(type)
                        || "integer".equals(type) || "enum".equals(type) || "json".equals(type)
                        || "datetime".equals(type) || "date".equals(type) || "boolean".equals(type)))
                    throw new ServiceException("字段类型不支持: " + type);
                String aggregate = field.path("aggregate").asText("none").toLowerCase(Locale.ROOT);
                if (!("none".equals(aggregate) || "sum".equals(aggregate) || "avg".equals(aggregate)
                        || "max".equals(aggregate) || "min".equals(aggregate) || "count".equals(aggregate)))
                    throw new ServiceException("字段统计方式不支持: " + aggregate);
                validatePlainText(field, "title", 128);
                validatePlainText(field, "dictCode", 128);
                validatePlainText(field, "format", 128);
                String dictCode = field.path("dictCode").asText("");
                if (!dictCode.isBlank() && !dictCode.matches("[A-Za-z0-9_.-]{2,64}"))
                    throw new ServiceException("字典编码不合法: " + dictCode);
                Set<String> allowed = Set.of("name", "title", "type", "show", "sortable", "aggregate", "dictCode", "mask", "format", "sourcePath", "path", "required", "default", "enum", "values", "scale");
                field.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new ServiceException("字段属性不支持: " + key); });
            }
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("字段定义格式不合法");
        }
    }

    private void validateParamSchema(String json)
    {
        try
        {
            JsonNode params = objectMapper.readTree(json);
            if (!params.isArray() || params.size() > 50) throw new ServiceException("参数定义数量不能超过 50 个");
            Set<String> names = new HashSet<>();
            for (JsonNode param : params)
            {
                if (!param.isObject()) throw new ServiceException("参数定义必须是对象");
                String name = param.path("name").asText("");
                if (!SAFE_FIELD.matcher(name).matches() || !names.add(name)) throw new ServiceException("参数名不合法或重复: " + name);
                String type = param.path("type").asText("STRING").toUpperCase(Locale.ROOT);
                if (!("STRING".equals(type) || "NUMBER".equals(type) || "DECIMAL".equals(type)
                        || "INTEGER".equals(type) || "ENUM".equals(type) || "DATE".equals(type)
                        || "DATETIME".equals(type) || "BOOLEAN".equals(type)))
                    throw new ServiceException("参数类型不支持: " + type);
                validatePlainText(param, "title", 128);
                String location = param.path("in").asText(param.path("location").asText("query")).toLowerCase(Locale.ROOT);
                if (!("path".equals(location) || "query".equals(location) || "header".equals(location)
                        || "json".equals(location) || "form".equals(location)))
                    throw new ServiceException("参数位置不支持: " + location);
                Set<String> allowed = Set.of("name", "title", "type", "required", "default", "min", "max", "in", "location", "header", "enum", "values");
                param.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new ServiceException("参数属性不支持: " + key); });
            }
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("参数定义格式不合法");
        }
    }

    private void validatePlainText(JsonNode object, String field, int maxLength)
    {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isValueNode() || value.asText("").length() > maxLength)
            throw new ServiceException("配置字段过长或不是纯文本: " + field);
        String text = value.asText("").toLowerCase(Locale.ROOT);
        if (text.contains("javascript:") || text.contains("<script") || text.contains("{{") || text.contains("}}"))
            throw new ServiceException("配置字段包含不允许的脚本或模板: " + field);
    }

    private void validateSchema(String schemaJson)
    {
        if (schemaJson == null || schemaJson.length() > MAX_JSON_CHARS) throw new ServiceException("页面配置过大");
        JsonNode root;
        try
        {
            root = objectMapper.readTree(schemaJson);
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException("页面 JSON 不合法");
        }
        if (root == null || !root.isObject() || !root.path("widgets").isArray()) throw new ServiceException("页面必须包含 widgets 数组");
        int canvasWidth = root.path("canvas").path("width").asInt(1920);
        int canvasHeight = root.path("canvas").path("height").asInt(1080);
        if (canvasWidth < 320 || canvasWidth > 10000 || canvasHeight < 240 || canvasHeight > 10000)
            throw new ServiceException("画布尺寸必须在 320-10000 × 240-10000 范围内");
        validateCanvasPalette(root.path("canvas"));
        validateWatermark(root.path("canvas").path("watermark"));
        validateRefresh(root.path("refresh"));
        String backgroundImage = root.path("canvas").path("background").path("imageRef").asText("");
        if (!backgroundImage.isBlank() && !(backgroundImage.startsWith("/profile/")
                || backgroundImage.startsWith("/dashboard/assets/")))
            throw new ServiceException("背景图片资源不在平台白名单");
        if (root.path("widgets").size() > 200) throw new ServiceException("页面组件数量不能超过 200");
        Set<String> widgetIds = new HashSet<>();
        for (JsonNode widget : root.path("widgets"))
        {
            String type = widget.path("type").asText("");
            if (!COMPONENT_TYPES.contains(type)) throw new ServiceException("组件类型不在白名单: " + type);
            String id = widget.path("id").asText("");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new ServiceException("组件 ID 不合法");
            if (!widgetIds.add(id)) throw new ServiceException("组件 ID 不能重复: " + id);
            JsonNode layout = widget.path("layout");
            if (!layout.isObject()) throw new ServiceException("组件必须包含布局配置");
            if (!layout.path("x").isNumber() || !layout.path("y").isNumber()
                    || !layout.path("w").isNumber() || !layout.path("h").isNumber())
                throw new ServiceException("组件布局坐标和尺寸必须是数字");
            double x = layout.path("x").asDouble(-1);
            double y = layout.path("y").asDouble(-1);
            double width = layout.path("w").asDouble(-1);
            double height = layout.path("h").asDouble(-1);
            double rotate = layout.path("rotate").asDouble(0);
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > canvasWidth || y + height > canvasHeight)
                throw new ServiceException("组件布局超出画布边界");
            if (rotate < -360 || rotate > 360) throw new ServiceException("组件旋转角度不合法");
            JsonNode binding = widget.get("binding");
            Map<String, Object> boundDataset = null;
            String datasetCode = binding == null ? "" : binding.path("datasetCode").asText("");
            String sourceType = binding == null ? "" : binding.path("sourceType").asText("").toUpperCase(Locale.ROOT);
            if (!sourceType.isBlank() && !Set.of("DATASET", "STATIC").contains(sourceType))
                throw new ServiceException("组件数据来源类型不在白名单");
            if ("STATIC".equals(sourceType) && !datasetCode.isBlank())
                throw new ServiceException("静态数据组件不能同时绑定数据集");
            JsonNode staticRows = binding == null ? null : binding.get("staticRows");
            if (staticRows != null && !staticRows.isNull()) validateStaticRows(staticRows);
            if (binding != null && !datasetCode.isBlank())
            {
                boundDataset = dashboardMapper.selectDatasetByCode(datasetCode);
                if (boundDataset == null) throw new ServiceException("数据集不存在: " + datasetCode);
            }
            JsonNode fieldMap = binding == null ? null : binding.get("fieldMap");
            validateFieldMap(fieldMap, boundDataset == null ? null : declaredDatasetFields(boundDataset));
            validateComponentFieldMap(type, fieldMap, boundDataset == null ? null : declaredDatasetFields(boundDataset));
            JsonNode displayFields = binding == null ? null : binding.get("displayFields");
            if (displayFields != null && !displayFields.isArray()) throw new ServiceException("组件展示字段必须是数组");
            if (displayFields != null && displayFields.isArray() && displayFields.size() > 100)
                throw new ServiceException("组件展示字段不能超过 100 个");
            if (displayFields != null && displayFields.isArray() && boundDataset != null)
            {
                Set<String> declaredFields = declaredDatasetFields(boundDataset);
                Set<String> selectedFields = new HashSet<>();
                for (JsonNode field : displayFields)
                {
                    String name = field.asText("");
                    if (!SAFE_FIELD.matcher(name).matches() || !selectedFields.add(name) || !declaredFields.contains(name))
                        throw new ServiceException("组件展示字段未声明或重复: " + name);
                }
            }
            if (binding != null && binding.has("parameters") && !binding.path("parameters").isObject())
                throw new ServiceException("组件参数必须是对象");
            if (binding != null && binding.has("parameters") && binding.path("parameters").isObject() && boundDataset != null)
            {
                Set<String> declaredParams = parseParamSchema(boundDataset.get("paramSchemaJson")).stream()
                        .map(item -> text(item.get("name"))).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
                binding.path("parameters").fieldNames().forEachRemaining(name -> {
                    if (!declaredParams.contains(name)) throw new ServiceException("组件参数未声明: " + name);
                });
            }
            if (binding != null && binding.has("rowLimit"))
            {
                int rowLimit = binding.path("rowLimit").asInt(-1);
                if (rowLimit < 1 || rowLimit > MAX_ROWS) throw new ServiceException("组件展示条数必须在 1-1000 范围内");
            }
            if (binding != null && binding.has("refreshSeconds"))
            {
                int refreshSeconds = binding.path("refreshSeconds").asInt(-1);
                if (refreshSeconds < 0 || refreshSeconds > 3600) throw new ServiceException("组件刷新间隔必须在 0-3600 秒范围内");
            }
            if (binding != null && binding.has("filters"))
            {
                JsonNode filterNode = binding.path("filters");
                if (!filterNode.isArray()) throw new ServiceException("组件过滤条件必须是数组");
                Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(binding.path("datasetCode").asText(""));
                if (dataset != null)
                {
                    normalizeFilters(dataset, filterMaps(filterNode));
                }
            }
            JsonNode interaction = widget.get("interaction");
            if (interaction != null && interaction.isObject()) validateInteraction(interaction);
            JsonNode style = widget.get("style");
            if (style != null && style.has("optionJson")) validateOptionJson(style.get("optionJson"), type);
            if (style != null && style.has("chartConfig")) validateChartConfig(style.get("chartConfig"), type);
            if (style != null && style.isObject()) validateWidgetStyle(style);
            if (style != null && style.isObject()) validateWidgetResources(style, type);
            if (style != null && style.has("imageRef"))
            {
                String imageRef = style.path("imageRef").asText("");
                if (!imageRef.isBlank() && !(imageRef.startsWith("/profile/") || imageRef.startsWith("/dashboard/assets/")))
                    throw new ServiceException("图片资源不在平台白名单");
            }
            if ("icon".equals(type) && style != null && style.has("iconName")
                    && !ICON_NAMES.contains(style.path("iconName").asText("star")))
                throw new ServiceException("图标不在平台白名单");
            String serialized = widget.toString().toLowerCase(Locale.ROOT);
            if (!"custom-html".equals(type)
                    && (serialized.contains("javascript:") || serialized.contains("<script")
                    || serialized.contains("function(") || serialized.contains("=>")))
                throw new ServiceException("页面配置包含不允许的脚本内容");
            if ("custom-html".equals(type) && style != null && style.isObject())
                validateCustomHtml(style.path("htmlContent"));
        }
        validatePageFilters(root.path("filters"), widgetIds);
        validateFilterForms(root.path("widgets"), root.path("filters"));
        validateTabs(root.path("widgets"), widgetIds);
    }

    /**
     * 校验组件字段映射。valueFields 是折线、面积、柱状和雷达图使用的多系列字段数组，
     * 其余映射仍保持单字段字符串，避免非标量配置绕过数据集字段白名单。
     */
    private void validateFieldMap(JsonNode fieldMap, Set<String> declaredFields)
    {
        if (fieldMap == null || fieldMap.isNull()) return;
        if (!fieldMap.isObject()) throw new ServiceException("字段映射必须是对象");
        fieldMap.fields().forEachRemaining(entry -> {
            String role = entry.getKey();
            JsonNode valueNode = entry.getValue();
            if (!SAFE_FIELD.matcher(role).matches())
                throw new ServiceException("组件字段映射角色不合法: " + role);
            if ("valueFields".equals(role))
            {
                if (!valueNode.isArray()) throw new ServiceException("多系列数值字段必须是数组");
                if (valueNode.size() > 12) throw new ServiceException("多系列数值字段不能超过 12 个");
                Set<String> selectedFields = new HashSet<>();
                for (JsonNode fieldNode : valueNode)
                {
                    if (!fieldNode.isTextual()) throw new ServiceException("多系列数值字段必须是字符串");
                    String field = fieldNode.asText("");
                    if (!SAFE_FIELD.matcher(field).matches() || !selectedFields.add(field))
                        throw new ServiceException("多系列数值字段不合法或重复: " + field);
                    if (declaredFields != null && !declaredFields.contains(field))
                        throw new ServiceException("组件字段映射未声明: " + field);
                }
                return;
            }
            if (!valueNode.isTextual())
                throw new ServiceException("组件字段映射必须是字符串: " + role);
            String field = valueNode.asText("");
            if (!field.isEmpty() && !SAFE_FIELD.matcher(field).matches())
                throw new ServiceException("组件字段映射不合法: " + field);
            if (!field.isEmpty() && declaredFields != null && !declaredFields.contains(field))
                throw new ServiceException("组件字段映射未声明: " + field);
        });
    }

    /** 组件专用字段角色白名单，避免将任意原始字段名写入公共渲染协议。 */
    private void validateComponentFieldMap(String componentType, JsonNode fieldMap, Set<String> declaredFields)
    {
        if (fieldMap == null || !fieldMap.isObject()) return;
        Set<String> allowed = null;
        if ("statistics".equals(componentType))
            allowed = Set.of("label", "value", "suffix", "compareValue", "compareLabel", "compareState");
        else if ("gantt-chart".equals(componentType))
            allowed = Set.of("id", "task", "start", "end", "actualStart", "actualEnd", "progress", "dependencies", "status");
        else if ("access-list".equals(componentType))
            allowed = Set.of("avatar", "title", "subtitle", "company", "status", "time");
        else if (Set.of("map-chart", "map-bar", "map-heat", "map-ranking").contains(componentType))
            allowed = Set.of("label", "value", "longitude", "latitude", "name", "category", "lng", "lat", "lon", "x", "y");
        else if (Set.of("map-flow", "map-timeline").contains(componentType))
            allowed = Set.of("fromName", "toName", "fromLongitude", "fromLatitude", "toLongitude", "toLatitude", "value", "group");
        if (allowed == null) return;
        java.util.Iterator<String> roles = fieldMap.fieldNames();
        while (roles.hasNext())
        {
            String role = roles.next();
            if ("valueFields".equals(role)) continue;
            if (!allowed.contains(role)) throw new ServiceException("组件字段映射角色不支持: " + role);
            JsonNode value = fieldMap.get(role);
            if (value == null || value.isNull() || value.asText("").isBlank()) continue;
            if (!value.isTextual() || !SAFE_FIELD.matcher(value.asText("")).matches())
                throw new ServiceException("组件字段映射不合法: " + role);
            if (declaredFields != null && !declaredFields.contains(value.asText("")))
                throw new ServiceException("组件字段映射未声明: " + value.asText(""));
        }
    }

    private void validateWatermark(JsonNode watermark)
    {
        if (watermark == null || watermark.isMissingNode() || watermark.isNull()) return;
        if (!watermark.isObject()) throw new ServiceException("水印配置必须是对象");
        int fontSize = watermark.path("fontSize").asInt(22);
        int rotate = watermark.path("rotate").asInt(-20);
        double opacity = watermark.path("opacity").asDouble(.12);
        if (fontSize < 10 || fontSize > 96 || rotate < -180 || rotate > 180 || opacity < 0 || opacity > 1)
            throw new ServiceException("水印字号、旋转角度或透明度不合法");
        String text = watermark.path("text").asText("");
        if (text.length() > 128 || text.toLowerCase(Locale.ROOT).contains("<script")
                || text.toLowerCase(Locale.ROOT).contains("javascript:"))
            throw new ServiceException("水印文字不合法");
    }

    private void validateRefresh(JsonNode refresh)
    {
        if (refresh == null || refresh.isMissingNode() || refresh.isNull()) return;
        if (!refresh.isObject()) throw new ServiceException("刷新配置必须是对象");
        String mode = refresh.path("mode").asText("interval").toLowerCase(Locale.ROOT);
        if (!("interval".equals(mode) || "daily".equals(mode)))
            throw new ServiceException("刷新策略不在白名单");
        JsonNode secondsNode = refresh.get("seconds");
        if (secondsNode != null && !secondsNode.isIntegralNumber())
            throw new ServiceException("刷新间隔必须是整数");
        int seconds = refresh.path("seconds").asInt(30);
        if (seconds < 5 || seconds > 3600)
            throw new ServiceException("刷新间隔必须在 5-3600 秒范围内");
        String at = refresh.path("at").asText("08:00");
        if (!at.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))
            throw new ServiceException("每日刷新时间必须为 HH:mm");
    }

    private void validateCanvasPalette(JsonNode canvas)
    {
        if (canvas == null || canvas.isMissingNode() || canvas.isNull()) return;
        String scaleMode = canvas.path("scaleMode").asText("contain");
        if (!Set.of("contain", "stretch").contains(scaleMode))
            throw new ServiceException("运行画面适配方式不在白名单");
        JsonNode fullscreenScaleMode = canvas.get("fullscreenScaleMode");
        if (fullscreenScaleMode != null && (!fullscreenScaleMode.isTextual()
                || !Set.of("contain", "cover", "stretch").contains(fullscreenScaleMode.asText())))
            throw new ServiceException("全屏画面适配方式只支持 contain、cover 或 stretch");
        String palette = canvas.path("palette").asText("");
        if (!palette.isBlank() && !PALETTE_NAMES.contains(palette))
            throw new ServiceException("系统配色不在白名单");
        JsonNode colors = canvas.get("paletteColors");
        if (colors == null || colors.isNull()) return;
        if (!colors.isArray() || colors.size() == 0 || colors.size() > 12)
            throw new ServiceException("系统配色数量不合法");
        for (JsonNode color : colors)
        {
            String text = color.asText("");
            if (!color.isTextual() || text.length() > 64 || !text.matches("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 .,%-]+\\)"))
                throw new ServiceException("系统配色格式不合法");
        }
    }

    private void validateTabs(JsonNode widgets, Set<String> widgetIds)
    {
        if (widgets == null || !widgets.isArray()) return;
        for (JsonNode widget : widgets)
        {
            JsonNode tabs = widget.path("style").path("tabs");
            if (tabs.isMissingNode() || tabs.isNull()) continue;
            if (!tabs.isArray() || tabs.size() == 0 || tabs.size() > 20)
                throw new ServiceException("选项卡配置必须包含 1-20 个选项");
            for (JsonNode tab : tabs)
            {
                if (!tab.isObject()) throw new ServiceException("选项卡项必须是对象");
                String label = tab.path("label").asText("");
                String content = tab.path("content").asText("");
                if (label.length() > 128 || content.length() > 2000
                        || content.toLowerCase(Locale.ROOT).contains("<script")
                        || content.toLowerCase(Locale.ROOT).contains("javascript:"))
                    throw new ServiceException("选项卡文字不合法");
                JsonNode ids = tab.path("widgetIds");
                if (!ids.isMissingNode())
                {
                    if (!ids.isArray() || ids.size() > 200) throw new ServiceException("选项卡组件范围不合法");
                    for (JsonNode id : ids)
                    {
                        if (!id.isTextual() || !widgetIds.contains(id.asText()))
                            throw new ServiceException("选项卡引用的组件不存在");
                    }
                }
            }
        }
    }

    /**
     * 查询表单是页面过滤器的受控编辑入口，只允许提交已声明的页面参数。
     * 表单字段不会执行 HTML、模板或脚本，避免把设计器 JSON 变成动态代码入口。
     */
    private void validateFilterForms(JsonNode widgets, JsonNode pageFilters)
    {
        Map<String, String> filterParameters = new LinkedHashMap<>();
        Set<String> declaredParameters = new HashSet<>();
        if (pageFilters != null && pageFilters.isArray())
        {
            for (JsonNode filter : pageFilters)
            {
                String id = filter.path("id").asText("");
                String parameter = filter.path("parameter").asText(filter.path("param").asText(""));
                if (!parameter.isBlank()) declaredParameters.add(parameter);
                if (!id.isBlank() && !parameter.isBlank()) filterParameters.put(id, parameter);
            }
        }
        if (widgets == null || !widgets.isArray()) return;
        for (JsonNode widget : widgets)
        {
            if (!Set.of("filter-form", "designer-form", "online-form").contains(widget.path("type").asText(""))) continue;
            JsonNode fields = widget.path("style").path("formFields");
            if (!fields.isArray() || fields.size() == 0 || fields.size() > MAX_FILTER_FORM_FIELDS)
                throw new ServiceException("查询表单字段数量必须在 1-" + MAX_FILTER_FORM_FIELDS + " 个之间");
            Set<String> names = new HashSet<>();
            Set<String> parameters = new HashSet<>();
            for (JsonNode field : fields)
            {
                if (!field.isObject()) throw new ServiceException("查询表单字段必须是对象");
                Set<String> allowed = Set.of("name", "label", "parameter", "placeholder", "defaultValue", "filterId", "type", "options", "optionsJson");
                field.fieldNames().forEachRemaining(name -> {
                    if (!allowed.contains(name)) throw new ServiceException("查询表单字段属性不支持: " + name);
                });
                String name = field.path("name").asText("");
                if (!SAFE_FIELD.matcher(name).matches() || !names.add(name))
                    throw new ServiceException("查询表单字段名不合法或重复: " + name);
                String parameter = field.path("parameter").asText("");
                if (!SAFE_FIELD.matcher(parameter).matches() || !parameters.add(parameter))
                    throw new ServiceException("查询表单参数名不合法或重复: " + parameter);
                String filterId = field.path("filterId").asText("");
                if (!filterId.isBlank())
                {
                    if (!SAFE_FIELD.matcher(filterId).matches() || !filterParameters.containsKey(filterId))
                        throw new ServiceException("查询表单未匹配页面过滤器: " + filterId);
                    if (!parameter.equals(filterParameters.get(filterId)))
                        throw new ServiceException("查询表单参数与页面过滤器不一致: " + parameter);
                }
                else if (!declaredParameters.contains(parameter))
                {
                    throw new ServiceException("查询表单参数未声明页面过滤器: " + parameter);
                }
                String type = field.path("type").asText("STRING").toUpperCase(Locale.ROOT);
                if (!("STRING".equals(type) || "NUMBER".equals(type) || "DATE".equals(type) || "DATETIME".equals(type)
                        || "SELECT".equals(type) || "RADIO".equals(type)))
                    throw new ServiceException("查询表单字段类型不支持: " + type);
                validateFilterFormText(field, "label");
                validateFilterFormText(field, "placeholder");
                validateFilterFormText(field, "defaultValue");
                if ("SELECT".equals(type) || "RADIO".equals(type)) validateFilterFormOptions(field);
            }
        }
    }

    private void validateFilterFormText(JsonNode field, String name)
    {
        JsonNode value = field.get(name);
        if (value == null || value.isNull()) return;
        if (!value.isValueNode() || value.isBinary()) throw new ServiceException("查询表单字段 " + name + " 必须是纯文本或基础值");
        String text = value.asText("");
        if (text.length() > MAX_FILTER_FORM_TEXT) throw new ServiceException("查询表单字段 " + name + " 过长");
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("javascript:") || lower.contains("<script") || lower.contains("</script")
                || lower.contains("<iframe") || lower.contains("function(") || lower.contains("=>")
                || lower.contains("{{") || lower.contains("}}"))
            throw new ServiceException("查询表单字段 " + name + " 包含不允许的脚本或模板内容");
        for (int i = 0; i < text.length(); i++)
        {
            if (Character.isISOControl(text.charAt(i)) && text.charAt(i) != '\n' && text.charAt(i) != '\r' && text.charAt(i) != '\t')
                throw new ServiceException("查询表单字段 " + name + " 包含控制字符");
        }
    }

    private void validateFilterFormOptions(JsonNode field)
    {
        JsonNode options = field.get("options");
        if (options == null || options.isNull())
        {
            String optionsJson = field.path("optionsJson").asText("");
            if (!optionsJson.isBlank())
            {
                try { options = objectMapper.readTree(optionsJson); }
                catch (JsonProcessingException ex) { throw new ServiceException("查询表单选项 JSON 不合法"); }
            }
        }
        if (options == null || !options.isArray() || options.size() == 0 || options.size() > 100)
            throw new ServiceException("下拉/单选表单必须配置 1-100 个选项");
        for (JsonNode option : options)
        {
            if (!option.isObject()) throw new ServiceException("查询表单选项必须是对象");
            Set<String> allowed = Set.of("label", "value");
            option.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new ServiceException("查询表单选项属性不支持: " + name); });
            if (!option.has("label") || !option.has("value") || !option.get("label").isValueNode() || !option.get("value").isValueNode())
                throw new ServiceException("查询表单选项必须包含 label 和 value");
            validateFilterFormText(option, "label");
            validateFilterFormText(option, "value");
        }
    }

    private void validatePageFilters(JsonNode filters, Set<String> widgetIds)
    {
        if (filters == null || filters.isMissingNode() || filters.isNull()) return;
        if (!filters.isArray() || filters.size() > 50) throw new ServiceException("页面过滤器不合法");
        Set<String> parameters = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode filter : filters)
        {
            if (!filter.isObject()) throw new ServiceException("页面过滤器必须是对象");
            String parameter = filter.path("parameter").asText(filter.path("param").asText(""));
            if (!SAFE_FIELD.matcher(parameter).matches()) throw new ServiceException("页面过滤器参数不合法");
            if (!parameters.add(parameter)) throw new ServiceException("页面过滤器参数不能重复: " + parameter);
            String id = filter.path("id").asText("");
            if (!id.isBlank() && (!SAFE_FIELD.matcher(id).matches() || !ids.add(id)))
                throw new ServiceException("页面过滤器 ID 不合法或重复: " + id);
            String type = filter.path("type").asText("STRING").toUpperCase(Locale.ROOT);
            if (!("STRING".equals(type) || "NUMBER".equals(type) || "DATE".equals(type) || "DATETIME".equals(type)))
                throw new ServiceException("页面过滤器类型不支持: " + type);
            JsonNode targetWidgets = filter.path("targetWidgetIds");
            if (!targetWidgets.isMissingNode())
            {
                if (!targetWidgets.isArray() || targetWidgets.size() > 200) throw new ServiceException("页面过滤器目标组件不合法");
                for (JsonNode target : targetWidgets)
                {
                    if (!target.isTextual() || !widgetIds.contains(target.asText()))
                        throw new ServiceException("页面过滤器目标组件不存在");
                }
            }
        }
    }

    private void validateInteraction(JsonNode interaction)
    {
        String action = interaction.path("onClick").asText(interaction.path("clickAction").asText("none"));
        if (!("none".equals(action) || "popup".equals(action) || "filter".equals(action) || "drilldown".equals(action)
                || "self-drilldown".equals(action) || "page".equals(action) || "browser".equals(action)))
            throw new ServiceException("组件点击动作不支持: " + action);
        String target = interaction.path("target").asText("");
        if ("self-drilldown".equals(action) && !target.isBlank())
            throw new ServiceException("组件自身钻取不能配置目标路由");
        if ("browser".equals(action))
        {
            if (target.isBlank() || target.length() > 2048 || !isSafeBrowserLink(target))
                throw new ServiceException("浏览器链接必须是有效的 http:// 或 https:// 地址");
        }
        else
        {
            if (!target.isBlank() && (!target.startsWith("/") || target.startsWith("//") || target.contains("\\")
                    || target.contains("://") || target.toLowerCase(Locale.ROOT).contains("javascript:")))
                throw new ServiceException("组件跳转目标必须是站内路由");
            if (target.length() > 256) throw new ServiceException("组件跳转目标过长");
        }
        JsonNode targetWidgets = interaction.path("targetWidgetIds");
        if (!targetWidgets.isMissingNode())
        {
            if (!targetWidgets.isArray() || targetWidgets.size() > 50) throw new ServiceException("联动目标组件不合法");
            for (JsonNode id : targetWidgets)
            {
                if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                    throw new ServiceException("联动目标组件 ID 不合法");
            }
        }
        for (String field : List.of("sourceField", "targetParameter", "targetField"))
        {
            String value = interaction.path(field).asText("");
            if (!value.isBlank() && !SAFE_FIELD.matcher(value).matches())
                throw new ServiceException("组件联动字段不合法: " + field);
        }
        JsonNode mappings = interaction.path("parameterMappings");
        if (!mappings.isMissingNode())
        {
            if (!mappings.isArray() || mappings.size() > 20) throw new ServiceException("钻取参数映射不合法");
            for (JsonNode mapping : mappings)
            {
                if (!mapping.isObject()) throw new ServiceException("钻取参数映射必须是对象");
                String sourceField = mapping.path("sourceField").asText("");
                String targetParameter = mapping.path("targetParameter").asText("");
                if (!SAFE_FIELD.matcher(sourceField).matches() || !SAFE_FIELD.matcher(targetParameter).matches())
                    throw new ServiceException("钻取参数映射字段不合法");
                mapping.fieldNames().forEachRemaining(name -> {
                    if (!("sourceField".equals(name) || "targetParameter".equals(name)))
                        throw new ServiceException("钻取参数映射属性不支持: " + name);
                });
            }
        }
        if ("self-drilldown".equals(action))
        {
            validateSelfDrilldown(interaction.path("drilldown"));
        }

        // 新版页面跳转使用稳定的页面编码/主键；保存和发布时在服务端确认目标
        // 仍可运行，避免仅依赖设计器下拉框或前端路由校验。未提供新字段时保留
        // 旧版 target 站内路由的兼容行为。
        validatePublishedInteractionTarget(interaction, action);
    }

    /**
     * 校验页面跳转交互的目标页面。targetPageCode 与 targetPageId 可以单独提供，
     * 同时提供时必须指向同一页面。目标页面必须存在、启用、未回收并有发布版本。
     */
    private void validatePublishedInteractionTarget(JsonNode interaction, String action)
    {
        String targetMode = interaction.path("targetMode").asText("").trim().toLowerCase(Locale.ROOT);
        if (!targetMode.isBlank() && !Set.of("published", "route").contains(targetMode))
            throw new ServiceException("页面跳转目标类型不支持: " + targetMode);

        String targetPageCode = interaction.path("targetPageCode").asText("").trim();
        Long targetPageId = parseInteractionPageId(interaction.get("targetPageId"));
        boolean hasTargetReference = !targetPageCode.isBlank() || targetPageId != null;
        boolean pageTargetAction = "page".equals(action) || "drilldown".equals(action);
        boolean publishedMode = "published".equals(targetMode);
        if (publishedMode && !pageTargetAction)
            throw new ServiceException("已发布页面目标只能用于页面跳转或钻取动作");
        if (!hasTargetReference)
        {
            if (publishedMode)
                throw new ServiceException("已发布页面跳转必须选择目标页面");
            return;
        }
        if (!pageTargetAction)
            throw new ServiceException("只有页面跳转或钻取动作可以配置目标页面");
        if (!targetPageCode.isBlank() && !SAFE_PAGE_CODE.matcher(targetPageCode).matches())
            throw new ServiceException("目标页面编码不合法");

        Map<String, Object> targetPage = targetPageId == null
                ? dashboardMapper.selectPageByCode(targetPageCode)
                : dashboardMapper.selectPageById(targetPageId);
        if (targetPage == null)
            throw new ServiceException("目标页面不存在");
        if ("1".equals(String.valueOf(targetPage.get("isDeleted"))))
            throw new ServiceException("目标页面已移入回收站");
        if ("1".equals(String.valueOf(targetPage.get("status"))))
            throw new ServiceException("目标页面已停用");

        Long resolvedPageId = toLong(targetPage.get("pageId"));
        if (!targetPageCode.isBlank() && !targetPageCode.equals(String.valueOf(targetPage.get("pageCode"))))
            throw new ServiceException("目标页面编码与主键不匹配");
        if (targetPageId != null && !targetPageId.equals(resolvedPageId))
            throw new ServiceException("目标页面主键不匹配");
        if (publishedRevision(resolvedPageId) == null)
            throw new ServiceException("目标页面尚未发布");
    }

    private Long parseInteractionPageId(JsonNode value)
    {
        if (value == null || value.isNull()) return null;
        if (!(value.isIntegralNumber() || value.isTextual()))
            throw new ServiceException("目标页面主键不合法");
        String raw = value.asText("").trim();
        if (raw.isBlank()) return null;
        try
        {
            long id = Long.parseLong(raw);
            if (id <= 0) throw new NumberFormatException();
            return id;
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException("目标页面主键不合法");
        }
    }

    private boolean isSafeBrowserLink(String target)
    {
        try
        {
            java.net.URI uri = java.net.URI.create(target);
            String scheme = uri.getScheme();
            return scheme != null && Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        }
        catch (IllegalArgumentException ex)
        {
            return false;
        }
    }

    private void validateSelfDrilldown(JsonNode drilldown)
    {
        if (drilldown == null || !drilldown.isObject())
            throw new ServiceException("组件自身钻取配置必须是对象");
        drilldown.fieldNames().forEachRemaining(name -> {
            if (!"levels".equals(name)) throw new ServiceException("组件自身钻取属性不支持: " + name);
        });
        JsonNode levels = drilldown.path("levels");
        if (!levels.isArray() || levels.size() < 1 || levels.size() > 5)
            throw new ServiceException("组件自身钻取层级必须是 1-5 级");
        for (JsonNode level : levels)
        {
            if (!level.isObject()) throw new ServiceException("组件自身钻取层级必须是对象");
            level.fieldNames().forEachRemaining(name -> {
                if (!("label".equals(name) || "fieldMap".equals(name) || "parameterMappings".equals(name)))
                    throw new ServiceException("组件自身钻取层级属性不支持: " + name);
            });
            String label = level.path("label").asText("");
            if (label.length() > 64) throw new ServiceException("组件自身钻取层级名称过长");
            JsonNode fieldMap = level.path("fieldMap");
            if (!fieldMap.isMissingNode())
            {
                if (!fieldMap.isObject() || fieldMap.size() > 8) throw new ServiceException("组件自身钻取字段映射不合法");
                fieldMap.fieldNames().forEachRemaining(name -> {
                    String source = fieldMap.path(name).asText("");
                    if (!SAFE_FIELD.matcher(name).matches() || !SAFE_FIELD.matcher(source).matches())
                        throw new ServiceException("组件自身钻取字段映射必须使用安全字段名");
                });
            }
            JsonNode parameterMappings = level.path("parameterMappings");
            if (!parameterMappings.isArray() || parameterMappings.size() > 20)
                throw new ServiceException("组件自身钻取参数映射不合法");
            for (JsonNode mapping : parameterMappings)
            {
                if (!mapping.isObject()) throw new ServiceException("组件自身钻取参数映射必须是对象");
                mapping.fieldNames().forEachRemaining(name -> {
                    if (!("sourceField".equals(name) || "targetParameter".equals(name)
                            || "targetField".equals(name)))
                        throw new ServiceException("组件自身钻取参数映射属性不支持: " + name);
                });
                String sourceField = mapping.path("sourceField").asText("");
                String targetParameter = mapping.path("targetParameter").asText("");
                String targetField = mapping.path("targetField").asText("");
                if (!SAFE_FIELD.matcher(sourceField).matches()
                        || !SAFE_FIELD.matcher(targetParameter).matches()
                        || (!targetField.isBlank() && !SAFE_FIELD.matcher(targetField).matches()))
                    throw new ServiceException("组件自身钻取参数映射字段不合法");
            }
        }
    }

    private void validateOptionJson(JsonNode value, String componentType)
    {
        // optionJson 是可选的：设计器会为未配置自定义 option 保留空字符串。
        // 空配置交给平台按组件类型生成默认 option，只有非空值才进入白名单校验。
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) return;
        JsonNode option;
        try { option = value.isTextual() ? objectMapper.readTree(value.asText()) : value; }
        catch (JsonProcessingException ex) { throw new ServiceException("ECharts optionJson 格式不合法"); }
        if (option == null || !option.isObject()) throw new ServiceException("ECharts optionJson 必须是对象");
        Set<String> allowed = new HashSet<>(Set.of("title", "legend", "tooltip", "grid", "xAxis", "yAxis", "color", "radar"));
        if ("custom-chart".equals(componentType)) allowed.add("series");
        option.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new ServiceException("ECharts option 属性不在白名单: " + name); });
        String serialized = option.toString().toLowerCase(Locale.ROOT);
        if (serialized.contains("function") || serialized.contains("javascript") || serialized.contains("<script"))
            throw new ServiceException("ECharts option 不允许脚本内容");
        if ("custom-chart".equals(componentType) && option.has("series")) validateCustomChartSeries(option.get("series"));
    }

    /** 通用图表允许少量声明式 series 样式，但不允许事件、函数、HTML 或外部资源。 */
    private void validateCustomChartSeries(JsonNode series)
    {
        if (!series.isArray() || series.size() > 12) throw new ServiceException("通用图表 series 必须是 1-12 项数组");
        Set<String> allowed = Set.of("type", "name", "data", "smooth", "stack", "areaStyle", "itemStyle", "label",
                "symbol", "symbolSize", "barWidth", "lineStyle", "emphasis", "radius", "center");
        Set<String> types = Set.of("line", "bar", "pie", "gauge", "funnel", "radar", "scatter");
        for (JsonNode item : series)
        {
            if (!item.isObject()) throw new ServiceException("通用图表 series 项必须是对象");
            item.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new ServiceException("通用图表 series 属性不支持: " + name); });
            String type = item.path("type").asText("bar");
            if (!types.contains(type)) throw new ServiceException("通用图表 series 类型不在白名单: " + type);
            validatePlainText(item, "name", 128);
            JsonNode data = item.get("data");
            if (data != null)
            {
                if (!data.isArray() || data.size() > MAX_ROWS) throw new ServiceException("通用图表 series 数据不能超过 1000 项");
                for (JsonNode datum : data)
                {
                    if (datum.isObject())
                    {
                        Set<String> dataFields = Set.of("name", "value", "row");
                        datum.fieldNames().forEachRemaining(name -> { if (!dataFields.contains(name)) throw new ServiceException("通用图表数据项属性不支持: " + name); });
                        if (datum.has("row") && !datum.path("row").isValueNode() && !datum.path("row").isNull())
                            throw new ServiceException("通用图表数据项 row 只能是基础值");
                    }
                    else if (!datum.isValueNode() && !datum.isNull()) throw new ServiceException("通用图表数据只能是基础值");
                }
            }
            for (String objectField : List.of("areaStyle", "itemStyle", "label", "lineStyle", "emphasis"))
            {
                JsonNode config = item.get(objectField);
                if (config != null && !config.isObject()) throw new ServiceException("通用图表 " + objectField + " 必须是对象");
                if (config != null && config.isObject())
                {
                    String nested = config.toString().toLowerCase(Locale.ROOT);
                    if (nested.contains("function") || nested.contains("javascript") || nested.contains("<script"))
                        throw new ServiceException("通用图表样式不允许脚本内容");
                }
            }
            if (item.has("smooth") && !item.get("smooth").isBoolean()) throw new ServiceException("通用图表 smooth 必须是布尔值");
            if (item.has("stack") && !item.get("stack").isBoolean() && !item.get("stack").isTextual()) throw new ServiceException("通用图表 stack 类型不合法");
        }
    }

    private void validateChartConfig(JsonNode value, String componentType)
    {
        if (value == null || value.isNull()) return;
        if (!value.isObject()) throw new ServiceException("图表通用配置必须是对象");
        Set<String> allowed = Set.of("tooltip", "legend", "xAxis", "yAxis", "grid", "smooth", "areaOpacity", "stack", "label", "markLine", "center", "colors", "colorsText", "valueScale", "valuePrecision");
        value.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new ServiceException("图表通用配置属性不支持: " + name);
        });
        JsonNode tooltip = value.get("tooltip");
        if (tooltip != null && (!tooltip.isObject() || (tooltip.has("show") && !tooltip.get("show").isBoolean())))
            throw new ServiceException("图表提示框配置不合法");
        if (tooltip != null && tooltip.isObject())
        {
            rejectChartFields(tooltip, Set.of("show", "fontSize", "textColor", "backgroundColor", "borderColor"), "图表提示框配置属性不支持: ");
            validateChartNumber(tooltip, "fontSize", 10, 32, "提示框字号必须在 10-32 范围内");
        }
        JsonNode legend = value.get("legend");
        if (legend != null)
        {
            if (!legend.isObject()) throw new ServiceException("图表图例配置不合法");
            if (legend.has("show") && !legend.get("show").isBoolean()) throw new ServiceException("图例显示配置不合法");
            String position = legend.path("position").asText("bottom");
            String orient = legend.path("orient").asText("horizontal");
            if (!Set.of("top", "bottom", "left", "right").contains(position)
                    || !Set.of("horizontal", "vertical").contains(orient)) throw new ServiceException("图例位置或方向不合法");
            rejectChartFields(legend, Set.of("show", "position", "orient", "fontSize", "textColor", "itemWidth", "itemHeight", "margin"), "图表图例配置属性不支持: ");
            validateChartNumber(legend, "fontSize", 9, 28, "图例字号必须在 9-28 范围内");
            validateChartNumber(legend, "itemWidth", 6, 40, "图例图标宽度必须在 6-40 范围内");
            validateChartNumber(legend, "itemHeight", 4, 24, "图例图标高度必须在 4-24 范围内");
            JsonNode margin = legend.get("margin");
            if (margin != null)
            {
                if (!margin.isObject()) throw new ServiceException("图例边距配置不合法");
                rejectChartFields(margin, Set.of("left", "right", "top", "bottom"), "图例边距属性不支持: ");
                for (String edge : List.of("left", "right", "top", "bottom"))
                    validateChartNumber(margin, edge, 0, 240, "图例边距必须在 0-240 范围内");
            }
        }
        for (String axisName : List.of("xAxis", "yAxis"))
        {
            JsonNode axis = value.get(axisName);
            if (axis == null) continue;
            if (!axis.isObject()) throw new ServiceException("图表坐标轴配置不合法");
            rejectChartFields(axis, Set.of("show", "type", "axisLineShow", "labelRotate", "name", "axisLineColor", "labelColor", "labelFontSize", "splitLineShow", "splitLineColor", "min", "max"), "图表坐标轴配置属性不支持: ");
            if (axis.has("show") && !axis.get("show").isBoolean()) throw new ServiceException("坐标轴显示配置不合法");
            if (axis.has("axisLineShow") && !axis.get("axisLineShow").isBoolean()) throw new ServiceException("坐标轴轴线显示配置不合法");
            if (axis.has("type") && !Set.of("category", "value", "time", "log").contains(axis.path("type").asText("")))
                throw new ServiceException("坐标轴类型不在白名单");
            if (axis.has("labelRotate") && (!axis.get("labelRotate").isNumber() || axis.get("labelRotate").asDouble() < -90 || axis.get("labelRotate").asDouble() > 90))
                throw new ServiceException("坐标轴标签旋转角度不合法");
            if (axis.has("name") && axis.get("name").asText("").length() > 128) throw new ServiceException("坐标轴名称过长");
            validateChartNumber(axis, "labelFontSize", 9, 28, "坐标轴标签字号必须在 9-28 范围内");
            for (String bound : List.of("min", "max"))
            {
                JsonNode number = axis.get(bound);
                if (number != null && !number.isNull() && !number.isNumber()) throw new ServiceException("坐标轴范围必须是数字");
            }
        }
        JsonNode grid = value.get("grid");
        if (grid != null)
        {
            if (!grid.isObject()) throw new ServiceException("图表边距配置不合法");
            for (String edge : List.of("left", "right", "top", "bottom"))
            {
                JsonNode number = grid.get(edge);
                if (number != null && (!number.isNumber() || number.asDouble() < 0 || number.asDouble() > 240))
                    throw new ServiceException("图表边距必须在 0-240 范围内");
            }
        }
        if (value.has("smooth") && !value.get("smooth").isBoolean()) throw new ServiceException("曲线平滑配置不合法");
        if (value.has("stack") && !value.get("stack").isBoolean()) throw new ServiceException("堆叠配置不合法");
        if (value.has("areaOpacity") && (!value.get("areaOpacity").isNumber() || value.get("areaOpacity").asDouble() < 0 || value.get("areaOpacity").asDouble() > 1))
            throw new ServiceException("面积透明度必须在 0-1 范围内");
        if (value.has("valueScale") && !Set.of("none", "thousand", "ten-thousand", "million").contains(value.path("valueScale").asText("none")))
            throw new ServiceException("数值数量级不在白名单");
        validateChartNumber(value, "valuePrecision", 0, 6, "数值小数位数必须在 0-6 范围内");
        if (value.has("valuePrecision") && !value.get("valuePrecision").isIntegralNumber())
            throw new ServiceException("数值小数位数必须是整数");
        JsonNode label = value.get("label");
        if (label != null)
        {
            if (!label.isObject() || (label.has("show") && !label.get("show").isBoolean())) throw new ServiceException("数值标签配置不合法");
            rejectChartFields(label, Set.of("show", "position", "format", "color", "fontSize", "fontWeight"), "数值标签配置属性不支持: ");
            String position = label.path("position").asText("top");
            if (!Set.of("top", "bottom", "left", "right", "inside", "insideTop", "insideBottom").contains(position))
                throw new ServiceException("数值标签位置不合法");
            String format = label.path("format").asText("{c}");
            if (format.length() > 64 || !format.matches("[^{}]*(?:\\{[abcd]\\}[^{}]*)*"))
                throw new ServiceException("数值标签格式只允许 {a}、{b}、{c}、{d} 占位符");
            validateChartNumber(label, "fontSize", 9, 32, "数值标签字号必须在 9-32 范围内");
            validateChartNumber(label, "fontWeight", 300, 900, "数值标签字重不合法");
        }
        JsonNode markLine = value.get("markLine");
        if (markLine != null)
        {
            if (!markLine.isObject()) throw new ServiceException("Y 轴辅助线配置不合法");
            rejectChartFields(markLine, Set.of("show", "value", "label", "color", "lineType"), "Y 轴辅助线配置属性不支持: ");
            if (markLine.has("show") && !markLine.get("show").isBoolean()) throw new ServiceException("Y 轴辅助线显示配置不合法");
            if (markLine.has("value") && !markLine.get("value").isNull() && !markLine.get("value").isNumber())
                throw new ServiceException("Y 轴辅助线数值必须是数字");
            String lineType = markLine.path("lineType").asText("dashed");
            if (!Set.of("solid", "dashed", "dotted").contains(lineType)) throw new ServiceException("Y 轴辅助线线型不合法");
            validatePlainText(markLine, "label", 64);
            String color = markLine.path("color").asText("");
            if (!color.isBlank() && !color.matches("#[0-9a-fA-F]{3,8}")) throw new ServiceException("Y 轴辅助线颜色不合法");
        }
        JsonNode center = value.get("center");
        if (center != null)
        {
            if (!center.isObject()) throw new ServiceException("图表中心坐标配置不合法");
            rejectChartFields(center, Set.of("x", "y"), "图表中心坐标属性不支持: ");
            validateChartNumber(center, "x", 0, 100, "图表中心横向位置必须在 0-100 范围内");
            validateChartNumber(center, "y", 0, 100, "图表中心纵向位置必须在 0-100 范围内");
        }
        JsonNode colors = value.get("colors");
        if (colors != null)
        {
            if (!colors.isArray() || colors.size() > 12) throw new ServiceException("图表配色数量不合法");
            for (JsonNode color : colors)
            {
                String text = color.asText("");
                if (!color.isTextual() || text.length() > 64 || !text.matches("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 .,%-]+\\)"))
                    throw new ServiceException("图表配色格式不合法");
            }
        }
        if (value.has("colorsText") && value.get("colorsText").asText("").length() > 1024)
            throw new ServiceException("图表配色文本过长");
        if ("word-cloud".equals(componentType) && value.has("stack") && value.path("stack").asBoolean(false))
            throw new ServiceException("文字云不支持堆叠配置");
    }

    private void rejectChartFields(JsonNode node, Set<String> allowed, String message)
    {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new ServiceException(message + name);
        });
    }

    private void validateChartNumber(JsonNode node, String field, double min, double max, String message)
    {
        if (!node.has(field) || node.get(field).isNull()) return;
        JsonNode value = node.get(field);
        if (!value.isNumber() || value.asDouble() < min || value.asDouble() > max)
            throw new ServiceException(message);
    }

    private void validateWidgetStyle(JsonNode style)
    {
        if (style.has("buttonShape") && !Set.of("rect", "angled").contains(style.path("buttonShape").asText("rect")))
            throw new ServiceException("按钮形状只支持矩形或斜角");
        JsonNode tableRules = style.get("tableRules");
        if (tableRules != null && !tableRules.isNull())
        {
            if (!tableRules.isArray() || tableRules.size() > 40) throw new ServiceException("表格格式规则不能超过 40 条");
            for (JsonNode rule : tableRules)
            {
                if (!rule.isObject()) throw new ServiceException("表格格式规则必须为对象");
                rejectChartFields(rule, Set.of("field", "equals", "color", "background", "badge", "suffix"), "表格格式规则属性不支持: ");
                if (!SAFE_FIELD.matcher(rule.path("field").asText("")).matches()) throw new ServiceException("格式规则字段不合法");
                if (rule.has("equals") && (!rule.get("equals").isValueNode() || rule.get("equals").asText("").length() > 128)) throw new ServiceException("格式规则匹配值不合法");
                if (rule.has("badge") && !rule.get("badge").isBoolean()) throw new ServiceException("格式规则标记必须为布尔值");
                for (String color : List.of("color", "background"))
                    if (rule.has(color) && !rule.path(color).asText("").matches("#[0-9a-fA-F]{3,8}")) throw new ServiceException("格式规则颜色必须为十六进制颜色");
                validatePlainText(rule, "suffix", 24);
            }
        }
        if (style.has("titleAlign") && !Set.of("left", "center", "right").contains(style.path("titleAlign").asText("left")))
            throw new ServiceException("标题水平对齐方式不在白名单");
        if (style.has("titleVerticalAlign") && !Set.of("top", "middle", "bottom").contains(style.path("titleVerticalAlign").asText("top")))
            throw new ServiceException("标题垂直对齐方式不在白名单");
        if (style.has("textAlign") && !Set.of("left", "center", "right").contains(style.path("textAlign").asText("center")))
            throw new ServiceException("组件文字对齐方式不在白名单");
        if (style.has("contentVerticalAlign") && !Set.of("top", "center", "bottom").contains(style.path("contentVerticalAlign").asText("center")))
            throw new ServiceException("组件内容垂直对齐方式不在白名单");
        if (style.has("borderStyle") && !Set.of("solid", "dashed", "dotted", "double").contains(style.path("borderStyle").asText("solid")))
            throw new ServiceException("组件边框样式不在白名单");
        for (String field : List.of("title", "subtitle", "text", "unit", "timeFormat", "city", "condition", "temperatureUnit"))
            if (style.has(field)) validatePlainText(style, field, 2000);
        validateChartNumber(style, "valueDecimalPlaces", 0, 6, "数值小数位必须在 0-6 范围内");
        if (style.hasNonNull("valueDecimalPlaces") && !style.get("valueDecimalPlaces").isIntegralNumber())
            throw new ServiceException("数值小数位必须是整数");
        validateChartNumber(style, "fontSize", 10, 96, "组件字号必须在 10-96 范围内");
        validateChartNumber(style, "fontWeight", 100, 900, "组件字重必须在 100-900 范围内");
        validateChartNumber(style, "borderWidth", 0, 16, "组件边框宽度必须在 0-16 范围内");
        validateChartNumber(style, "borderOpacity", 0, 1, "组件边框透明度必须在 0-1 范围内");
        for (String field : List.of("titleColor", "subtitleColor", "color", "backgroundColor", "borderColor"))
        {
            if (!style.has(field) || style.get(field).isNull()) continue;
            String color = style.path(field).asText("");
            if (!style.get(field).isTextual() || color.length() > 64 || (!color.isBlank() && !color.matches("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 .,%-]+\\)")))
                throw new ServiceException("组件颜色格式不合法: " + field);
        }
        for (String field : List.of("ganttStart", "ganttEnd", "ganttCurrentDate"))
        {
            if (!style.has(field) || style.get(field).isNull() || style.path(field).asText("").isBlank()) continue;
            try { java.time.LocalDate.parse(style.path(field).asText("")); }
            catch (java.time.format.DateTimeParseException ex) { throw new ServiceException("甘特图日期必须为有效的 YYYY-MM-DD: " + field); }
        }
        if (!style.path("ganttStart").asText("").isBlank() && !style.path("ganttEnd").asText("").isBlank()
                && style.path("ganttEnd").asText().compareTo(style.path("ganttStart").asText()) <= 0)
            throw new ServiceException("甘特图结束日期必须晚于开始日期");
        validateChartNumber(style, "ganttLabelWidth", 80, 400, "甘特图任务列宽度必须在 80-400 范围内");
        if (style.has("ganttShowDependencies") && !style.get("ganttShowDependencies").isBoolean())
            throw new ServiceException("甘特图依赖开关必须是布尔值");
        validateChartNumber(style, "bar3dDepth", 3, 14, "3D 柱形深度必须在 3-14 范围内");
        if (style.has("customChartType") && !Set.of("line-chart", "bar-chart", "area-chart", "pie-chart", "ring-chart", "gauge", "progress", "funnel", "radar", "scatter").contains(style.path("customChartType").asText("bar-chart")))
            throw new ServiceException("通用图表类型不在白名单");
        validateChartNumber(style, "carouselSeconds", 2, 60, "轮播间隔必须在 2-60 秒范围内");
        validateChartNumber(style, "carouselInterval", 2, 60, "轮播间隔必须在 2-60 秒范围内");
        validateChartNumber(style, "advancedRowHeight", 20, 80, "高级表格行高必须在 20-80 范围内");
        validateChartNumber(style, "statsValueSize", 16, 72, "统计概览字号必须在 16-72 范围内");
        validateChartNumber(style, "statsLabelSize", 9, 36, "统计标签字号必须在 9-36 范围内");
        validateChartNumber(style, "statsUnitSize", 9, 30, "统计单位字号必须在 9-30 范围内");
        validateChartNumber(style, "flipMinDigits", 1, 12, "数字翻牌位数必须在 1-12 范围内");
        validateChartNumber(style, "flipCellGap", 0, 32, "数字翻牌间距必须在 0-32 范围内");
        validateChartNumber(style, "flipCellWidth", 18, 96, "数字翻牌单格宽度必须在 18-96 范围内");
        validateChartNumber(style, "flipCellHeight", 24, 120, "数字翻牌单格高度必须在 24-120 范围内");
        validateChartNumber(style, "ringInnerRadius", 0, 90, "环图内半径必须在 0-90 范围内");
        validateChartNumber(style, "ringOuterRadius", 1, 100, "环图外半径必须在 1-100 范围内");
        validateChartNumber(style, "ringLegendColumns", 1, 4, "环图图例列数必须在 1-4 范围内");
        validateChartNumber(style, "ringLegendWidth", 80, 600, "环图图例宽度必须在 80-600 范围内");
        validateChartNumber(style, "ringLegendLeft", 0, 100, "环图图例横向位置必须在 0-100 范围内");
        validateChartNumber(style, "ringLegendItemGap", 0, 40, "环图图例间距必须在 0-40 范围内");
        validateChartNumber(style, "buttonPaddingX", 0, 80, "按钮横向内边距必须在 0-80 范围内");
        validateChartNumber(style, "buttonPaddingY", 0, 40, "按钮纵向内边距必须在 0-40 范围内");
        validateChartNumber(style, "tableCellPadding", 0, 32, "表格横向内边距必须在 0-32 范围内");
        validateChartNumber(style, "tableFirstColumnWidth", 20, 90, "表格首列宽度必须在 20-90 范围内");
        validateChartNumber(style, "ringTextRadius", 10, 48, "环形文字半径必须在 10-48 范围内");
        validateChartNumber(style, "ringTextSpeed", 0, 120, "环形文字速度必须在 0-120 范围内");
        validateChartNumber(style, "ringTextTilt", -45, 45, "环形文字倾角必须在 -45-45 范围内");
        validateChartNumber(style, "accessAvatarSize", 24, 72, "图文记录头像大小必须在 24-72 范围内");
        validateChartNumber(style, "accessRowHeight", 56, 120, "图文记录行高必须在 56-120 范围内");
        for (String field : List.of("iconFrame", "buttonFill", "carouselShowIndex", "carouselHighlight", "advancedShowHeader", "advancedShowIndex", "advancedStripe", "advancedScroll", "statsShowCompare", "flipSplitDigits", "ringEqualSegments", "ringLegendShowValue", "ringTextShowOrbit", "ringTextGradient", "timelineShowDates", "timelineShowEndDate", "clockShowIcon", "accessShowAvatar", "backgroundTransparent", "borderTransparent", "embeddedMode", "qualityVisible", "titleImageEnabled", "useSystemPalette"))
            if (style.has(field) && !style.get(field).isBoolean()) throw new ServiceException("组件样式属性必须是布尔值: " + field);
        if (style.has("titleImageFit") && !Set.of("stretch", "contain", "cover").contains(style.path("titleImageFit").asText("stretch")))
            throw new ServiceException("标题栏图片适配方式不在白名单");
        if (style.has("titleImageAlign") && !Set.of("left", "center", "right").contains(style.path("titleImageAlign").asText("left")))
            throw new ServiceException("标题栏图片水平对齐方式不在白名单");
        validateChartNumber(style, "titleImageHeight", 20, 120, "标题栏高度必须在 20-120 范围内");
        for (String field : List.of("titlePaddingTop", "titlePaddingRight", "titlePaddingBottom", "titlePaddingLeft"))
            validateChartNumber(style, field, 0, 120, "标题内边距必须在 0-120 范围内");
        for (String field : List.of("contentPaddingTop", "contentPaddingRight", "contentPaddingBottom", "contentPaddingLeft"))
            validateChartNumber(style, field, 0, 120, "内容内边距必须在 0-120 范围内");
        if (style.has("statsMode") && !Set.of("card", "compact").contains(style.path("statsMode").asText("card"))) throw new ServiceException("统计概览模式不在白名单");
        if (style.has("statsLayout") && !Set.of("vertical", "horizontal").contains(style.path("statsLayout").asText("vertical"))) throw new ServiceException("统计概览布局不在白名单");
        if (style.has("statsLabelPosition") && !Set.of("before", "after").contains(style.path("statsLabelPosition").asText("before"))) throw new ServiceException("统计标签位置不在白名单");
        if (style.has("carouselDirection") && !Set.of("horizontal", "vertical").contains(style.path("carouselDirection").asText("horizontal"))) throw new ServiceException("卡片轮播方向不在白名单");
        if (style.has("ringTextDirection") && !Set.of("normal", "reverse").contains(style.path("ringTextDirection").asText("normal"))) throw new ServiceException("环形文字方向不在白名单");
        if (style.has("imageFit") && !Set.of("contain", "cover", "fill", "none", "scale-down").contains(style.path("imageFit").asText("contain"))) throw new ServiceException("图片适配方式不在白名单");
        if (style.has("imagePosition") && !Set.of("center", "top", "bottom", "left", "right").contains(style.path("imagePosition").asText("center"))) throw new ServiceException("图片位置不在白名单");
        if (style.has("timelineLabelPosition") && !Set.of("above", "below").contains(style.path("timelineLabelPosition").asText("below"))) throw new ServiceException("里程碑标题位置不在白名单");
        if (style.has("timelineDateFormat") && !Set.of("YYYY-MM-DD", "YYYY/MM/DD", "MM-DD").contains(style.path("timelineDateFormat").asText("YYYY-MM-DD"))) throw new ServiceException("里程碑日期格式不在白名单");
        if (style.has("timelineLayout") && !Set.of("compact", "spread").contains(style.path("timelineLayout").asText("compact"))) throw new ServiceException("里程碑内容分布不在白名单");
        if (style.has("accessVariant") && !Set.of("person", "vehicle").contains(style.path("accessVariant").asText("person"))) throw new ServiceException("图文记录样式不在白名单");
        for (String field : List.of("statsPrefix", "statsUpColor", "statsDownColor", "ringTextCenterText", "ringLegendSuffix", "timelineDateFormat"))
            if (style.has(field)) validatePlainText(style, field, 128);
        for (String field : List.of("accessStatusSuccessText", "accessStatusNeutralText"))
            if (style.has(field)) validatePlainText(style, field, 16);
        for (String field : List.of("ganttPlanColor", "ganttActualColor", "ganttHeaderColor", "statsUpColor", "statsDownColor", "statsLabelColor", "statsUnitColor", "flipCellBackground", "flipCellBorderColor", "timelineDoneColor", "timelineActiveColor", "tableHeaderColor", "tableTextColor", "tableValueColor", "tableHeaderBackground"))
        {
            String color = style.path(field).asText("");
            if (!color.isBlank() && !color.equals("transparent") && !color.matches("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 .,%-]+\\)")) throw new ServiceException("组件扩展颜色格式不合法: " + field);
        }
        if (style.has("ringInnerRadius") && style.has("ringOuterRadius")
                && style.path("ringInnerRadius").asDouble() >= style.path("ringOuterRadius").asDouble())
            throw new ServiceException("环图外半径必须大于内半径");
    }

    /**
     * 自定义 HTML 只在无同源权限的沙箱 iframe 中运行。允许内联 HTML/CSS/JS，
     * 但禁止嵌套框架、外部脚本/网络地址、浏览器凭证访问和通过 HTML 形成的代理入口。
     * 数据和跨组件操作只能经注入的 JLink 消息协议完成。
     */
    private void validateCustomHtml(JsonNode value)
    {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (!value.isTextual()) throw new ServiceException("自定义 HTML 内容必须是文本");
        String html = value.asText("");
        if (html.length() > MAX_CUSTOM_HTML_CHARS)
            throw new ServiceException("自定义 HTML 内容不能超过 256 KB");
        if (html.indexOf('\u0000') >= 0)
            throw new ServiceException("自定义 HTML 内容包含非法控制字符");
        String lower = html.toLowerCase(Locale.ROOT);
        for (String token : List.of("<iframe", "<object", "<embed", "<base", "<form",
                "javascript:", "data:text/html", "data:application/xhtml", "document.cookie",
                "localstorage", "sessionstorage", "window.top", "parent.location",
                "window.location", "import(", "eval(", "new function"))
        {
            if (lower.contains(token))
                throw new ServiceException("自定义 HTML 内容包含不允许的能力: " + token);
        }
        if (lower.matches("(?s).*<(script|link)[^>]+\\s(src|href)\\s*=.*")
                || lower.matches("(?s).*<[a-z][^>]*\\s(on[a-z]+)\\s*=\\s*[^>]*>.*")
                || lower.matches("(?s).*\\b(?:src|href)\\s*=\\s*['\"](?:https?:|//).*"))
            throw new ServiceException("自定义 HTML 不允许外部脚本、外链或事件属性");
        if (lower.contains("http://") || lower.contains("https://"))
            throw new ServiceException("自定义 HTML 不允许外部网络地址");
    }

    /**
     * 图片、视频和 iframe 只能引用平台托管资源或平台内部页面，避免把组件配置
     * 变成任意外链、开放重定向或第三方脚本入口。
     */
    private void validateWidgetResources(JsonNode style, String componentType)
    {
        for (String field : List.of("imageRef", "titleImageRef", "videoRef", "posterRef"))
        {
            if (!style.has(field) || style.get(field).isNull()) continue;
            String value = style.path(field).asText("");
            if (!value.isBlank() && !isSafePlatformResource(value))
                throw new ServiceException("组件资源不在平台白名单: " + field);
        }
        if (style.has("iframeRef") && !style.path("iframeRef").isNull())
        {
            String value = style.path("iframeRef").asText("");
            if (!value.isBlank() && (!isSafePlatformResource(value) || !value.startsWith("/dashboard/")))
                throw new ServiceException("Iframe 只允许平台内部页面");
        }
        if (style.has("timeFormat"))
        {
            String format = style.path("timeFormat").asText("HH:mm:ss");
            if (!Set.of("HH:mm", "HH:mm:ss", "YYYY-MM-DD", "YYYY-MM-DD HH:mm", "YYYY-MM-DD HH:mm:ss").contains(format))
                throw new ServiceException("时间组件格式不在白名单");
        }
        for (String field : List.of("autoplay", "muted", "loop", "controls"))
        {
            if (style.has(field) && !style.get(field).isBoolean())
                throw new ServiceException("视频组件属性必须是布尔值: " + field);
        }
        if (style.has("temperaturePrecision"))
        {
            JsonNode precision = style.get("temperaturePrecision");
            if (!precision.isIntegralNumber() || precision.asInt() < 0 || precision.asInt() > 2)
                throw new ServiceException("天气温度小数位数必须在 0-2 范围内");
        }
        // 受控地图只接受平台提供的 GeoJSON 引用，坐标和标签仍来自数据集字段映射。
        if (style.has("mapRef") && !style.path("mapRef").isNull())
        {
            String value = style.path("mapRef").asText("");
            if (!value.isBlank() || "map-chart".equals(componentType))
            {
                if (!value.isBlank() && !isSafePlatformResource(value))
                    throw new ServiceException("地图资源不在平台白名单");
            }
        }
        for (String field : List.of("mapShowLabels", "mapRoam", "mapAreaGradient", "mapVisualMap"))
        {
            if (style.has(field) && !style.get(field).isBoolean())
                throw new ServiceException("地图显示属性必须是布尔值: " + field);
        }
        if (style.has("mapPointSize"))
        {
            JsonNode pointSize = style.get("mapPointSize");
            if (!pointSize.isIntegralNumber() || pointSize.asInt() < 4 || pointSize.asInt() > 32)
                throw new ServiceException("地图点位大小必须在 4-32 范围内");
        }
        validateChartNumber(style, "mapLabelFontSize", 8, 32, "地图区域名称字号必须在 8-32 范围内");
        validateChartNumber(style, "mapBorderWidth", 0, 10, "地图边界宽度必须在 0-10 范围内");
        validateChartNumber(style, "mapZoom", .5, 20, "地图缩放比例必须在 0.5-20 范围内");
        validateChartNumber(style, "mapAspectScale", .5, 2, "地图长宽比必须在 0.5-2 范围内");
        validateChartNumber(style, "mapLayoutX", 0, 100, "地图中心 X 必须在 0-100 范围内");
        validateChartNumber(style, "mapLayoutY", 0, 100, "地图中心 Y 必须在 0-100 范围内");
        validateChartNumber(style, "mapLayoutSize", 10, 200, "地图布局大小必须在 10-200 范围内");
        validateChartNumber(style, "mapShadowBlur", 0, 100, "地图阴影大小必须在 0-100 范围内");
        validateChartNumber(style, "mapShadowOffsetX", -100, 100, "地图阴影水平偏移必须在 -100-100 范围内");
        validateChartNumber(style, "mapShadowOffsetY", -100, 100, "地图阴影垂直偏移必须在 -100-100 范围内");
        validateChartNumber(style, "mapVisualMin", -1000000000000D, 1000000000000D, "地图视觉映射最小值不合法");
        validateChartNumber(style, "mapVisualMax", -1000000000000D, 1000000000000D, "地图视觉映射最大值不合法");
        if (style.has("mapVisualMin") && style.has("mapVisualMax")
                && style.path("mapVisualMin").asDouble() >= style.path("mapVisualMax").asDouble())
            throw new ServiceException("地图视觉映射最大值必须大于最小值");
        for (String field : List.of("mapAreaColor", "mapCenterColor", "mapEdgeColor", "mapBorderColor",
                "mapEmphasisColor", "mapLabelColor", "mapShadowColor", "mapVisualMinColor", "mapVisualMaxColor"))
        {
            if (!style.has(field) || style.get(field).isNull()) continue;
            String color = style.path(field).asText("");
            if (!color.isBlank() && !color.matches("#[0-9a-fA-F]{3,8}|rgba?\\([0-9 .,%-]+\\)"))
                throw new ServiceException("地图颜色格式不合法: " + field);
        }
    }

    private boolean isSafePlatformResource(String value)
    {
        return value.length() <= 512
                && (value.startsWith("/profile/") || value.startsWith("/dashboard/assets/")
                        || value.startsWith("/dashboard/runtime/") || value.startsWith("/static/"))
                && !value.contains("..") && !value.contains("\\")
                && !value.contains("://") && !value.toLowerCase(Locale.ROOT).contains("javascript:")
                && !value.toLowerCase(Locale.ROOT).contains("data:");
    }

    private void validateStaticRows(JsonNode rows)
    {
        if (!rows.isArray() || rows.size() > MAX_ROWS)
            throw new ServiceException("组件静态数据必须是 0-1000 行数组");
        for (JsonNode row : rows)
        {
            if (!row.isObject() || row.size() > 100)
                throw new ServiceException("组件静态数据每行必须是最多 100 个字段的对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!SAFE_FIELD.matcher(name).matches()) throw new ServiceException("组件静态数据字段名不合法");
                JsonNode value = row.get(name);
                if (value != null && !value.isNull() && !value.isValueNode())
                    throw new ServiceException("组件静态数据只允许基础值");
                if (value != null && value.isTextual())
                {
                    String text = value.asText("");
                    if (text.length() > 2000 || text.toLowerCase(Locale.ROOT).contains("<script")
                            || text.toLowerCase(Locale.ROOT).contains("javascript:"))
                        throw new ServiceException("组件静态数据文本不合法");
                }
            });
        }
    }

    private Map<String, Object> currentRevision(Map<String, Object> page)
    {
        Object id = page.get("currentRevisionId");
        return id == null ? null : dashboardMapper.selectRevisionById(toLong(id));
    }

    private Map<String, Object> draftRevision(Long pageId)
    {
        return dashboardMapper.selectRevisionList(pageId).stream()
                .filter(item -> "DRAFT".equals(item.get("status")))
                .findFirst()
                .map(item -> dashboardMapper.selectRevisionById(toLong(item.get("revisionId"))))
                .orElse(null);
    }

    /** 返回页面最新的已发布版本；selectRevisionList 按版本号倒序。 */
    private Map<String, Object> publishedRevision(Long pageId)
    {
        if (pageId == null) return null;
        return dashboardMapper.selectRevisionList(pageId).stream()
                .filter(item -> "PUBLISHED".equals(item.get("status")))
                .findFirst()
                .map(item -> dashboardMapper.selectRevisionById(toLong(item.get("revisionId"))))
                .orElse(null);
    }

    private int nextVersion(Long pageId)
    {
        return dashboardMapper.selectRevisionList(pageId).stream().mapToInt(item -> {
            Object value = item.get("versionNo");
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        }).max().orElse(0) + 1;
    }

    private Map<String, Object> requirePage(Long pageId)
    {
        if (pageId == null) throw new ServiceException("页面编号不能为空");
        Map<String, Object> page = dashboardMapper.selectPageById(pageId);
        if (page == null) throw new ServiceException("页面不存在");
        if ("1".equals(String.valueOf(page.get("isDeleted")))) throw new ServiceException("页面已移入回收站");
        return page;
    }

    private Long normalizeFolderId(Object value)
    {
        if (value == null || text(value).isBlank()) return 0L;
        if ("0".equals(text(value).trim())) return 0L;
        return dataFolders.validateTarget("page", value);
    }

    private Long normalizeFolderParent(Object value)
    {
        return normalizeFolderId(value);
    }

    /**
     * 页面文件夹允许有限层级，但不能形成环。以待设置的父节点向上遍历，
     * 命中当前节点即拒绝本次修改；最多遍历 100 层，避免脏数据导致请求长时间占用。
     */
    private boolean createsFolderCycle(Long folderId, Long parentId)
    {
        Long cursor = parentId;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; cursor != null && cursor > 0 && i < 100; i++)
        {
            if (folderId.equals(cursor)) return true;
            if (!visited.add(cursor)) return true;
            Map<String, Object> parent = dashboardMapper.selectPageFolderById(cursor);
            if (parent == null) return false;
            cursor = toLong(parent.get("parentId"));
        }
        return cursor != null && cursor > 0;
    }

    private Long normalizeDatasetGroupParent(Object value)
    {
        if (value == null || text(value).isBlank()) return 0L;
        Long groupId = toLong(value);
        if (groupId == null || groupId < 0) throw new ServiceException("数据集文件夹编号不合法");
        if (groupId == 0) return 0L;
        if (dashboardMapper.selectDatasetGroupById(groupId) == null)
            throw new ServiceException("数据集父文件夹不存在");
        return groupId;
    }

    private boolean createsDatasetGroupCycle(Long groupId, Long parentId)
    {
        Long cursor = parentId;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; cursor != null && cursor > 0 && i < 100; i++)
        {
            if (groupId.equals(cursor)) return true;
            if (!visited.add(cursor)) return true;
            Map<String, Object> parent = dashboardMapper.selectDatasetGroupById(cursor);
            if (parent == null) return false;
            cursor = toLong(parent.get("parentId"));
        }
        return cursor != null && cursor > 0;
    }

    private Long normalizeResourceFolderId(Object value)
    {
        if (value == null || text(value).isBlank()) return 0L;
        if ("0".equals(text(value).trim())) return 0L;
        return dataFolders.validateTarget("resource", value);
    }

    private boolean createsResourceFolderCycle(Long folderId, Long parentId)
    {
        Long cursor = parentId;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; cursor != null && cursor > 0 && i < 100; i++)
        {
            if (folderId.equals(cursor)) return true;
            if (!visited.add(cursor)) return true;
            Map<String, Object> parent = dashboardMapper.selectResourceFolderById(cursor);
            if (parent == null) return false;
            cursor = toLong(parent.get("parentId"));
        }
        return cursor != null && cursor > 0;
    }

    private Map<String, Object> requireShare(String token)
    {
        String value = text(token);
        if (value.length() < 32 || value.length() > 128)
        {
            throw new ServiceException("分享链接无效或已过期");
        }
        return requireActiveShare(dashboardMapper.selectShareByTokenHash(hash(value)));
    }

    private Map<String, Object> requireActiveShare(Map<String, Object> share)
    {
        if (share == null || !"ACTIVE".equals(String.valueOf(share.get("status"))))
        {
            throw new ServiceException("分享链接无效或已撤销");
        }
        Object expiresAt = share.get("expiresAt");
        if (expiresAt == null)
        {
            return share;
        }
        Instant expiry;
        if (expiresAt instanceof java.util.Date)
        {
            expiry = ((java.util.Date) expiresAt).toInstant();
        }
        else
        {
            try
            {
                expiry = OffsetDateTime.parse(String.valueOf(expiresAt)).toInstant();
            }
            catch (RuntimeException ex)
            {
                try
                {
                    expiry = LocalDateTime.parse(String.valueOf(expiresAt)).atZone(ZoneId.systemDefault()).toInstant();
                }
                catch (RuntimeException ignored)
                {
                    throw new ServiceException("分享链接有效期无效");
                }
            }
        }
        if (!expiry.isAfter(Instant.now()))
        {
            throw new ServiceException("分享链接已过期");
        }
        return share;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> shareDatasetCatalog(Object schema)
    {
        if (!(schema instanceof Map)) return Collections.emptyList();
        Object rawWidgets = ((Map<String, Object>) schema).get("widgets");
        if (!(rawWidgets instanceof List)) return Collections.emptyList();
        Set<String> codes = new HashSet<>();
        for (Object item : (List<?>) rawWidgets)
        {
            if (!(item instanceof Map)) continue;
            Object binding = ((Map<String, Object>) item).get("binding");
            if (!(binding instanceof Map)) continue;
            String code = text(((Map<String, Object>) binding).get("datasetCode"));
            if (!code.isEmpty()) codes.add(code);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String code : codes)
        {
            Map<String, Object> dataset = dashboardMapper.selectDatasetByCode(code);
            if (dataset == null) continue;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("datasetCode", dataset.get("datasetCode"));
            summary.put("dataType", dataset.get("dataType"));
            summary.put("fieldSchemaJson", dataset.get("fieldSchemaJson"));
            summary.put("paramSchemaJson", dataset.get("paramSchemaJson"));
            summary.put("status", dataset.get("status"));
            summary.put("refreshSeconds", dataset.get("refreshSeconds"));
            result.add(summary);
        }
        return result;
    }

    private Map<String, Object> requireDataset(Long datasetId)
    {
        if (datasetId == null) throw new ServiceException("数据集编号不能为空");
        Map<String, Object> dataset = dashboardMapper.selectDatasetById(datasetId);
        if (dataset == null) throw new ServiceException("数据集不存在");
        return dataset;
    }

    private Map<String, Object> parseObject(String json, String label)
    {
        try
        {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) throw new ServiceException(label + "必须是 JSON 对象");
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException(label + "格式不合法");
        }
    }

    private Object readJson(Object value, String label)
    {
        try
        {
            // 当前响应转换器可能将 JsonNode 当作 Bean 展开。转换为普通
            // Map/List 后再交给响应层，确保前端拿到真实页面 JSON。
            return objectMapper.convertValue(objectMapper.readTree(String.valueOf(value)), Object.class);
        }
        catch (JsonProcessingException ex)
        {
            throw new ServiceException(label + "格式不合法");
        }
    }

    private JsonNode atPath(JsonNode node, String path)
    {
        if (path == null || path.isEmpty()) return node;
        JsonNode current = node;
        for (String part : path.split("\\."))
        {
            if (current == null) return objectMapper.createArrayNode();
            current = current.path(part);
        }
        return current;
    }

    private List<Map<String, Object>> rowsFromNode(JsonNode node)
    {
        if (node == null || node.isMissingNode() || node.isNull()) return new ArrayList<>();
        if (node.isObject() && node.has("rows")) node = node.get("rows");
        if (!node.isArray())
        {
            if (node.isObject()) return Collections.singletonList(objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {}));
            return new ArrayList<>();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : node)
        {
            if (item.isObject()) rows.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
        }
        return rows;
    }

    private void validateSql(String sql)
    {
        if (sql == null || sql.isBlank()) throw new ServiceException("SQL 不能为空");
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select ") || normalized.startsWith("select\n") || normalized.startsWith("with ")))
            throw new ServiceException("SQL 数据集只允许 SELECT 或 WITH 查询");
        if (sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) throw new ServiceException("SQL 不允许多语句或注释");
        for (String keyword : FORBIDDEN_SQL)
        {
            if (Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find()) throw new ServiceException("SQL 包含不允许的关键字");
        }
    }

    private DataSourceSettings resolveDataSource(String code)
    {
        if (code == null || code.isBlank()) throw new ServiceException("未配置数据源");
        if ("labor".equalsIgnoreCase(code) || "master".equalsIgnoreCase(code))
        {
            String prefix = "labor".equalsIgnoreCase(code) ? "dashboard.datasource.labor" : "spring.datasource.druid.master";
            String url = environment.getProperty(prefix + ".url");
            String username = environment.getProperty(prefix + ".username");
            String password = environment.getProperty(prefix + ".password", "");
            if (url == null || username == null) throw new ServiceException("数据源配置不完整: " + code);
            return new DataSourceSettings(url, username, password);
        }
        Map<String, Object> source = dashboardMapper.selectDataSourceByCode(code);
        if (source == null || !"ACTIVE".equalsIgnoreCase(text(source.get("status")))) throw new ServiceException("数据源未登记或未启用: " + code);
        if (!"MYSQL".equalsIgnoreCase(text(source.get("sourceType")))) throw new ServiceException("数据源不是 MySQL: " + code);
        Map<String, Object> config = parseObject(text(source.get("configJson")), "数据源配置");
        String url = text(config.get("jdbcUrl"));
        String username = text(config.get("username"));
        if (url.isBlank() || username.isBlank()) throw new ServiceException("数据源配置不完整: " + code);
        return new DataSourceSettings(url, username, decryptSecret(text(source.get("secretCiphertext"))));
    }

    private EndpointSettings resolveRegisteredEndpoint(Map<String, Object> source)
    {
        if (source == null || !"HTTP".equalsIgnoreCase(text(source.get("sourceType")))
                || !"ACTIVE".equalsIgnoreCase(text(source.get("status"))))
            throw new ServiceException("HTTP 数据源未登记或未启用");
        Map<String, Object> config = parseObject(text(source.get("configJson")), "HTTP 数据源配置");
        String baseUrl = text(config.get("baseUrl"));
        String path = text(config.get("path"));
        String method = text(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        if (baseUrl.isBlank() || path.isBlank()) throw new ServiceException("HTTP 数据源配置不完整");
        validateConfiguredEndpoint(baseUrl, config);
        return new EndpointSettings(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl,
                path.startsWith("/") ? path : "/" + path, method, decryptSecret(text(source.get("secretCiphertext"))));
    }

    private EndpointSettings resolveRegisteredWebsocketEndpoint(Map<String, Object> source)
    {
        if (source == null || !"WEBSOCKET".equalsIgnoreCase(text(source.get("sourceType")))
                || !"ACTIVE".equalsIgnoreCase(text(source.get("status"))))
            throw new ServiceException("WebSocket 数据源未登记或未启用");
        Map<String, Object> config = parseObject(text(source.get("configJson")), "WebSocket 数据源配置");
        String baseUrl = text(config.get("baseUrl"));
        String path = text(config.get("path"));
        if (baseUrl.isBlank() || path.isBlank()) throw new ServiceException("WebSocket 数据源配置不完整");
        validateConfiguredWebSocketEndpoint(baseUrl);
        return new EndpointSettings(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl,
                path.startsWith("/") ? path : "/" + path, "GET", decryptSecret(text(source.get("secretCiphertext"))));
    }

    private void validateConfiguredWebSocketEndpoint(String url)
    {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException ex) { throw new ServiceException("WebSocket 地址不合法"); }
        if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null)
            throw new ServiceException("WebSocket 地址协议不合法");
        String allowlist = text(environment.getProperty("dashboard.websocket.allowed-hosts"));
        if (allowlist.isBlank()) throw new ServiceException("WebSocket 来源未配置主机白名单");
        boolean allowed = Arrays.stream(allowlist.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .anyMatch(item -> item.equalsIgnoreCase(uri.getHost()));
        if (!allowed) throw new ServiceException("WebSocket 地址不在服务端白名单");
    }

    private void testWebsocketEndpoint(EndpointSettings endpoint) throws Exception
    {
        URI uri = URI.create(endpoint.baseUrl + endpoint.path);
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5));
        if (!endpoint.secret.isBlank())
            builder.header("Authorization", endpoint.secret.startsWith("Bearer ") ? endpoint.secret : "Bearer " + endpoint.secret);
        WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener()
        {
            @Override
            public void onOpen(WebSocket webSocket)
            {
                webSocket.request(1);
                WebSocket.Listener.super.onOpen(webSocket);
            }

            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message)
            {
                webSocket.request(1);
                return WebSocket.Listener.super.onPong(webSocket, message);
            }
        }).orTimeout(5, TimeUnit.SECONDS).join();
        try
        {
            socket.sendPing(ByteBuffer.wrap(new byte[] { 1 })).orTimeout(5, TimeUnit.SECONDS).join();
        }
        finally
        {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "dashboard health check")
                    .orTimeout(5, TimeUnit.SECONDS).exceptionally(ignored -> null).join();
        }
    }

    private String encryptSecret(String value)
    {
        if (value == null || value.isBlank()) return "";
        try
        {
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretKey(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        }
        catch (GeneralSecurityException ex) { throw new ServiceException("数据源凭证加密失败"); }
    }

    private String decryptSecret(String value)
    {
        if (value == null || value.isBlank()) return "";
        try
        {
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length <= 12) throw new GeneralSecurityException("invalid payload");
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretKey(), "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) { throw new ServiceException("数据源凭证无法解密"); }
    }

    private byte[] secretKey()
    {
        String configured = text(environment.getProperty("dashboard.datasource.encryption-key"));
        if (configured.isBlank()) throw new ServiceException("未配置数据源凭证加密密钥");
        try
        {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length >= 16) return Arrays.copyOf(decoded, 32);
        }
        catch (IllegalArgumentException ignored) { }
        try { return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)), 32); }
        catch (Exception ex) { throw new ServiceException("数据源凭证加密密钥不可用"); }
    }

    private String classifyError(Exception ex)
    {
        Throwable cause = rootCause(ex);
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.sql.SQLTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
        if (cause instanceof java.net.ConnectException) return "NOT_CONNECTED";
        if (safeMessage(ex).contains("鉴权")) return "AUTH_ERROR";
        if (safeMessage(ex).contains("未接入") || safeMessage(ex).contains("未配置")) return "NOT_CONNECTED";
        if (safeMessage(ex).contains("受限")) return "RATE_LIMITED";
        if (cause instanceof JsonProcessingException) return "INVALID_DATA";
        if (cause instanceof java.sql.SQLException) return "SOURCE_ERROR";
        if (cause instanceof ServiceException && (safeMessage(ex).contains("参数") || safeMessage(ex).contains("过滤")
                || safeMessage(ex).contains("字段") || safeMessage(ex).contains("操作符")
                || safeMessage(ex).contains("行数") || safeMessage(ex).contains("类型")
                || safeMessage(ex).contains("配置") || safeMessage(ex).contains("脚本"))) return "INVALID_DATA";
        return "SOURCE_ERROR";
    }

    private String safeMessage(Exception ex)
    {
        Throwable cause = rootCause(ex);
        if (cause instanceof java.sql.SQLException) return "数据源执行失败";
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) return "来源请求超时";
        if (cause instanceof java.net.ConnectException) return "来源尚未接入";
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return "数据源执行失败";
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private Throwable rootCause(Throwable value)
    {
        Throwable current = value;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private String required(Map<String, Object> body, String key)
    {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new ServiceException(key + " 不能为空");
        return String.valueOf(value).trim();
    }

    private String jsonString(Object value, String fallback)
    {
        if (value == null) return fallback;
        if (value instanceof String) return (String) value;
        try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new ServiceException("JSON 配置无法序列化"); }
    }

    private String text(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integer(Object value, int fallback)
    {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { int parsed = Integer.parseInt(String.valueOf(value)); if (parsed < 1 || parsed > 3600) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException ex) { throw new ServiceException("刷新或超时时间必须为 1-3600 的整数"); }
    }

    private Integer sortOrder(Object value)
    {
        if (value == null || String.valueOf(value).isBlank()) return 0;
        try
        {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed < 0 || parsed > 999999) throw new NumberFormatException();
            return parsed;
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException("排序值必须为 0-999999 的整数");
        }
    }

    private Long toLong(Object value)
    {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { throw new ServiceException("编号不合法"); }
    }

    private String hash(String value)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) builder.append(String.format("%02x", item));
            return builder.toString();
        }
        catch (Exception ex) { throw new IllegalStateException("无法计算页面配置哈希", ex); }
    }

    private static final class DataSourceSettings
    {
        private final String url;
        private final String username;
        private final String password;

        private DataSourceSettings(String url, String username, String password)
        {
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    private static final class EndpointSettings
    {
        private final String baseUrl;
        private final String path;
        private final String method;
        private final String secret;

        private EndpointSettings(String baseUrl, String path, String method, String secret)
        {
            this.baseUrl = baseUrl;
            this.path = path;
            this.method = method;
            this.secret = secret;
        }
    }
}
