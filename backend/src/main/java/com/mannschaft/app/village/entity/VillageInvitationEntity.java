package com.mannschaft.app.village.entity;

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
import java.util.UUID;

/**
 * 非公開村への招待エンティティ（トークンハッシュ式）。
 *
 * <p>平文トークンは保持しない。DB には {@link #tokenHash}（SHA-256 hex）のみを保存し、
 * 照合は呼び出し側でハッシュ化した値で行う（{@code AuthTokenService} と同じ方式）。</p>
 *
 * <p>日時は全て {@link Instant}（起きた瞬間）で保持する。壁時計ではなく瞬間であり、
 * 番人 {@code DateTimeAndZoneGuardTest} が新規の {@code LocalDateTime} フィールドを禁じている。</p>
 *
 * <p>{@code village_id} には実FKを張らない（クロスドメイン/モジュラーモノリス方針。
 * 整合性はアプリ層で保証し、インデックスのみ張る）。</p>
 */
@Entity
@Table(name = "village_invitations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageInvitationEntity extends UuidV7Entity {

    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** トークンのSHA-256ハッシュ(hex)。平文トークンは保存してはならない。 */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** 指名制招待の宛先ユーザーID。NULLならリンク型（誰でも使える）招待。 */
    @Column(name = "target_user_id")
    private Long targetUserId;

    /** 無期限の招待を作れないよう NOT NULL とする。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 無制限の招待を作れないよう NOT NULL とする。 */
    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * 発行した村長/長老の村メンバーシップID。
     *
     * <p>{@code village_memberships.invited_by_membership_id}（既存列・BINARY(16)）と型を揃える。
     * 受諾時にそのまま引き継ぐため、user_id ではなくメンバーシップIDで保持する。</p>
     */
    @Column(name = "created_by_membership_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID createdByMembershipId;

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
        if (this.usedCount == null) {
            this.usedCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * この招待が現在利用可能かどうかを判定する。
     *
     * <p>失効理由（失効済み/期限切れ/使用回数到達のどれに該当するか）は返さない。
     * 理由を呼び出し元が区別できる形にすると、招待の存在や状態を推測できてしまう
     * 「存在オラクル」になり得るため、可否を表す boolean 1個にのみ集約する。</p>
     *
     * <p>基準時刻は<b>引数で受け取る</b>。内部で {@code Instant.now()} を呼ぶと
     * 「期限ちょうどの瞬間」を境界として検証できず、テストが実行速度に左右される
     * （実際に負荷時のみ落ちる flaky を生んでいた）。</p>
     *
     * @param now 判定の基準時刻（呼び出し元が {@code Clock} から得る）
     */
    public boolean isUsable(Instant now) {
        if (this.revokedAt != null) {
            return false;
        }
        if (this.expiresAt != null && this.expiresAt.isBefore(now)) {
            return false;
        }
        if (this.usedCount != null && this.maxUses != null && this.usedCount >= this.maxUses) {
            return false;
        }
        return true;
    }
}
