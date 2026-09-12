package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardDataFolderMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 各业务沿用原目录表和编码，目录归属不参与连接、发布、契约或授权。 */
@Service
public class DashboardDataFolderService
{
    private static final Set<String> SCOPES = Set.of("dataset", "source", "integration", "page", "resource");

    @Autowired
    private DashboardDataFolderMapper mapper;

    public String requireScope(String scope)
    {
        if (!SCOPES.contains(scope == null ? "" : scope)) throw new ServiceException("数据目录类型不支持");
        return scope;
    }

    public List<Map<String, Object>> list(String scope)
    {
        List<Map<String, Object>> folders = new ArrayList<>(mapper.selectFolders(requireScope(scope), false));
        folders.removeIf(item -> !active(item));
        folders.sort(Comparator.comparingLong((Map<String, Object> item) -> number(item.get("sortOrder"), "排序"))
                .thenComparingLong(item -> number(item.get("folderId"), "文件夹编号")));
        return folders;
    }

    @Transactional
    public Map<String, Object> create(String scope, Map<String, Object> body, String username)
    {
        requireScope(scope);
        requireBody(body);
        List<Map<String, Object>> folders = mapper.selectFolders(scope, true);
        Long parentId = nonnegative(body.getOrDefault("parentId", 0), "父文件夹编号");
        requireParent(folders, parentId);
        String code = text(body.getOrDefault("folderCode", body.get("groupCode")));
        if (code.isBlank()) code = "folder-" + UUID.randomUUID().toString().replace("-", "");
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,63}"))
            throw new ServiceException("文件夹编码只能使用字母、数字、下划线和短横线，长度 2-64");
        for (Map<String, Object> folder : folders)
            if (code.equalsIgnoreCase(text(folder.get("folderCode")))) throw new ServiceException("文件夹编码已存在");
        Map<String, Object> folder = new LinkedHashMap<>();
        folder.put("scope", scope);
        folder.put("folderCode", code);
        folder.put("folderName", name(body.getOrDefault("folderName", body.get("groupName"))));
        folder.put("parentId", parentId);
        folder.put("sortOrder", sortOrder(body.getOrDefault("sortOrder", 0)));
        folder.put("remark", remark(body.get("remark")));
        folder.put("username", username);
        try { mapper.insertFolder(folder); }
        catch (DuplicateKeyException ex) { throw new ServiceException("文件夹编码已存在"); }
        return mapper.selectFolder(scope, number(folder.get("folderId"), "文件夹编号"), false);
    }

    @Transactional
    public int update(String scope, Map<String, Object> body, String username)
    {
        requireScope(scope);
        requireBody(body);
        Long id = positive(body.getOrDefault("folderId", body.get("groupId")), "文件夹编号");
        // 按主键顺序锁定该类型目录，防止并发移动形成父子循环。
        List<Map<String, Object>> folders = mapper.selectFolders(scope, true);
        Map<String, Object> existing = requireFolder(folders, id);
        Object suppliedCode = body.containsKey("folderCode") ? body.get("folderCode") : body.get("groupCode");
        if (suppliedCode != null && !text(suppliedCode).equals(text(existing.get("folderCode"))))
            throw new ServiceException("文件夹编码创建后不可修改");
        Long parentId = nonnegative(body.getOrDefault("parentId", existing.get("parentId")), "父文件夹编号");
        requireParent(folders, parentId);
        Map<Long, Long> parents = new HashMap<>();
        for (Map<String, Object> folder : folders)
            parents.put(number(folder.get("folderId"), "文件夹编号"), number(folder.get("parentId"), "父文件夹编号"));
        Set<Long> visited = new HashSet<>();
        for (Long cursor = parentId; cursor != 0; cursor = parents.getOrDefault(cursor, 0L))
            if (id.equals(cursor) || !visited.add(cursor)) throw new ServiceException("文件夹不能移动到自身或自己的子文件夹");
        Map<String, Object> folder = new LinkedHashMap<>(existing);
        folder.put("scope", scope);
        folder.put("folderName", name(body.getOrDefault("folderName", body.getOrDefault("groupName", existing.get("folderName")))));
        folder.put("parentId", parentId);
        folder.put("sortOrder", sortOrder(body.getOrDefault("sortOrder", existing.get("sortOrder"))));
        folder.put("remark", remark(body.getOrDefault("remark", existing.get("remark"))));
        if ("page".equals(scope) || "resource".equals(scope))
        {
            String status = text(body.getOrDefault("status", existing.getOrDefault("status", "0")));
            if (!Set.of("0", "1").contains(status)) throw new ServiceException("文件夹状态不合法");
            folder.put("status", status);
        }
        folder.put("username", username);
        return mapper.updateFolder(folder);
    }

    @Transactional
    public int delete(String scope, Long folderId)
    {
        requireScope(scope);
        positive(folderId, "文件夹编号");
        List<Map<String, Object>> folders = mapper.selectFolders(scope, true);
        Map<String, Object> folder = requireFolder(folders, folderId);
        boolean hasChildren = folders.stream().anyMatch(item -> active(item)
                && folderId.equals(number(item.get("parentId"), "父文件夹编号")));
        if (hasChildren || mapper.countMembers(scope, folderId, text(folder.get("folderCode"))) > 0)
            throw new ServiceException("文件夹下仍有内容或子文件夹，不能删除");
        return mapper.deleteFolder(scope, folderId);
    }

    @Transactional
    public int move(String scope, Map<String, Object> body, String username)
    {
        requireScope(scope);
        requireBody(body);
        String resourceType = text(body.get("resourceType"));
        if ("resource".equals(scope) && !Set.of("asset", "map").contains(resourceType))
            throw new ServiceException("资源移动必须指定合法类型 asset 或 map");
        if (!"resource".equals(scope) && !resourceType.isBlank())
            throw new ServiceException("当前数据类型不接受资源类型参数");
        if (!(body.get("ids") instanceof List<?> rawIds) || rawIds.isEmpty() || rawIds.size() > 1000)
            throw new ServiceException("请选择 1-1000 条需要移动的数据");
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(rawIds.stream().map(value -> positive(value, "数据编号")).toList()));
        ids.sort(Long::compareTo);
        Long folderId = nonnegative(body.get("folderId"), "目标文件夹编号");
        Map<String, Object> target = folderId == 0 ? null : mapper.selectFolder(scope, folderId, true);
        if (folderId > 0 && (target == null || !active(target))) throw new ServiceException("目标文件夹不存在、已停用或不属于当前数据类型");
        List<Long> existingIds = mapper.selectMemberIds(scope, ids, resourceType);
        if (existingIds.size() != ids.size() || !new HashSet<>(existingIds).containsAll(ids))
            throw new ServiceException("部分数据不存在或不可移动，已取消本次移动；系统数据源、内置地图和回收站页面不能移动");
        mapper.moveMembers(scope, ids, folderId, target == null ? "" : text(target.get("folderCode")), username, resourceType);
        return ids.size();
    }

    /** 创建/编辑对象在既有事务中锁定目标，避免并发删除造成孤立归属。 */
    public Long validateTarget(String scope, Object value)
    {
        requireScope(scope);
        Long id = nonnegative(value == null ? 0 : value, "文件夹编号");
        Map<String, Object> target = id == 0 ? null : mapper.selectFolder(scope, id, true);
        if (id > 0 && (target == null || !active(target)))
            throw new ServiceException("文件夹不存在、已停用或不属于当前数据类型");
        return id;
    }

    /** 在启动列表分页前调用；具体列表仍使用 SQL 条件完成过滤。 */
    public List<Long> filterIds(String scope, Long folderId, boolean includeChildren)
    {
        requireScope(scope);
        if (folderId == null) return null;
        if ("source".equals(scope) && folderId == -1) return List.of(-1L);
        nonnegative(folderId, "文件夹编号");
        if (folderId == 0) return List.of(0L);
        List<Map<String, Object>> folders = mapper.selectFolders(scope, false);
        if (!active(requireFolder(folders, folderId))) throw new ServiceException("文件夹已停用");
        Set<Long> result = new LinkedHashSet<>();
        result.add(folderId);
        if (includeChildren)
        {
            boolean changed;
            do
            {
                changed = false;
                for (Map<String, Object> folder : folders)
                    if (active(folder) && result.contains(number(folder.get("parentId"), "父文件夹编号")))
                        changed |= result.add(number(folder.get("folderId"), "文件夹编号"));
            } while (changed);
        }
        return new ArrayList<>(result);
    }

    public List<String> datasetFilterCodes(Long folderId, boolean includeChildren)
    {
        List<Long> ids = filterIds("dataset", folderId, includeChildren);
        if (ids == null) return null;
        if (folderId == 0) return List.of("");
        Set<Long> selected = new HashSet<>(ids);
        return mapper.selectFolders("dataset", false).stream()
                .filter(item -> selected.contains(number(item.get("folderId"), "文件夹编号")))
                .map(item -> text(item.get("folderCode"))).toList();
    }

    private Map<String, Object> requireFolder(List<Map<String, Object>> folders, Long id)
    {
        return folders.stream().filter(item -> id.equals(number(item.get("folderId"), "文件夹编号")))
                .findFirst().orElseThrow(() -> new ServiceException("文件夹不存在或不属于当前数据类型"));
    }

    private void requireParent(List<Map<String, Object>> folders, Long parentId)
    {
        if (parentId > 0 && !active(requireFolder(folders, parentId))) throw new ServiceException("父文件夹已停用");
    }

    private static boolean active(Map<String, Object> folder)
    {
        return !"1".equals(text(folder.get("status")));
    }

    private static void requireBody(Map<String, Object> body)
    {
        if (body == null) throw new ServiceException("文件夹参数不能为空");
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

    private static String name(Object value)
    {
        String name = text(value);
        if (name.isBlank() || name.length() > 100) throw new ServiceException("文件夹名称不能为空且不能超过 100 个字符");
        return name;
    }

    private static String remark(Object value)
    {
        String valueText = text(value);
        if (valueText.length() > 500) throw new ServiceException("备注不能超过 500 个字符");
        return valueText;
    }

    private static int sortOrder(Object value)
    {
        long order = number(value, "排序");
        if (order < 0 || order > Integer.MAX_VALUE) throw new ServiceException("排序必须是非负整数");
        return (int) order;
    }

    private static Long positive(Object value, String field)
    {
        Long id = nonnegative(value, field);
        if (id == 0) throw new ServiceException(field + "必须大于 0");
        return id;
    }

    private static Long nonnegative(Object value, String field)
    {
        Long id = number(value, field);
        if (id < 0) throw new ServiceException(field + "必须是非负整数；系统数据源不能移动");
        return id;
    }

    private static Long number(Object value, String field)
    {
        try
        {
            if (!text(value).matches("-?\\d+")) throw new NumberFormatException();
            return Long.valueOf(text(value));
        }
        catch (NumberFormatException ex) { throw new ServiceException(field + "必须是整数"); }
    }
}
