package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.MentionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * メンションリポジトリ。
 */
public interface MentionRepository extends JpaRepository<MentionEntity, Long> {

    /**
     * 指定ユーザー宛のメンション一覧を作成日時降順で取得する。
     */
    List<MentionEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 指定ユーザー宛の未読メンション件数を返す。
     */
    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * IDとユーザーIDでメンションを取得する（権限チェック込み）。
     */
    Optional<MentionEntity> findByIdAndUserId(Long id, Long userId);
}
