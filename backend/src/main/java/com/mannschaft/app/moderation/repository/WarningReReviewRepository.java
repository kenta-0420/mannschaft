package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.ReReviewStatus;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WARNING再レビューリポジトリ。
 */
public interface WarningReReviewRepository extends JpaRepository<WarningReReviewEntity, Long> {

    /**
     * ステータス別に再レビュー一覧を取得する。
     */
    Page<WarningReReviewEntity> findByStatusOrderByCreatedAtDesc(ReReviewStatus status, Pageable pageable);

    /**
     * ユーザーとアクションの組み合わせで重複チェックする。
     */
    boolean existsByUserIdAndActionId(Long userId, Long actionId);

    /**
     * ステータス別の件数を取得する。
     */
    long countByStatus(ReReviewStatus status);
}
