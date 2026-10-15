package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.AppealStatus;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 異議申立てリポジトリ。
 */
public interface ModerationAppealRepository extends JpaRepository<ModerationAppealEntity, Long> {

    /**
     * トークンで異議申立てを検索する。
     */
    Optional<ModerationAppealEntity> findByAppealToken(String appealToken);

    /**
     * ステータス別に異議申立て一覧を取得する。
     */
    Page<ModerationAppealEntity> findByStatusOrderByCreatedAtDesc(AppealStatus status, Pageable pageable);

    /**
     * 全異議申立て一覧を取得する（ページング付き）。
     */
    Page<ModerationAppealEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * ユーザーとアクションの組み合わせで重複チェックする。
     */
    boolean existsByUserIdAndActionId(Long userId, Long actionId);

    /**
     * ステータス別の件数を取得する。
     */
    long countByStatus(AppealStatus status);
}
