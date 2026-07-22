package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

/**
 * F20.3 ベータ特典: 付与メタ（{@code beta_grants}）。権利の実体は F20.1 {@code entitlements}
 * （{@code source_kind=BETA_GRANT}・{@code source_ref_id=beta_grants.id}）であり、本 Entity は
 * <b>付与メタ</b>（誰に・いつ・どの条件で・どの機能を渡したか）のみを保持する（設計書 01 §1）。
 *
 * <p><b>主キー</b>: {@link UuidV7Entity} を継承（BINARY(16)・UUIDv7・CLAUDE.md 原則 6）。
 * 時刻列は {@code UuidV7Entity} が持たないため自前定義する。</p>
 *
 * <p><b>スキーマ不変条件</b>: 二重付与防止（{@code uk_bg_scope_phase}）・grant_kind × scope_kind 整合
 * （{@code chk_bg_kind_scope}）・譲渡不可の物理固定（{@code chk_bg_not_transferable}）・フェーズ範囲
 * （{@code chk_bg_phase}）を DDL の CHECK / UNIQUE で担保する。ここではその不変条件を
 * {@link Check} / {@link UniqueConstraint} で Entity にも宣言し、テストプロファイル（ddl-auto=create・
 * Flyway 無効）でも同一の制約が生成されるようにする（Flyway DDL と二重宣言・命名一致）。</p>
 *
 * <p><b>取消は終端</b>: {@code revoked_at} をセットすると復活しない。業務無効化は常に {@code revoked_at}
 * （{@code deleted_at} は {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository}
 * 基底要求の保持列で通常運用では使わない）。</p>
 *
 * <p>設計書: docs/features/F20.3_beta_perks/01_data_model.md §1</p>
 */
@Entity
@Table(
        name = "beta_grants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bg_scope_phase",
                columnNames = {"scope_kind", "scope_id", "beta_phase"}),
        indexes = {
                @Index(name = "idx_bg_scope", columnList = "scope_kind, scope_id"),
                @Index(name = "idx_bg_review", columnList = "review_flag, review_flagged_at"),
                @Index(name = "idx_bg_phase", columnList = "beta_phase, grant_kind"),
                @Index(name = "idx_bg_org", columnList = "organization_id")
        })
@Check(name = "chk_bg_grant_kind", constraints = "grant_kind IN ('INDIVIDUAL','TEAM_ORG')")
@Check(name = "chk_bg_phase", constraints = "beta_phase BETWEEN 1 AND 4")
@Check(name = "chk_bg_scope_kind", constraints = "scope_kind IN ('USER','TEAM','ORG')")
@Check(name = "chk_bg_kind_scope", constraints =
        "(grant_kind = 'INDIVIDUAL' AND scope_kind = 'USER') OR "
                + "(grant_kind = 'TEAM_ORG' AND scope_kind IN ('TEAM','ORG'))")
@Check(name = "chk_bg_not_transferable", constraints = "transferable = FALSE")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BetaGrantEntity extends UuidV7Entity {

    /** 付与種別（INDIVIDUAL=個人 / TEAM_ORG=チーム・組織）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_kind", nullable = false, length = 12)
    private GrantKind grantKind;

    /** ベータ段階（1〜4。4=1万人規模）。 */
    @Column(name = "beta_phase", nullable = false)
    private Integer betaPhase;

    /** USER / TEAM / ORG（INDIVIDUAL は USER 固定・TEAM_ORG は TEAM/ORG）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    /** users.id / teams.id / organizations.id（論理参照・FK なし）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 付与時の実測値と閾値の焼き付け（JSON・設計書 01 §1）。 */
    @Column(name = "criteria_snapshot", nullable = false, columnDefinition = "JSON")
    private String criteriaSnapshot;

    /** 付与時アクティブ人数（TEAM_ORG のみ・INDIVIDUAL は NULL）。 */
    @Column(name = "active_member_count_snapshot")
    private Integer activeMemberCountSnapshot;

    /** 付与時に展開した feature_key 配列（JSON・FULL 構成のスナップショット）。 */
    @Column(name = "granted_feature_keys", nullable = false, columnDefinition = "JSON")
    private String grantedFeatureKeys;

    /** 譲渡可否。常に FALSE（{@code chk_bg_not_transferable} で物理固定）。 */
    @Column(name = "transferable", nullable = false)
    private boolean transferable;

    /** 審査待ちフラグ（true でも権利は有効のまま）。 */
    @Column(name = "review_flag", nullable = false)
    private boolean reviewFlag;

    /** 審査フラグ事由（review_flag=true のとき必須・アプリ層保証）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_reason", length = 32)
    private BetaReviewReason reviewReason;

    /** フラグ設定日時。 */
    @Column(name = "review_flagged_at")
    private LocalDateTime reviewFlaggedAt;

    /** 審査解決日時（問題なし）。 */
    @Column(name = "review_resolved_at")
    private LocalDateTime reviewResolvedAt;

    /** 審査解決者（シスアド userId・論理参照）。 */
    @Column(name = "review_resolved_by")
    private Long reviewResolvedBy;

    /** 取消日時（終端・復活しない）。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 取消操作者（シスアド userId。退会等のシステム取消は NULL）。 */
    @Column(name = "revoked_by")
    private Long revokedBy;

    /** 取消事由（revoked_at とセットで必須・アプリ層保証）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 64)
    private BetaRevokeReason revokeReason;

    /** 付与日時（TEAM_ORG の valid_until 起点）。 */
    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    /** 付与操作者（シスアド userId。自動付与バッチは NULL=SYSTEM）。 */
    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（通常は使わない。業務無効化は revoked_at。基底要求の保持列）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ============================================================
    // ドメインメソッド（状態遷移・設計書 01 §4）
    // ============================================================

    /** INDIVIDUAL（個人特典）か。 */
    public boolean isIndividual() {
        return this.grantKind == GrantKind.INDIVIDUAL;
    }

    /** TEAM_ORG（チーム・組織特典）か。 */
    public boolean isTeamOrg() {
        return this.grantKind == GrantKind.TEAM_ORG;
    }

    /** 取消済み（終端）か。 */
    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    /**
     * 審査待ちフラグを立てる（設計書 01 §4.2・AC-07/AC-08）。権利は有効のまま。
     *
     * @param reason フラグ事由（必須）
     * @throws IllegalStateException 取消済み grant へのフラグ（終端への操作）
     * @throws IllegalArgumentException reason が null
     */
    public void flagReview(BetaReviewReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("reviewReason は必須です");
        }
        if (isRevoked()) {
            throw new IllegalStateException("取消済みの特典は審査フラグを立てられません");
        }
        this.reviewFlag = true;
        this.reviewReason = reason;
        this.reviewFlaggedAt = LocalDateTime.now();
        // 再フラグ（resolve 後の再オーナー変更等）に備え、前回の解決情報はクリアする。
        this.reviewResolvedAt = null;
        this.reviewResolvedBy = null;
    }

    /**
     * 審査を解決する（問題なし・設計書 01 §4.2・AC-20）。フラグを下ろし解決者/日時を記録する。
     * {@code review_reason} は監査のため履歴として残す。
     *
     * @param resolverUserId 審査解決者（シスアド userId）
     * @throws IllegalStateException review_flag=false（審査待ちでない）への解決
     */
    public void resolveReview(Long resolverUserId) {
        if (!this.reviewFlag) {
            throw new IllegalStateException("審査待ちでない特典は解決できません");
        }
        this.reviewFlag = false;
        this.reviewResolvedAt = LocalDateTime.now();
        this.reviewResolvedBy = resolverUserId;
    }

    /**
     * 特典を取消す（終端・設計書 01 §4.1・AC-09）。由来 entitlements の revoke は呼び出し側サービスが
     * 同一トランザクションで行う（本メソッドは付与メタ側の状態遷移のみ）。
     *
     * @param reason         取消事由（必須）
     * @param operatorUserId 取消操作者（シスアド userId。システム取消は NULL）
     * @throws IllegalStateException 既に取消済み（二重取消）
     * @throws IllegalArgumentException reason が null
     */
    public void revoke(BetaRevokeReason reason, Long operatorUserId) {
        if (reason == null) {
            throw new IllegalArgumentException("revokeReason は必須です");
        }
        if (isRevoked()) {
            throw new IllegalStateException("既に取消済みの特典です");
        }
        this.revokedAt = LocalDateTime.now();
        this.revokeReason = reason;
        this.revokedBy = operatorUserId;
    }

    /**
     * 延長の適用可否を検証する（設計書 01 §3 / §4.1・AC-14）。
     *
     * <p>延長の実体は「新しい entitlement 行の発行」であり付与メタ（本行）は不変のため、本メソッドは
     * 延長操作の<b>ドメイン不変条件のガード</b>を担う（実際の entitlement 発行は呼び出し側サービスが行う）。
     * INDIVIDUAL は無期限のため延長対象外、取消済みは操作不可、月数は 1〜24 の範囲。</p>
     *
     * @param extensionMonths 延長月数（1〜24）
     * @throws IllegalStateException    INDIVIDUAL（無期限）/ 取消済みへの延長
     * @throws IllegalArgumentException 月数が範囲外
     */
    public void extend(int extensionMonths) {
        if (isIndividual()) {
            throw new IllegalStateException("個人特典は無期限のため延長できません");
        }
        if (isRevoked()) {
            throw new IllegalStateException("取消済みの特典は延長できません");
        }
        if (extensionMonths < 1 || extensionMonths > 24) {
            throw new IllegalArgumentException("延長月数は 1〜24 の範囲で指定してください");
        }
        // 付与メタ側に持続状態は無い（valid_until は entitlements 側）。監査のため updated_at を進める。
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.grantedAt == null) {
            this.grantedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
