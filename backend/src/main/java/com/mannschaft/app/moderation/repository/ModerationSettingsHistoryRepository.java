package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.entity.ModerationSettingsHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * モデレーション設定変更履歴リポジトリ。
 */
public interface ModerationSettingsHistoryRepository extends JpaRepository<ModerationSettingsHistoryEntity, Long> {

    /**
     * 設定キーで変更履歴を取得する。
     */
    Page<ModerationSettingsHistoryEntity> findBySettingKeyOrderByChangedAtDesc(String settingKey, Pageable pageable);

    /**
     * 全変更履歴を取得する（ページング付き）。
     */
    Page<ModerationSettingsHistoryEntity> findAllByOrderByChangedAtDesc(Pageable pageable);
}
