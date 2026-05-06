package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
