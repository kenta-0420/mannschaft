package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村メンバーシップリポジトリ（F17.1 Phase 1）。
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
}
