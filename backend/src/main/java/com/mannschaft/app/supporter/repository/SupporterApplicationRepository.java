package com.mannschaft.app.supporter.repository;

import com.mannschaft.app.supporter.SupporterApplicationStatus;
import com.mannschaft.app.supporter.entity.SupporterApplicationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * サポーター申請リポジトリ。
 */
public interface SupporterApplicationRepository extends JpaRepository<SupporterApplicationEntity, Long> {

    /** スコープの全申請をページング取得（全ステータス） */
    Page<SupporterApplicationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /** ユーザーのPEND申請を取得 */
    Optional<SupporterApplicationEntity> findByScopeTypeAndScopeIdAndUserIdAndStatus(
            String scopeType, Long scopeId, Long userId, SupporterApplicationStatus status);

    /** ユーザーの申請（ステータス問わず）を取得 */
    Optional<SupporterApplicationEntity> findByScopeTypeAndScopeIdAndUserId(
            String scopeType, Long scopeId, Long userId);

    /** ユーザーがPEND申請中かどうか確認 */
    boolean existsByScopeTypeAndScopeIdAndUserIdAndStatus(
            String scopeType, Long scopeId, Long userId, SupporterApplicationStatus status);
}
