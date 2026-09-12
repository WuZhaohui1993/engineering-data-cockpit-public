package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardDataFolderMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** 覆盖目录边界、跨类型隔离与移动失败时不执行任何写入。 */
public class DashboardDataFolderServiceTest
{
    @Test
    public void rejectsCrossScopeParentAndMoveTargetBeforeWriting() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("source", 10, 0, "source-a");
        fixture.folder("integration", 20, 0, "integration-a");
        reject(() -> fixture.service.create("source", Map.of("folderName", "错误父级", "parentId", 20), "test"));
        reject(() -> fixture.service.move("source", Map.of("ids", List.of(1), "folderId", 20), "test"));
        assertEquals(0, fixture.writes);
    }

    @Test
    public void rejectsPartialMissingBatchAndSystemSourceAtomically() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.members.put("source", Set.of(1L, 2L));
        reject(() -> fixture.service.move("source", Map.of("ids", List.of(1, 999), "folderId", 0), "test"));
        reject(() -> fixture.service.move("source", Map.of("ids", List.of(-1), "folderId", 0), "test"));
        reject(() -> fixture.service.move("source", Map.of("ids", List.of(1), "folderId", -1), "test"));
        assertEquals(0, fixture.writes);
    }

    @Test
    public void moveDatasetKeepsItsLegacyGroupCodeAndSupportsRoot() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("dataset", 3, 0, "legacy-business");
        fixture.members.put("dataset", Set.of(1L, 2L));
        assertEquals(2, fixture.service.move("dataset", Map.of("ids", List.of(2, 1, 2), "folderId", 3), "test"));
        assertEquals(List.of(1L, 2L), fixture.lastMove.get("ids"));
        assertEquals("legacy-business", fixture.lastMove.get("groupCode"));
        assertEquals(1, fixture.service.move("dataset", Map.of("ids", List.of(1), "folderId", 0), "test"));
        assertEquals("", fixture.lastMove.get("groupCode"));
    }

    @Test
    public void renamePreservesStableCodeAndCycleIsRejected() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("dataset", 1, 0, "stable-code");
        fixture.folder("dataset", 2, 1, "child");
        fixture.folder("dataset", 3, 2, "grandchild");
        reject(() -> fixture.service.update("dataset", Map.of("folderId", 1, "parentId", 3), "test"));
        reject(() -> fixture.service.update("dataset", Map.of("folderId", 2, "parentId", 2), "test"));
        reject(() -> fixture.service.update("dataset", Map.of("folderId", 1, "folderCode", "changed"), "test"));
        assertEquals(0, fixture.writes);
        fixture.service.update("dataset", Map.of("folderId", 1, "folderName", "新的中文名称"), "test");
        assertEquals("stable-code", fixture.row("dataset", 1L).get("folderCode"));
        assertEquals("新的中文名称", fixture.row("dataset", 1L).get("folderName"));
    }

    @Test
    public void nonemptyFolderCannotBeDeleted() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("integration", 10, 0, "root");
        fixture.folder("integration", 11, 10, "child");
        reject(() -> fixture.service.delete("integration", 10L));
        fixture.memberCounts.put(11L, 1);
        reject(() -> fixture.service.delete("integration", 11L));
        assertEquals(0, fixture.writes);
        fixture.memberCounts.put(11L, 0);
        assertEquals(1, fixture.service.delete("integration", 11L));
    }

    @Test
    public void descendantFilteringDoesNotTreatRootOrSystemFolderAsAll() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("dataset", 1, 0, "topic");
        fixture.folder("dataset", 2, 1, "detail");
        fixture.folder("dataset", 3, 2, "nested");
        fixture.folder("dataset", 4, 0, "other");
        assertEquals(List.of("topic"), fixture.service.datasetFilterCodes(1L, false));
        assertEquals(List.of("topic", "detail", "nested"), fixture.service.datasetFilterCodes(1L, true));
        assertEquals(List.of(""), fixture.service.datasetFilterCodes(0L, true));
        assertNull(fixture.service.datasetFilterCodes(null, true));
        assertEquals(List.of(-1L), fixture.service.filterIds("source", -1L, true));
        reject(() -> fixture.service.filterIds("integration", -1L, false));
        reject(() -> fixture.service.filterIds("dataset", 999L, false));
    }

    @Test
    public void generatedCodeAndStrictInputValidation() throws Exception
    {
        Fixture fixture = new Fixture();
        Map<String, Object> result = fixture.service.create("source", Map.of("folderName", "工程平台"), "test");
        assertTrue(result.get("folderCode").toString().matches("folder-[a-f0-9]{32}"));
        reject(() -> fixture.service.list("SOURCE"));
        reject(() -> fixture.service.list("source;drop table"));
        reject(() -> fixture.service.move("source", Map.of("ids", List.of(1.5), "folderId", 0), "test"));
        reject(() -> fixture.service.create("source", Map.of("folderName", " "), "test"));
        reject(() -> fixture.service.create("source", Map.of("folderName", "工程平台", "parentId", -1), "test"));
    }

    @Test
    public void resourceMoveRequiresExplicitSubtypeAndBuiltinIdsCannotMove() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.folder("resource", 8, 0, "shared-resource");
        fixture.members.put("resource", Set.of(1L));
        reject(() -> fixture.service.move("resource", Map.of("ids", List.of(1), "folderId", 8), "test"));
        reject(() -> fixture.service.move("resource", Map.of("ids", List.of(1), "folderId", 8, "resourceType", "video"), "test"));
        reject(() -> fixture.service.move("resource", Map.of("ids", List.of(-1), "folderId", 8, "resourceType", "map"), "test"));
        assertEquals(0, fixture.writes);
        fixture.service.move("resource", Map.of("ids", List.of(1), "folderId", 8, "resourceType", "map"), "test");
        assertEquals("map", fixture.lastMove.get("resourceType"));
        reject(() -> fixture.service.move("page", Map.of("ids", List.of(1), "folderId", 0, "resourceType", "asset"), "test"));
    }

    @Test
    public void disabledPageAndResourceFoldersStayUnavailableForNewMembers() throws Exception
    {
        for (String scope : List.of("page", "resource"))
        {
            Fixture fixture = new Fixture();
            fixture.folder(scope, 1, 0, "active");
            fixture.folder(scope, 2, 1, "disabled");
            fixture.row(scope, 2L).put("status", "1");
            assertEquals(1, fixture.service.list(scope).size());
            assertEquals(List.of(1L), fixture.service.filterIds(scope, 1L, true));
            reject(() -> fixture.service.validateTarget(scope, 2));
            reject(() -> fixture.service.create(scope, Map.of("folderName", "child", "parentId", 2), "test"));
            fixture.service.update(scope, Map.of("folderId", 2, "folderName", "仍然停用"), "test");
            assertEquals("1", fixture.row(scope, 2L).get("status"));
            // 旧目录仅统计启用的直接子目录，统一入口保持该规则。
            assertEquals(1, fixture.service.delete(scope, 1L));
        }
    }

    private static void reject(Runnable operation)
    {
        try { operation.run(); fail("应拒绝不合法目录操作"); }
        catch (ServiceException expected) { assertNotNull(expected.getMessage()); }
    }

    private static final class Fixture
    {
        final DashboardDataFolderService service = new DashboardDataFolderService();
        final Map<String, List<Map<String, Object>>> folders = new HashMap<>();
        final Map<String, Set<Long>> members = new HashMap<>();
        final Map<Long, Integer> memberCounts = new HashMap<>();
        Map<String, Object> lastMove;
        int writes;
        long nextId = 100;

        Fixture() throws Exception
        {
            for (String scope : List.of("dataset", "source", "integration", "page", "resource")) folders.put(scope, new ArrayList<>());
            DashboardDataFolderMapper mapper = (DashboardDataFolderMapper) Proxy.newProxyInstance(
                    DashboardDataFolderMapper.class.getClassLoader(), new Class<?>[]{DashboardDataFolderMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectFolders" -> new ArrayList<>(folders.get((String) args[0]));
                        case "selectFolder" -> row((String) args[0], (Long) args[1]);
                        case "selectMemberIds" -> ((List<?>) args[1]).stream()
                                .filter(id -> members.getOrDefault((String) args[0], Set.of()).contains(id)).toList();
                        case "countMembers" -> memberCounts.getOrDefault((Long) args[1], 0);
                        case "moveMembers" -> {
                            writes++;
                            lastMove = Map.of("scope", args[0], "ids", args[1], "folderId", args[2], "groupCode", args[3], "resourceType", args[5]);
                            yield ((List<?>) args[1]).size();
                        }
                        case "insertFolder" -> {
                            Map<String, Object> folder = cast(args[0]);
                            folder.put("folderId", nextId++);
                            folders.get(folder.get("scope")).add(new LinkedHashMap<>(folder));
                            writes++;
                            yield 1;
                        }
                        case "updateFolder" -> {
                            Map<String, Object> folder = cast(args[0]);
                            row((String) folder.get("scope"), (Long) folder.get("folderId")).putAll(folder);
                            writes++;
                            yield 1;
                        }
                        case "deleteFolder" -> {
                            folders.get(args[0]).remove(row((String) args[0], (Long) args[1]));
                            writes++;
                            yield 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Field field = DashboardDataFolderService.class.getDeclaredField("mapper");
            field.setAccessible(true);
            field.set(service, mapper);
        }

        void folder(String scope, long id, long parentId, String code)
        {
            folders.get(scope).add(new LinkedHashMap<>(Map.of("folderId", id, "folderCode", code,
                    "folderName", code, "parentId", parentId, "sortOrder", 0)));
        }

        Map<String, Object> row(String scope, Long id)
        {
            return folders.get(scope).stream().filter(item -> id.equals(item.get("folderId"))).findFirst().orElse(null);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
    }
}
