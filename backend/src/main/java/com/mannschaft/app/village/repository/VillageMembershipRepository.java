package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
