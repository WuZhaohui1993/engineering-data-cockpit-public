package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 数据管理目录；scope 在服务端白名单校验，SQL 不拼接表名。 */
public interface DashboardDataFolderMapper
{
    List<Map<String, Object>> selectFolders(@Param("scope") String scope, @Param("lock") boolean lock);

    Map<String, Object> selectFolder(@Param("scope") String scope, @Param("folderId") Long folderId,
            @Param("lock") boolean lock);

    int insertFolder(Map<String, Object> folder);

    int updateFolder(Map<String, Object> folder);

    int deleteFolder(@Param("scope") String scope, @Param("folderId") Long folderId);

    int countMembers(@Param("scope") String scope, @Param("folderId") Long folderId,
            @Param("groupCode") String groupCode);

    List<Long> selectMemberIds(@Param("scope") String scope, @Param("ids") List<Long> ids,
            @Param("resourceType") String resourceType);

    int moveMembers(@Param("scope") String scope, @Param("ids") List<Long> ids,
            @Param("folderId") Long folderId, @Param("groupCode") String groupCode,
            @Param("username") String username, @Param("resourceType") String resourceType);
}
