package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.ShoppingListItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * お買い物リストアイテムリポジトリ。
 */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItemEntity, Long> {

    List<ShoppingListItemEntity> findByListIdOrderByIsCheckedAscSortOrderAsc(Long listId);

    long countByListId(Long listId);

    @Modifying
    @Query("DELETE FROM ShoppingListItemEntity i WHERE i.listId = :listId AND i.isChecked = true")
    int deleteCheckedItems(@Param("listId") Long listId);

    @Modifying
    @Query("""
            UPDATE ShoppingListItemEntity i
            SET i.isChecked = false, i.checkedBy = NULL, i.checkedAt = NULL
            WHERE i.listId = :listId AND i.isChecked = true
            """)
    int uncheckAllItems(@Param("listId") Long listId);

    List<ShoppingListItemEntity> findByListIdOrderBySortOrderAsc(Long listId);

    @Modifying
    @Query("UPDATE ShoppingListItemEntity i SET i.assignedTo = NULL WHERE i.assignedTo = :userId")
    int clearAssignmentByUserId(@Param("userId") Long userId);
}
