package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F20.1: エンタイトルメントリポジトリ（{@code entitlements}・権利の真実源）。
 *
 * <p>{@code organization_id} NULL 許容のため
 * {@link AbstractTenantAwareRepository} を継承する（escrow 前例・設計書 01 §0 / §3.2）。</p>
 *
 * <p>{@link #existsActiveGrant} が {@code idx_ent_lookup (scope_kind, scope_id, feature_key,
 * valid_until)} を効かせる正準の isEntitled 判定クエリ（設計書 01 §3.3）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（{@code EntitlementQueryService.isEntitled}・
 * {@code EntitlementGuard} は別部隊）。</p>
 */
public interface EntitlementRepository extends AbstractTenantAwareRepository<EntitlementEntity, UUID> {

    /**
     * 正準の isEntitled 判定クエリ（設計書 01 §3.3）。
     *
     * <p>{@code idx_ent_lookup (scope_kind, scope_id, feature_key, valid_until)} を効かせる
     * 等値3列＋範囲1列の検索。{@code revoked_at} は選択率が低く INDEX に含めない（設計書どおり）。
     * 半開区間 {@code [valid_from, valid_until)}: {@code now == valid_until} は false。</p>
     */
    @Query("SELECT COUNT(e) > 0 FROM EntitlementEntity e "
            + "WHERE e.scopeKind = :scopeKind AND e.scopeId = :scopeId AND e.featureKey = :featureKey "
            + "AND e.revokedAt IS NULL "
            + "AND e.validFrom <= :now "
            + "AND (e.validUntil IS NULL OR :now < e.validUntil)")
    boolean existsActiveGrant(
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            @Param("featureKey") String featureKey,
            @Param("now") LocalDateTime now);

    /**
     * スコープ×機能の現時点で有効な権利行を全件取得する（権利サマリ EP・AC-23 の
     * {@code entitledFeatures} 合成に使用予定）。
     */
    @Query("SELECT e FROM EntitlementEntity e "
            + "WHERE e.scopeKind = :scopeKind AND e.scopeId = :scopeId "
            + "AND e.revokedAt IS NULL "
            + "AND e.validFrom <= :now "
            + "AND (e.validUntil IS NULL OR :now < e.validUntil)")
    List<EntitlementEntity> findActiveByScope(
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            @Param("now") LocalDateTime now);

    /**
     * 発行元（{@code source_kind} × {@code source_ref_id}）に紐づく未取消の権利行を取得する
     * （契約解約時の一括 revoke 対象抽出・AC-20 に使用）。
     */
    List<EntitlementEntity> findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
            EntitlementSourceKind sourceKind, UUID sourceRefId);
}
