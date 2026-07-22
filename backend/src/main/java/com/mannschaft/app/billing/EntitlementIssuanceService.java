package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F20.1/F20.3 共通: エンタイトルメント（{@code entitlements}）の発行サービス。
 *
 * <p><b>抽出の意図（F20.3 設計判断①・発行元の多態化）</b>: {@code entitlements} への行 INSERT は元々
 * {@link BillingContractService} の private ロジック（PLAN/ADDON 契約前提・{@code source_ref_id}=
 * {@code billing_contracts.id}）に閉じていた。F20.3 のベータ特典は<b>契約行を作らない別の発行元</b>
 * （{@code source_kind=BETA_GRANT}・{@code source_ref_id}={@code beta_grants.id}）であり、同じ
 * 「権利行の発行」ロジックを共有する必要がある。そこで発行部分だけを本サービスに抽出し、
 * PLAN/ADDON も BETA_GRANT も同一経路で {@code entitlements} 行を発行する。</p>
 *
 * <p><b>抽出方針（挙動不変）</b>: 元の {@code issueExtracted} は「entitlements の一括 INSERT ＋ flush ＋
 * {@code uk_ent_grant} 違反の {@link EntitlementErrorCode#DUPLICATE_ENTITLEMENT} 変換」のみを担っていた。
 * 本サービスはその<b>INSERT ロジックのみ</b>を移し、{@code valid_until} を引数化して延長・有期限付与にも
 * 使えるよう一般化した。<b>キャッシュ evict は呼び出し側の責務のまま</b>（フローごとに evict 対象 feature_key
 * 集合が異なる＝作成は付与集合／変更は旧∪新／期末解約は残存集合、など。{@link BillingContractService} の
 * {@code evictAfterCommit} が AFTER_COMMIT 遅延と対象集合の算出を担う設計書 02 §8 を保つため、evict は移さない）。</p>
 *
 * <p><b>トランザクション</b>: 本サービスは {@code @Transactional} を持たない。呼び出し元（契約サービス・
 * ベータ特典サービス）の {@code @Transactional} 境界に参加し、権利発行を同一トランザクションで確定させる
 * （付与メタと権利実体の原子性・設計書 01 §3）。</p>
 */
@Service
@RequiredArgsConstructor
public class EntitlementIssuanceService {

    private final EntitlementRepository entitlementRepository;
    private final Clock clock;

    /**
     * 指定スコープに feature_key 集合の権利を発行する（{@code entitlements} 一括 INSERT）。
     *
     * <p>{@code valid_from} は発行時刻（{@code LocalDateTime.now(clock)}）。二重発行
     * （{@code uk_ent_grant} 違反）は {@link EntitlementErrorCode#DUPLICATE_ENTITLEMENT} に変換する
     * （元 {@code BillingContractService.issueEntitlements} と同一挙動・AC-21）。</p>
     *
     * @param scopeKind      USER / TEAM / ORG
     * @param scopeId        users.id / teams.id / organizations.id
     * @param organizationId テナント（ORG=scope_id / TEAM=主所属組織 / USER=NULL）
     * @param featureKeys    発行する feature_key 集合（空なら no-op）
     * @param sourceKind     発行元区分（PLAN / ADDON / BETA_GRANT）
     * @param sourceRefId    発行元行 ID（PLAN/ADDON=billing_contracts.id / BETA_GRANT=beta_grants.id）
     * @param validUntil     有効終了（含まない・半開区間）。NULL=無期限
     * @return 発行した feature_key の一覧（入力順）
     */
    public List<String> issue(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            List<String> featureKeys, EntitlementSourceKind sourceKind, UUID sourceRefId,
            LocalDateTime validUntil) {

        if (featureKeys == null || featureKeys.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<EntitlementEntity> rows = new ArrayList<>();
        List<String> issuedKeys = new ArrayList<>();
        for (String featureKey : featureKeys) {
            rows.add(EntitlementEntity.builder()
                    .scopeKind(scopeKind)
                    .scopeId(scopeId)
                    .featureKey(featureKey)
                    .sourceKind(sourceKind)
                    .sourceRefId(sourceRefId)
                    .validFrom(now)
                    .validUntil(validUntil)
                    .organizationId(organizationId)
                    .build());
            issuedKeys.add(featureKey);
        }
        try {
            entitlementRepository.saveAll(rows);
            entitlementRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // uk_ent_grant 違反（同一発行元 × 同時刻の二重発行・AC-21）。
            throw new BusinessException(EntitlementErrorCode.DUPLICATE_ENTITLEMENT, ex);
        }
        return issuedKeys;
    }
}
