package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * F03.11 募集型予約: キャンセル記録リポジトリ (Phase 5a)。
 */
public interface RecruitmentCancellationRecordRepository extends JpaRepository<RecruitmentCancellationRecordEntity, Long> {

    /**
     * §5.2 ステップ5 申込時の未払いキャンセル料チェック。
     */
    boolean existsByUserIdAndPaymentStatusIn(Long userId, Collection<CancellationPaymentStatus> statuses);

    List<RecruitmentCancellationRecordEntity> findByUserIdOrderByCancelledAtDesc(Long userId);

    List<RecruitmentCancellationRecordEntity> findByListingIdOrderByCancelledAtDesc(Long listingId);
}
