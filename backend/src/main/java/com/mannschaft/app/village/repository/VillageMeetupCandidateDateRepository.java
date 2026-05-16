package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 寄合候補日リポジトリ（F17.1 Phase 3-β）。
 */
public interface VillageMeetupCandidateDateRepository
        extends JpaRepository<VillageMeetupCandidateDateEntity, UUID> {

    /** 寄合の候補日一覧（表示順）。 */
    List<VillageMeetupCandidateDateEntity> findByMeetupIdOrderBySortOrderAscCandidateDateAsc(UUID meetupId);

    /** 寄合内で特定日付の候補が存在するか（UNIQUE 重複検査用）。 */
    Optional<VillageMeetupCandidateDateEntity> findByMeetupIdAndCandidateDate(UUID meetupId, LocalDate candidateDate);
}
