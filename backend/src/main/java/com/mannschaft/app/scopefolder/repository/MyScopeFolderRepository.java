package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * マイスコープフォルダリポジトリ。
 */
public interface MyScopeFolderRepository extends JpaRepository<MyScopeFolderEntity, Long> {

    /**
     * ユーザーのスコープタイプ別フォルダ一覧を並び順で取得する（削除済み除外）。
     */
    List<MyScopeFolderEntity> findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(
            Long userId, ScopeType scopeType);

    /**
     * 指定ユーザー所有のフォルダを取得する（削除済み除外・IDOR防止）。
     */
    Optional<MyScopeFolderEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * ユーザーのスコープタイプ別フォルダ数を取得する（削除済み除外）。
     */
    long countByUserIdAndScopeTypeAndDeletedAtIsNull(Long userId, ScopeType scopeType);

    /**
     * 同名フォルダの存在チェック（作成時）。
     */
    boolean existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(
            Long userId, ScopeType scopeType, String name);

    /**
     * 同名フォルダの存在チェック（更新時：自分自身を除く）。
     */
    boolean existsByUserIdAndScopeTypeAndNameAndIdNotAndDeletedAtIsNull(
            Long userId, ScopeType scopeType, String name, Long id);

    /**
     * 「未分類」フォルダ（is_default=TRUE）を取得する。
     * F15.3 §5.2.1: lazy 生成判定に使用。
     */
    Optional<MyScopeFolderEntity> findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(
            Long userId, ScopeType scopeType);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM my_scope_folders WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserIdIncludingDeleted(@Param("userId") Long userId);
}
