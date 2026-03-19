package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * チャット・連絡先フォルダアイテムのリポジトリ。
 */
public interface ChatContactFolderItemRepository extends JpaRepository<ChatContactFolderItemEntity, Long> {

    /**
     * 指定フォルダのアイテム一覧を取得する。
     */
    List<ChatContactFolderItemEntity> findByFolderId(Long folderId);

    /**
     * アイテム種別×アイテムIDで取得する（1アイテム1フォルダの制約）。
     */
    Optional<ChatContactFolderItemEntity> findByItemTypeAndItemId(FolderItemType itemType, Long itemId);

    /**
     * アイテムをフォルダから外す。
     */
    @Modifying
    @Query("DELETE FROM ChatContactFolderItemEntity e WHERE e.itemType = :itemType AND e.itemId = :itemId")
    void deleteByItemTypeAndItemId(
            @Param("itemType") FolderItemType itemType,
            @Param("itemId") Long itemId);
}
