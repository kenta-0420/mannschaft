package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.PaymentBeneficiarySettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * チーム/組織ごとの会費受益者制限設定リポジトリ。
 *
 * <p>team_id / organization_id のどちらか一方で 1 行を引く（1スコープ1行）。
 * team_id と organization_id を併せ持つ Entity ではない（DDL の CHECK で排他）ため、
 * {@code AbstractTenantAwareRepository}（organization_id 専用基底）は使用しない
 * （team スコープも扱う・payment_items 同様）。</p>
 */
public interface PaymentBeneficiarySettingRepository
        extends JpaRepository<PaymentBeneficiarySettingEntity, UUID> {

    /**
     * チームIDで受益者制限設定を取得する。
     * レコードが存在しない場合は {@link Optional#empty()} を返す。
     * 呼び出し元は empty の場合に既定値（会員のみ＝true）として扱うこと。
     *
     * @param teamId チームID
     * @return 該当チームの設定（存在しない場合は empty）
     */
    Optional<PaymentBeneficiarySettingEntity> findByTeamId(Long teamId);

    /**
     * 組織IDで受益者制限設定を取得する。
     * レコードが存在しない場合は {@link Optional#empty()} を返す。
     *
     * @param organizationId 組織ID
     * @return 該当組織の設定（存在しない場合は empty）
     */
    Optional<PaymentBeneficiarySettingEntity> findByOrganizationId(Long organizationId);

    /**
     * チームIDに対応する設定レコードが存在するか確認する。
     *
     * @param teamId チームID
     * @return 存在する場合 true
     */
    boolean existsByTeamId(Long teamId);

    /**
     * 組織IDに対応する設定レコードが存在するか確認する。
     *
     * @param organizationId 組織ID
     * @return 存在する場合 true
     */
    boolean existsByOrganizationId(Long organizationId);
}
