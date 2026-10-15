package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageEmergencyMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * デジタルサイネージ 緊急メッセージリポジトリ。
 */
public interface SignageEmergencyMessageRepository extends JpaRepository<SignageEmergencyMessageEntity, Long> {

    /**
     * 画面IDに紐づくアクティブな緊急メッセージを取得する。
     */
    Optional<SignageEmergencyMessageEntity> findByScreenIdAndIsActiveTrue(Long screenId);

    /**
     * 画面IDに紐づく緊急メッセージ一覧を作成日時降順で取得する。
     */
    List<SignageEmergencyMessageEntity> findByScreenIdOrderByCreatedAtDesc(Long screenId);
}
