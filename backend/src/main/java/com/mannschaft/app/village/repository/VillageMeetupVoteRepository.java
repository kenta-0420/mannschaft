package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 寄合投票リポジトリ（F17.1 Phase 3-β）。
 */
public interface VillageMeetupVoteRepository extends JpaRepository<VillageMeetupVoteEntity, UUID> {

    /** 候補日に紐づく全投票。 */
    List<VillageMeetupVoteEntity> findByCandidateDateId(UUID candidateDateId);

    /** 候補日 ID 群に紐づく全投票（集計用 IN クエリ）。 */
    List<VillageMeetupVoteEntity> findByCandidateDateIdIn(List<UUID> candidateDateIds);

    /** 候補日 × 投票者で既存投票を検索（投票変更用）。 */
    Optional<VillageMeetupVoteEntity> findByCandidateDateIdAndVoterUserId(UUID candidateDateId, Long voterUserId);
}
