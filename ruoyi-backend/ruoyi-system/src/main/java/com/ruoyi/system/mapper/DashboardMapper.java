package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 轻量大屏数据访问层。
 *
 * 页面、版本和数据集配置都使用独立业务表，避免修改基础系统表。
 */
public interface DashboardMapper
{
    List<Map<String, Object>> selectPageList(@Param("keyword") String keyword,
            @Param("folderId") Long folderId, @Param("includeDeleted") boolean includeDeleted);

    List<Map<String, Object>> selectPageListByFolders(@Param("keyword") String keyword,
            @Param("folderIds") List<Long> folderIds, @Param("includeDeleted") boolean includeDeleted);

    /**
     * 查询可作为页面跳转目标的已发布页面。结果只包含启用、未回收且至少有一个
     * PUBLISHED 版本的页面；实现层会返回该页面最新的已发布版本。
     */
    List<Map<String, Object>> selectPublishedPageList(@Param("keyword") String keyword,
            @Param("folderId") Long folderId);

    List<Map<String, Object>> selectPublishedPageListByFolders(@Param("keyword") String keyword,
            @Param("folderIds") List<Long> folderIds);

    Map<String, Object> selectPageById(@Param("pageId") Long pageId);

    Map<String, Object> selectPageByCode(@Param("pageCode") String pageCode);

    int insertPage(Map<String, Object> page);

    int updatePage(Map<String, Object> page);

    int softDeletePage(@Param("pageId") Long pageId, @Param("deleteBy") String deleteBy);

    int restorePage(@Param("pageId") Long pageId, @Param("updateBy") String updateBy);

    int purgePage(@Param("pageId") Long pageId);

    int purgeRecycleBin();

    List<Map<String, Object>> selectPageRecycleList(@Param("keyword") String keyword);

    List<Map<String, Object>> selectPageFolderList();

    Map<String, Object> selectPageFolderById(@Param("folderId") Long folderId);

    Map<String, Object> selectPageFolderByCode(@Param("folderCode") String folderCode);

    int insertPageFolder(Map<String, Object> folder);

    int updatePageFolder(Map<String, Object> folder);

    int deletePageFolder(@Param("folderId") Long folderId);

    int countPagesInFolder(@Param("folderId") Long folderId);

    int countChildFolders(@Param("folderId") Long folderId);

    List<Map<String, Object>> selectRevisionList(@Param("pageId") Long pageId);

    Map<String, Object> selectRevisionById(@Param("revisionId") Long revisionId);

    int insertRevision(Map<String, Object> revision);

    int updateRevisionContent(Map<String, Object> revision);

    int updateRevisionStatus(@Param("revisionId") Long revisionId, @Param("status") String status,
            @Param("publishBy") String publishBy, @Param("publishNote") String publishNote);

    int updateCurrentRevision(@Param("pageId") Long pageId, @Param("revisionId") Long revisionId);

    List<Map<String, Object>> selectDatasetList(@Param("keyword") String keyword,
            @Param("dataType") String dataType, @Param("groupCode") String groupCode);

    List<Map<String, Object>> selectDatasetListByFolders(@Param("keyword") String keyword,
            @Param("dataType") String dataType, @Param("groupCode") String groupCode,
            @Param("folderGroupCodes") List<String> folderGroupCodes);

    Map<String, Object> selectDatasetById(@Param("datasetId") Long datasetId);

    Map<String, Object> selectDatasetByCode(@Param("datasetCode") String datasetCode);

    int insertDataset(Map<String, Object> dataset);

    int updateDataset(Map<String, Object> dataset);

    int deleteDataset(@Param("datasetId") Long datasetId);

    int updateDatasetTestResult(@Param("datasetId") Long datasetId, @Param("status") String status);

    int updateDatasetFieldSchema(@Param("datasetId") Long datasetId, @Param("fieldSchemaJson") String fieldSchemaJson);

    List<Map<String, Object>> selectDatasetGroupList();

    Map<String, Object> selectDatasetGroupById(@Param("groupId") Long groupId);

    Map<String, Object> selectDatasetGroupByCode(@Param("groupCode") String groupCode);

    int insertDatasetGroup(Map<String, Object> group);

    int updateDatasetGroup(Map<String, Object> group);

    int deleteDatasetGroup(@Param("groupId") Long groupId);

    int countDatasetsInGroup(@Param("groupCode") String groupCode);

    int countChildDatasetGroups(@Param("groupId") Long groupId);

    List<Map<String, Object>> selectResourceFolderList();

    Map<String, Object> selectResourceFolderById(@Param("folderId") Long folderId);

    Map<String, Object> selectResourceFolderByCode(@Param("folderCode") String folderCode);

    int insertResourceFolder(Map<String, Object> folder);

    int updateResourceFolder(Map<String, Object> folder);

    int deleteResourceFolder(@Param("folderId") Long folderId);

    int countAssetsInResourceFolder(@Param("folderId") Long folderId);

    int countMapsInResourceFolder(@Param("folderId") Long folderId);

    int countChildResourceFolders(@Param("folderId") Long folderId);

    List<Map<String, Object>> selectShareList(@Param("pageId") Long pageId);

    List<Map<String, Object>> selectSharePageList(@Param("shareId") Long shareId);

    List<Map<String, Object>> selectShareMemberships(@Param("shareId") Long shareId);

    Map<String, Object> selectSharePageByCode(@Param("shareId") Long shareId,
            @Param("pageCode") String pageCode);

    int insertSharePage(Map<String, Object> sharePage);

    Map<String, Object> selectShareByTokenHash(@Param("tokenHash") String tokenHash);

    Map<String, Object> selectShareForUpdate(@Param("pageId") Long pageId, @Param("shareId") Long shareId);

    Map<String, Object> selectRevisionMetadata(@Param("revisionId") Long revisionId);

    Map<String, Object> selectRuntimeRevisionMetadata(@Param("pageId") Long pageId,
            @Param("currentRevisionId") Long currentRevisionId);

    int updateShareVersionMode(@Param("pageId") Long pageId, @Param("shareId") Long shareId,
            @Param("versionMode") String versionMode, @Param("revisionId") Long revisionId);

    int updateSharePageRevision(@Param("shareId") Long shareId, @Param("pageId") Long pageId,
            @Param("revisionId") Long revisionId);

    int insertShare(Map<String, Object> share);

    int touchShare(@Param("shareId") Long shareId);

    int revokeShare(@Param("pageId") Long pageId, @Param("shareId") Long shareId);

    List<Map<String, Object>> selectDataSourceList();

    long countDataSourcesByFilters(Map<String, Object> filters);

    List<Map<String, Object>> selectDataSourcePage(Map<String, Object> filters);

    Map<String, Object> selectDataSourceById(@Param("dataSourceId") Long dataSourceId);

    Map<String, Object> selectDataSourceByCode(@Param("sourceCode") String sourceCode);

    int insertDataSource(Map<String, Object> dataSource);

    int updateDataSource(Map<String, Object> dataSource);

    int deleteDataSource(@Param("dataSourceId") Long dataSourceId);

    int countDatasetReferences(@Param("sourceCode") String sourceCode);

    List<Map<String, Object>> selectAssetList(@Param("keyword") String keyword,
            @Param("assetType") String assetType, @Param("folderId") Long folderId);

    List<Map<String, Object>> selectAssetListByFolders(@Param("keyword") String keyword,
            @Param("assetType") String assetType, @Param("folderIds") List<Long> folderIds);

    Map<String, Object> selectAssetById(@Param("assetId") Long assetId);

    Map<String, Object> selectAssetByCode(@Param("assetCode") String assetCode);

    int insertAsset(Map<String, Object> asset);

    int updateAsset(Map<String, Object> asset);

    int deleteAsset(@Param("assetId") Long assetId);

    List<Map<String, Object>> selectMapResourceList(@Param("keyword") String keyword,
            @Param("folderId") Long folderId);

    List<Map<String, Object>> selectMapResourceListByFolders(@Param("keyword") String keyword,
            @Param("folderIds") List<Long> folderIds);

    List<Map<String, Object>> selectMapResourcePage(@Param("keyword") String keyword,
            @Param("folderId") Long folderId);

    List<Map<String, Object>> selectMapResourcePageByFolders(@Param("keyword") String keyword,
            @Param("folderIds") List<Long> folderIds);

    Map<String, Object> selectMapResourceById(@Param("mapId") Long mapId);

    Map<String, Object> selectMapResourceByCode(@Param("mapCode") String mapCode);

    int insertMapResource(Map<String, Object> resource);

    int updateMapResource(Map<String, Object> resource);

    int deleteMapResource(@Param("mapId") Long mapId);
}
