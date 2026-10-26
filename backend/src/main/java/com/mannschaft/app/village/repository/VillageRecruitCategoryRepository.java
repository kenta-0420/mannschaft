package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageRecruitCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ごと募集カテゴリマスタリポジトリ（F17.1 P2）。
 *
 * <p>原則7 適用外（村は organization_id を持たない全テナント横断ドメイン）。
 * 同名重複防止は partial unique が MySQL で表現できないため（V19.007 の先例）、
 * 本リポジトリの {@code existsActive*} を Service 層の判定に使う。</p>
 */
public interface VillageRecruitCategoryRepository extends JpaRepository<VillageRecruitCategoryEntity, UUID> {

    /** 村の生きているカテゴリ一覧を表示順昇順で取得する（一覧 API。論理削除は除外・AC-14c）。 */
    List<VillageRecruitCategoryEntity> findByVillageIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAsc(
            UUID villageId);

    /** ID で生きているカテゴリを取得する（IDOR チェックは Service 層で villageId 一致を検証）。 */
    Optional<VillageRecruitCategoryEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 村内に同名の生きているカテゴリが存在するかを判定する（作成時の重複チェック・AC-06〜08）。
     */
    @Query("""
            SELECT (COUNT(c) > 0) FROM VillageRecruitCategoryEntity c
            WHERE c.deletedAt IS NULL
              AND c.villageId = :villageId
              AND c.name = :name
            """)
    boolean existsActiveByVillageIdAndName(@Param("villageId") UUID villageId, @Param("name") String name);

    /**
     * 村内に同名の生きているカテゴリが存在するかを判定する（指定 ID を除外・リネーム時の重複チェック）。
     */
    @Query("""
            SELECT (COUNT(c) > 0) FROM VillageRecruitCategoryEntity c
            WHERE c.deletedAt IS NULL
              AND c.villageId = :villageId
              AND c.name = :name
              AND c.id <> :excludeId
            """)
    boolean existsActiveByVillageIdAndNameExcludingId(
            @Param("villageId") UUID villageId, @Param("name") String name, @Param("excludeId") UUID excludeId);

    /** 村内の生きているカテゴリ数を取得する（上限チェック用・AC-09）。 */
    long countByVillageIdAndDeletedAtIsNull(UUID villageId);
}
