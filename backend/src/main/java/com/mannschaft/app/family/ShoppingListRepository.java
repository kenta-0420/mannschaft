package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * お買い物リストリポジトリ。
 */
public interface ShoppingListRepository extends JpaRepository<ShoppingListEntity, Long> {

    /**
     * チームのリスト一覧を取得する（論理削除除外）。
     */
    List<ShoppingListEntity> findByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long teamId);

    /**
     * チームのリスト一覧をステータスでフィルタして取得する。
     */
    List<ShoppingListEntity> findByTeamIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long teamId, ShoppingListStatus status);

    /**
     * チームの論理削除されていないリスト数を取得する。
     */
    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    /**
     * ID + 論理削除除外で取得する。
     */
    Optional<ShoppingListEntity> findByIdAndDeletedAtIsNull(Long id);
}
