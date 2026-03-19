package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * お買い物リストアイテムリポジトリ。
 */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItemEntity, Long> {

    /**
     * リストのアイテム一覧を取得する（未チェック→チェック済み順、表示順ソート）。
     */
    List<ShoppingListItemEntity> findByListIdOrderByIsCheckedAscSortOrderAsc(Long listId);

    /**
     * リストのアイテム数を取得する。
     */
    long countByListId(Long listId);

    /**
     * チェック済みアイテムを一括削除する。
     */
    @Modifying
    @Query("DELETE FROM ShoppingListItemEntity i WHERE i.listId = :listId AND i.isChecked = true")
    int deleteCheckedItems(@Param("listId") Long listId);

    /**
     * 全アイテムのチェックを一括解除する。
     */
    @Modifying
    @Query("""
            UPDATE ShoppingListItemEntity i
            SET i.isChecked = false, i.checkedBy = NULL, i.checkedAt = NULL
            WHERE i.listId = :listId AND i.isChecked = true
            """)
    int uncheckAllItems(@Param("listId") Long listId);

    /**
     * テンプレートリストのアイテム一覧を取得する（コピー元）。
     */
    List<ShoppingListItemEntity> findByListIdOrderBySortOrderAsc(Long listId);

    /**
     * 担当者を一括クリアする（メンバー脱退時用）。
     */
    @Modifying
    @Query("UPDATE ShoppingListItemEntity i SET i.assignedTo = NULL WHERE i.assignedTo = :userId")
    int clearAssignmentByUserId(@Param("userId") Long userId);
}
