package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F03.11 募集型予約: 参加者ステータス遷移履歴リポジトリ。
 * Phase 1 では INSERT のみ。Phase 3 で参照クエリを拡張。
 */
public interface RecruitmentParticipantHistoryRepository extends JpaRepository<RecruitmentParticipantHistoryEntity, Long> {

    List<RecruitmentParticipantHistoryEntity> findByListingIdOrderByChangedAtDesc(Long listingId);

    List<RecruitmentParticipantHistoryEntity> findByParticipantIdOrderByChangedAtDesc(Long participantId);
}
