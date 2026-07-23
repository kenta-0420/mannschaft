package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F20.3 {@code beta_grants} リポジトリ。{@code organization_id} を保持するため
 * {@link AbstractTenantAwareRepository} を継承する（CLAUDE.md 原則 7・設計書 01 §1）。
 */
public interface BetaGrantRepository extends AbstractTenantAwareRepository<BetaGrantEntity, UUID> {

    /**
     * 同一スコープ × 同一フェーズの付与を取得する（二重付与検出・{@code uk_bg_scope_phase} と同一軸）。
     * 取消済みも含めて返す（取消は終端で同フェーズの再付与は不可・設計書 01 §1 設計判断 1）。
     */
    Optional<BetaGrantEntity> findByScopeKindAndScopeIdAndBetaPhase(
            EntitlementScopeKind scopeKind, Long scopeId, Integer betaPhase);

    /**
     * 指定スコープの有効な（未取消の）付与を取得する。
     * 退会確定時の一括取消（設計書 02 §5.1）・オーナー変更フラグ（Phase 2 保留・02 §5）の起点。
     */
    List<BetaGrantEntity> findByScopeKindAndScopeIdAndRevokedAtIsNull(
            EntitlementScopeKind scopeKind, Long scopeId);

    /**
     * 指定スコープの付与を取消済み含めて付与日時降順で取得する（利用者向け照会・設計書 02 §1）。
     * {@code /me/beta-perks} / チーム・組織照会は取消済みも {@code revokedAt} 付きで表示するため
     * {@code RevokedAtIsNull} で絞らない。
     */
    List<BetaGrantEntity> findByScopeKindAndScopeIdOrderByGrantedAtDesc(
            EntitlementScopeKind scopeKind, Long scopeId);

    /**
     * シスアド運用一覧のフィルタ検索（設計書 02 §4・{@code GET /system-admin/beta-perks/grants}）。
     * 各条件は NULL で無効化（AND 絞り込み）。
     *
     * @param reviewFlag 審査待ちフラグ（null=無視）
     * @param grantKind  付与種別（null=無視）
     * @param betaPhase  ベータ段階（null=無視）
     * @param pageable   ページング
     */
    @Query("""
            SELECT g FROM BetaGrantEntity g
             WHERE (:reviewFlag IS NULL OR g.reviewFlag = :reviewFlag)
               AND (:grantKind IS NULL OR g.grantKind = :grantKind)
               AND (:betaPhase IS NULL OR g.betaPhase = :betaPhase)
            """)
    Page<BetaGrantEntity> searchGrants(
            @Param("reviewFlag") Boolean reviewFlag,
            @Param("grantKind") GrantKind grantKind,
            @Param("betaPhase") Integer betaPhase,
            Pageable pageable);

    /**
     * シスアド運用一覧のフィルタ検索（scope 絞り込み付き・設計書 02 §4）。
     * {@link #searchGrants} に {@code scopeKind}/{@code scopeId} フィルタ（各 NULL で無効化）を加えた版。
     * 隊2 の一覧 EP は本メソッドを用いる（{@code scopeKind}/{@code scopeId} クエリを黙殺しないため）。
     *
     * @param reviewFlag 審査待ちフラグ（null=無視）
     * @param grantKind  付与種別（null=無視）
     * @param betaPhase  ベータ段階（null=無視）
     * @param scopeKind  スコープ種別（null=無視）
     * @param scopeId    スコープ ID（null=無視）
     * @param pageable   ページング
     */
    @Query("""
            SELECT g FROM BetaGrantEntity g
             WHERE (:reviewFlag IS NULL OR g.reviewFlag = :reviewFlag)
               AND (:grantKind IS NULL OR g.grantKind = :grantKind)
               AND (:betaPhase IS NULL OR g.betaPhase = :betaPhase)
               AND (:scopeKind IS NULL OR g.scopeKind = :scopeKind)
               AND (:scopeId IS NULL OR g.scopeId = :scopeId)
            """)
    Page<BetaGrantEntity> searchGrantsWithScope(
            @Param("reviewFlag") Boolean reviewFlag,
            @Param("grantKind") GrantKind grantKind,
            @Param("betaPhase") Integer betaPhase,
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            Pageable pageable);
}
