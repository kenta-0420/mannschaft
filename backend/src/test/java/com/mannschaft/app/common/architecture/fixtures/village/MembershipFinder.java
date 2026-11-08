package com.mannschaft.app.common.architecture.fixtures.village;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;

import java.util.Optional;
import java.util.UUID;

/** 番人見本でメソッド参照 {@code repo::生述語} を受けるための関数型インタフェース。 */
@FunctionalInterface
public interface MembershipFinder {
    Optional<VillageMembershipEntity> find(UUID villageId, VillageSubjectType subjectType, Long subjectId);
}
