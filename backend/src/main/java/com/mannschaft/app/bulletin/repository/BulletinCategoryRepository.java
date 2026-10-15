package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 掲示板カテゴリリポジトリ。
 */
public interface BulletinCategoryRepository extends JpaRepository<BulletinCategoryEntity, Long> {

    /**
     * スコープごとのカテゴリ一覧を表示順で取得する。
     */
    List<BulletinCategoryEntity> findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
            ScopeType scopeType, Long scopeId);

    /**
     * スコープ内で同名のカテゴリが存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndName(ScopeType scopeType, Long scopeId, String name);

    /**
     * スコープ内で同名のカテゴリが存在するか確認する（自身を除く）。
     */
    boolean existsByScopeTypeAndScopeIdAndNameAndIdNot(ScopeType scopeType, Long scopeId, String name, Long id);

    /**
     * IDとスコープで検索する。
     */
    Optional<BulletinCategoryEntity> findByIdAndScopeTypeAndScopeId(Long id, ScopeType scopeType, Long scopeId);

    // ====================================================================
    // F17.1 村掲示板グローバル方式 — 村スコープ版（scopeId 版と対称）
    // ====================================================================

    /**
     * 村スコープのカテゴリ一覧を表示順で取得する。
     *
     * <p>{@code findByScopeTypeAndScopeIdOrderByDisplayOrderAsc} の村スコープ対称メソッド。</p>
     */
    List<BulletinCategoryEntity> findByScopeVillageIdOrderByDisplayOrderAsc(UUID scopeVillageId);

    /**
     * 村スコープ内で同名のカテゴリが存在するか確認する。
     */
    boolean existsByScopeVillageIdAndName(UUID scopeVillageId, String name);

    /**
     * 村スコープ内で同名のカテゴリが存在するか確認する（自身を除く）。
     */
    boolean existsByScopeVillageIdAndNameAndIdNot(UUID scopeVillageId, String name, Long id);

    /**
     * ID と村スコープで検索する（所有確認用）。
     */
    Optional<BulletinCategoryEntity> findByIdAndScopeVillageId(Long id, UUID scopeVillageId);
}
