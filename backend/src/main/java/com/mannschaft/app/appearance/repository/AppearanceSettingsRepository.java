package com.mannschaft.app.appearance.repository;

import com.mannschaft.app.appearance.entity.AppearanceSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * F11.4 外観テーマ設定 — リポジトリ。
 *
 * <p>個人帰属データ（{@code organization_id} を持たない）のため
 * {@code AbstractTenantAwareRepository}（原則 7）は適用対象外。素の
 * {@link JpaRepository} を継承する。</p>
 *
 * <p>1ユーザー1行の UNIQUE 制約を前提に {@link #findByUserId(Long)} で
 * upsert 判断を行う。</p>
 */
@Repository
public interface AppearanceSettingsRepository extends JpaRepository<AppearanceSettingsEntity, UUID> {

    /**
     * 指定ユーザーの外観設定を 1 件取得する。
     * UNIQUE KEY uq_appearance_settings_user_id により結果は最大 1 件。
     *
     * @param userId ユーザー ID
     * @return 設定エンティティ（未登録の場合は空）
     */
    Optional<AppearanceSettingsEntity> findByUserId(Long userId);
}
