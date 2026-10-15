package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.entity.PaymentBeneficiarySettingEntity;
import com.mannschaft.app.payment.repository.PaymentBeneficiarySettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チーム/組織ごとの会費受益者制限設定サービス。
 *
 * <p>会費の「受益者は会員(MEMBER)のみ」フラグの解決・更新を一本化する。
 * <b>レコードが存在しないスコープは既定 true（会員のみ・純 SUPPORTER 除外）として扱う</b>（後方互換・マスター御裁可）。
 * ADMIN が false にすれば応援者（SUPPORTER）も受益者にできる。</p>
 *
 * <p>スコープは team_id または organization_id のどちらか一方で引く（1スコープ1行）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentBeneficiarySettingService {

    private final PaymentBeneficiarySettingRepository settingRepository;

    /**
     * スコープ（team または org）の受益者制限設定を取得する。
     * レコードが存在しない場合は既定値（{@code beneficiaryMemberOnly=true}）の未永続エンティティを返す。
     *
     * <p>本メソッドは DB を書き込まない。永続化が必要な場合は
     * {@link #updateSetting(Long, Long, Boolean)} を使うこと。</p>
     *
     * @param teamId         チームID（組織スコープのときは null）
     * @param organizationId 組織ID（チームスコープのときは null）
     * @return 該当スコープの設定（存在しなければ既定値 true の未永続エンティティ）
     */
    public PaymentBeneficiarySettingEntity getOrDefault(Long teamId, Long organizationId) {
        if (teamId != null) {
            return settingRepository.findByTeamId(teamId)
                    .orElseGet(() -> PaymentBeneficiarySettingEntity.builder()
                            .teamId(teamId)
                            .build());
        }
        if (organizationId != null) {
            return settingRepository.findByOrganizationId(organizationId)
                    .orElseGet(() -> PaymentBeneficiarySettingEntity.builder()
                            .organizationId(organizationId)
                            .build());
        }
        // スコープ未指定は判定不能。既定（会員のみ＝true）の未永続エンティティを返す（fail-safe・厳格側に倒す）。
        log.warn("受益者制限設定の取得でスコープ（team/org）が未指定。既定（会員のみ）にフォールバックします。");
        return PaymentBeneficiarySettingEntity.builder().build();
    }

    /**
     * スコープ（team または org）で「受益者は会員のみ」かどうかを返す。
     * <b>レコードが存在しないスコープは true（会員のみ）を返す</b>（既定 ON・後方互換）。
     *
     * @param teamId         チームID（組織スコープのときは null）
     * @param organizationId 組織ID（チームスコープのときは null）
     * @return 会員のみに限定する場合 true（既定 true）
     */
    public boolean isMemberOnly(Long teamId, Long organizationId) {
        return Boolean.TRUE.equals(getOrDefault(teamId, organizationId).getBeneficiaryMemberOnly());
    }

    /**
     * スコープ（team または org）の受益者制限設定を更新する（upsert）。
     * レコードが存在しなければ新規作成し、存在すれば値を更新する。
     *
     * @param teamId                チームID（組織スコープのときは null）
     * @param organizationId        組織ID（チームスコープのときは null）
     * @param beneficiaryMemberOnly 会員のみ限定フラグ（null の場合は据え置き / 新規時は既定 true）
     * @return 更新後の設定エンティティ
     * @throws IllegalArgumentException team/org がいずれも null、または両方非 null のとき
     */
    @Transactional
    public PaymentBeneficiarySettingEntity updateSetting(
            Long teamId, Long organizationId, Boolean beneficiaryMemberOnly) {
        if ((teamId == null) == (organizationId == null)) {
            throw new IllegalArgumentException(
                    "team_id または organization_id のどちらか一方のみを指定してください: teamId="
                            + teamId + ", organizationId=" + organizationId);
        }

        PaymentBeneficiarySettingEntity entity = findExisting(teamId, organizationId)
                .map(existing -> {
                    existing.updateSetting(beneficiaryMemberOnly);
                    return existing;
                })
                .orElseGet(() -> {
                    @SuppressWarnings("rawtypes")
                    PaymentBeneficiarySettingEntity.PaymentBeneficiarySettingEntityBuilder builder =
                            PaymentBeneficiarySettingEntity.builder()
                                    .teamId(teamId)
                                    .organizationId(organizationId);
                    if (beneficiaryMemberOnly != null) {
                        builder.beneficiaryMemberOnly(beneficiaryMemberOnly);
                    }
                    return builder.build();
                });
        PaymentBeneficiarySettingEntity saved = settingRepository.save(entity);
        log.info("会費受益者制限設定更新: teamId={}, organizationId={}, beneficiaryMemberOnly={}",
                teamId, organizationId, saved.getBeneficiaryMemberOnly());
        return saved;
    }

    private java.util.Optional<PaymentBeneficiarySettingEntity> findExisting(Long teamId, Long organizationId) {
        if (teamId != null) {
            return settingRepository.findByTeamId(teamId);
        }
        return settingRepository.findByOrganizationId(organizationId);
    }
}
