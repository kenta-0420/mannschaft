package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.MatchProposalDateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 応募日程候補リポジトリ。
 */
public interface MatchProposalDateRepository extends JpaRepository<MatchProposalDateEntity, Long> {

    /**
     * 応募の日程候補一覧を取得する。
     */
    List<MatchProposalDateEntity> findByProposalIdOrderByProposedDateAsc(Long proposalId);

    /**
     * 応募の日程候補数をカウントする。
     */
    long countByProposalId(Long proposalId);
}
