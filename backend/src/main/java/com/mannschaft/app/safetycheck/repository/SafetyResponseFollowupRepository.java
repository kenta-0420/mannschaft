package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 安否確認フォローアップリポジトリ。
 */
public interface SafetyResponseFollowupRepository extends JpaRepository<SafetyResponseFollowupEntity, Long> {

    /**
     * 回答IDでフォローアップを取得する。
     */
    Optional<SafetyResponseFollowupEntity> findBySafetyResponseId(Long safetyResponseId);
}
