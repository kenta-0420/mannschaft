package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F02.5 ユーザー別 行動メモ設定リポジトリ。
 *
 * <p>PK = userId。{@code findById(userId)} で取得する。
 * レコード未作成のユーザーは {@link com.mannschaft.app.actionmemo.service.ActionMemoSettingsService}
 * 側で「デフォルト値（mood_enabled = false）」と等価に扱う。</p>
 */
public interface UserActionMemoSettingsRepository extends JpaRepository<UserActionMemoSettingsEntity, Long> {

    /**
     * mood 有効カウント取得（メトリクス gauge 用）。
     */
    long countByMoodEnabledTrue();

    /**
     * ユーザー ID で明示取得（可読性のため）。
     */
    default Optional<UserActionMemoSettingsEntity> findByUserId(Long userId) {
        return findById(userId);
    }
}
