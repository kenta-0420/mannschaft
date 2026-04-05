package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.ShoppingListStatus;
import com.mannschaft.app.family.entity.ShoppingListEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * お買い物リストリポジトリ。
 */
public interface ShoppingListRepository extends JpaRepository<ShoppingListEntity, Long> {

    List<ShoppingListEntity> findByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long teamId);

    List<ShoppingListEntity> findByTeamIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long teamId, ShoppingListStatus status);

    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    Optional<ShoppingListEntity> findByIdAndDeletedAtIsNull(Long id);
}
