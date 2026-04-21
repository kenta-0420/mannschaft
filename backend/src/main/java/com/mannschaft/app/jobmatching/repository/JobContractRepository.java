package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 求人契約リポジトリ。
 */
public interface JobContractRepository extends JpaRepository<JobContractEntity, Long> {

    /**
     * Worker のマイ契約一覧を採用確定日時の新しい順で取得する。
     */
    List<JobContractEntity> findByWorkerUserIdOrderByMatchedAtDesc(Long workerId);

    /**
     * Requester のマイ契約一覧を採用確定日時の新しい順で取得する。
     */
    List<JobContractEntity> findByRequesterUserIdOrderByMatchedAtDesc(Long requesterId);

    /**
     * 応募から契約を逆引きする（一応募 ↔ 一契約のユニーク関係）。
     */
    Optional<JobContractEntity> findByJobApplicationId(Long applicationId);
}
