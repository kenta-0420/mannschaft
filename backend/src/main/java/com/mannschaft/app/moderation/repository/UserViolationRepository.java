package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.ViolationType;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ユーザー違反リポジトリ。
 */
public interface UserViolationRepository extends JpaRepository<UserViolationEntity, Long> {

    /**
     * ユーザーの有効な違反一覧を取得する。
     */
    List<UserViolationEntity> findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーの全違反一覧を取得する。
     */
    List<UserViolationEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーの有効な特定種別の違反数を取得する。
     */
    long countByUserIdAndViolationTypeAndIsActiveTrue(Long userId, ViolationType violationType);

    /**
     * ユーザーの有効な違反総数を取得する。
     */
    long countByUserIdAndIsActiveTrue(Long userId);

    /**
     * 有効な違反総数を取得する。
     */
    long countByIsActiveTrue();

    /**
     * アクションIDで違反を検索する。
     */
    UserViolationEntity findByActionId(Long actionId);
}
