package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.SafetyResponseStatus;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 安否確認回答リポジトリ。
 */
public interface SafetyResponseRepository extends JpaRepository<SafetyResponseEntity, Long> {

    /**
     * 安否確認IDに紐づく全回答を取得する。
     */
    List<SafetyResponseEntity> findBySafetyCheckIdOrderByRespondedAtAsc(Long safetyCheckId);

    /**
     * 安否確認IDとユーザーIDで回答を取得する。
     */
    Optional<SafetyResponseEntity> findBySafetyCheckIdAndUserId(Long safetyCheckId, Long userId);

    /**
     * 安否確認IDに紐づく回答数を取得する。
     */
    long countBySafetyCheckId(Long safetyCheckId);

    /**
     * 安否確認IDとステータスで回答数を取得する。
     */
    long countBySafetyCheckIdAndStatus(Long safetyCheckId, SafetyResponseStatus status);

    /**
     * 安否確認IDに紐づく回答済みユーザーIDリストを取得する。
     */
    @Query("SELECT sr.userId FROM SafetyResponseEntity sr WHERE sr.safetyCheckId = :safetyCheckId")
    List<Long> findRespondedUserIdsBySafetyCheckId(@Param("safetyCheckId") Long safetyCheckId);

    /**
     * 安否確認IDとステータスで回答一覧を取得する。
     */
    List<SafetyResponseEntity> findBySafetyCheckIdAndStatusOrderByRespondedAtAsc(
            Long safetyCheckId, SafetyResponseStatus status);
}
