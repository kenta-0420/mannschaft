package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.UnflagRequestStatus;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ヤバいやつ解除申請リポジトリ。
 */
public interface YabaiUnflagRequestRepository extends JpaRepository<YabaiUnflagRequestEntity, Long> {

    /**
     * ユーザーの最新の解除申請を取得する。
     */
    Optional<YabaiUnflagRequestEntity> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ステータス別に解除申請一覧を取得する。
     */
    Page<YabaiUnflagRequestEntity> findByStatusOrderByCreatedAtDesc(UnflagRequestStatus status, Pageable pageable);

    /**
     * 全解除申請一覧を取得する（ページング付き）。
     */
    Page<YabaiUnflagRequestEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * ステータス別の件数を取得する。
     */
    long countByStatus(UnflagRequestStatus status);
}
