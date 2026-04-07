package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.UserQuickMemoSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ユーザーごとのポイっとメモ設定リポジトリ。
 */
public interface UserQuickMemoSettingsRepository extends JpaRepository<UserQuickMemoSettingsEntity, Long> {

    /**
     * ユーザーIDで設定を取得する。
     */
    Optional<UserQuickMemoSettingsEntity> findByUserId(Long userId);
}
