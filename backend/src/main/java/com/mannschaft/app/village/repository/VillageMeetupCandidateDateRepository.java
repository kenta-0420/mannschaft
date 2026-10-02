package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 寄合候補日リポジトリ（F17.1 Phase 3-β）。
 */
public interface VillageMeetupCandidateDateRepository
        extends JpaRepository<VillageMeetupCandidateDateEntity, UUID> {

    /**
     * 寄合の候補日一覧（表示順）。
     *
     * <p>同一日の中でも時刻順に安定させるため、{@code candidateTime} を第三ソートキーに加える（#2357）。
     * 終日候補（{@code candidate_time = NULL}）は ASC で先頭に並ぶ。</p>
     */
    List<VillageMeetupCandidateDateEntity>
            findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(UUID meetupId);
}
