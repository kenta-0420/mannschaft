package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村メンバーシップリポジトリ（F17.1 Phase 1）。
 *
 * <p>B1 で定義した基本クエリに加え、B3（メンバーシップ Service）で必要となる
 * 一覧ページネーション・HEADMAN 引き継ぎ用の最古参検索を追加する。</p>
 */
public interface VillageMembershipRepository extends JpaRepository<VillageMembershipEntity, UUID> {

    /** 現役メンバーシップ（leftAt IS NULL）を主体で取得。 */
    Optional<VillageMembershipEntity> findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
            UUID villageId, VillageSubjectType subjectType, Long subjectId);

    /** 指定主体が参加している全村のメンバーシップ。 */
    List<VillageMembershipEntity> findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
            VillageSubjectType subjectType, Long subjectId);

    /** 村の現役メンバー件数。 */
    long countByVillageIdAndLeftAtIsNull(UUID villageId);

    /** 村の現役メンバー一覧（ページネーション、参加日昇順）。 */
    Page<VillageMembershipEntity> findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(
            UUID villageId, Pageable pageable);

    /** 村内の指定ロールで最古参の現役メンバーを 1 件取得（HEADMAN 引き継ぎ用）。 */
    Optional<VillageMembershipEntity> findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
            UUID villageId, VillageRole role);

    /** 村内の指定ロールの現役メンバー件数（最後の HEADMAN 判定用）。 */
    long countByVillageIdAndRoleAndLeftAtIsNull(UUID villageId, VillageRole role);

    // ====================================================================
    // F17.1 Phase 1 B10 — 村内 MEMBER 検索（読み取り専用）
    // ====================================================================

    /**
     * 村内 USER 現役メンバーの user_id（subject_id）集合を返す。
     * 村内検索の MEMBER タイプで「村人だけに絞ったニックネーム」を引くために使う。
     */
    @Query("""
            SELECT m.subjectId FROM VillageMembershipEntity m
            WHERE m.villageId = :villageId
              AND m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<Long> findActiveUserSubjectIdsByVillageId(@Param("villageId") UUID villageId);

    /**
     * 全村横断で「現役の USER メンバーシップ」の subject_id 重複なし集合を返す（F17.1 Phase 3-β 巡礼バッチ用）。
     */
    @Query("""
            SELECT DISTINCT m.subjectId FROM VillageMembershipEntity m
            WHERE m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<Long> findDistinctActiveUserSubjectIds();

    /**
     * 指定ユーザーが現役所属している村のカテゴリ・村IDを参照するためのメンバーシップ取得（巡礼バッチ用）。
     */
    @Query("""
            SELECT m FROM VillageMembershipEntity m
            WHERE m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.subjectId = :userId
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<VillageMembershipEntity> findActiveUserMemberships(@Param("userId") Long userId);
}
