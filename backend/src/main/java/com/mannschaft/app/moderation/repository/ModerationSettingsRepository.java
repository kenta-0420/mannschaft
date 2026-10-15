package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * モデレーション設定リポジトリ。
 */
public interface ModerationSettingsRepository extends JpaRepository<ModerationSettingsEntity, Long> {

    /**
     * 設定キーで設定を取得する。
     */
    Optional<ModerationSettingsEntity> findBySettingKey(String settingKey);
}
