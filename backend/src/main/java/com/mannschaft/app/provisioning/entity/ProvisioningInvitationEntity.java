package com.mannschaft.app.provisioning.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * 柱②-1: 販促プロビジョニング招待エンティティ（トークンハッシュ式）。
 *
 * <p>PROVISIONED（承諾前の事前作成）状態で作られた組織/チームを、招待メールの承諾によって
 * ACTIVE へ引き上げるための招待。平文トークンは保持しない。DB には {@link #tokenHash}
 * （SHA-256 hex）のみを保存し、照合は呼び出し側でハッシュ化した値で行う
 * （{@code village.VillageInvitationEntity} / {@code AuthTokenService} と同じ方式）。</p>
 *
 * <p>本 PR では DDL とエンティティ骨格のみを追加する（挙動不変）。行を生成する作成 API、
 * 承諾により lifecycle_status を PROVISIONED → ACTIVE へ引き上げる承諾 API、および
 * PROVISIONED を通常導線から隠すゲートは後続 PR で同時に実装する
 * （.claude/campaigns/2026-09-01-org-governance.md 柱②）。</p>
 *
 * <p>{@code team_id} / {@code organization_id} には実FKを張らない
 * （クロスドメイン/モジュラーモノリス方針。整合性はアプリ層で保証し、インデックスのみ張る）。</p>
 *
 * <p>日時は全て {@link Instant}（起きた瞬間）で保持する。壁時計ではなく瞬間であり、
 * 番人 {@code DateTimeAndZoneGuardTest} が新規の {@code LocalDateTime} フィールドを禁じている。</p>
 */
@Entity
@Table(name = "provisioning_invitations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ProvisioningInvitationEntity extends UuidV7Entity {

    /** プロビジョニング対象がチームの場合。organizationId との XOR は DB 制約（chk_pi_scope）が担保する。 */
    @Column(name = "team_id")
    private Long teamId;

    /** プロビジョニング対象が組織の場合。teamId との XOR は DB 制約（chk_pi_scope）が担保する。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "invite_email", nullable = false, length = 255)
    private String inviteEmail;

    /** トークンのSHA-256ハッシュ(hex)。平文トークンは保存してはならない。 */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** PENDING/ACCEPTED/CANCELLED/EXPIRED。アプリ層検証（ENUM にしない）。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 無期限の招待を作れないよう NOT NULL とする。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** 承諾した user ID（FK 張らない）。 */
    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 発行者の user ID（FK 張らない）。 */
    @Column(name = "issued_by", nullable = false)
    private Long issuedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.status == null) {
            this.status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
