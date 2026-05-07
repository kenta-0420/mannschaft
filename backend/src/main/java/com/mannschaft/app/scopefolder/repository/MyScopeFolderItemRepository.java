package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * マイスコープフォルダアイテムリポジトリ。
 */
public interface MyScopeFolderItemRepository extends JpaRepository<MyScopeFolderItemEntity, Long> {

    /**
     * フォルダ内のアイテム一覧を並び順で取得する。
     */
    List<MyScopeFolderItemEntity> findByFolderIdOrderBySortOrder(Long folderId);

    /**
     * フォルダID一覧からアイテムを一括取得する（フォルダ一覧API用・N+1回避）。
     */
    List<MyScopeFolderItemEntity> findByFolderIdIn(List<Long> folderIds);

    /**
     * フォルダIDとスコープIDでアイテムを取得する。
     */
    Optional<MyScopeFolderItemEntity> findByFolderIdAndScopeId(Long folderId, Long scopeId);

    /**
     * フォルダIDとスコープIDの組み合わせが存在するか確認する。
     */
    boolean existsByFolderIdAndScopeId(Long folderId, Long scopeId);

    /**
     * ユーザーの全フォルダ（指定スコープタイプ）からscopeIdを検索する（1アイテム1フォルダ制約用）。
     */
    @Query("SELECT i FROM MyScopeFolderItemEntity i JOIN MyScopeFolderEntity f ON i.folderId = f.id " +
           "WHERE f.userId = :userId AND f.scopeType = :scopeType AND i.scopeId = :scopeId AND f.deletedAt IS NULL")
    Optional<MyScopeFolderItemEntity> findByUserAndScopeTypeAndScopeId(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * フォルダのアイテムを全削除する（フォルダ削除時など）。
     */
    void deleteByFolderId(Long folderId);
}
