package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    /**
     * 複数の発行元に紐づく未取消の権利行を<b>1本のクエリで</b>取得する（PR6a AC-72 / AC-72b）。
     *
     * <p>退会 purge の一括解約は契約数 M をループするため、契約ごとに
     * {@link #findBySourceKindAndSourceRefIdAndRevokedAtIsNull} を呼ぶと M に比例して SQL が増える。
     * 走査対象の契約 ID をまとめて渡し、発行数・契約数に依らず定数本数に保つ。</p>
     *
     * @param sourceKinds  発行元種別（PLAN / ADDON）
     * @param sourceRefIds 発行元 ID（契約 ID）
     * @return 未取消の権利行
     */
    List<EntitlementEntity> findBySourceKindInAndSourceRefIdInAndRevokedAtIsNull(
            Collection<EntitlementSourceKind> sourceKinds, Collection<UUID> sourceRefIds);

    /**
     * 指定した権利行を<b>1本の一括 UPDATE で</b> revoke する（PR6a AC-72 / AC-72b）。
     *
     * <p>エンティティを1件ずつ書き換えて flush させると、発行数 N に比例した UPDATE が出る。
     * 一括 UPDATE は {@code @PreUpdate} を経由しないため {@code updated_at} を明示的に渡す。
     * 呼び出し元は revoke 対象の {@code feature_key} 集合を、UPDATE の<b>前に</b>読んだ行から採ること
     * （本メソッドは行を返さない）。</p>
     *
     * <p>永続化コンテキストは<b>クリアしない</b>（{@code clearAutomatically=false}）。purge 経路は
     * 同一トランザクションで契約エンティティを保持したまま処理を続けるため、ここで全体を切り離すと
     * 呼び出し元の手が滑る。読み出し済みの権利行が古いままになる点は、呼び出し元が
     * {@code feature_key} 以外を参照しないことで担保する。</p>
     *
     * @param ids           対象の権利行 ID
     * @param revokedAt     取消日時
     * @param revokedBy     取消した操作者（SYSTEM 経路は {@code null}）
     * @return 更新件数
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EntitlementEntity e SET e.revokedAt = :revokedAt, e.revokedBy = :revokedBy, "
            + "e.updatedAt = :revokedAt WHERE e.id IN :ids AND e.revokedAt IS NULL")
    int bulkRevokeByIds(@Param("ids") Collection<UUID> ids,
                        @Param("revokedAt") LocalDateTime revokedAt,
                        @Param("revokedBy") Long revokedBy);
}
