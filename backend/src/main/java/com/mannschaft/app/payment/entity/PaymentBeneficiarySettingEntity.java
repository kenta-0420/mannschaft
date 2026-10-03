package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * チーム/組織ごとの会費受益者制限設定エンティティ（1スコープ1行）。
 *
 * <p>会費の「受益者を会員(MEMBER)のみに限定する」フラグ（{@code beneficiaryMemberOnly}）を保持する。
 * <b>既定は ON（true）＝純 SUPPORTER を受益者から除外</b>する（マスター御裁可）。ADMIN が OFF にすれば
 * 応援者（SUPPORTER）も受益者にできる。</p>
 *
 * <p>スコープは team_id または organization_id の<b>どちらか一方のみ</b>が非 null（DDL の CHECK で保証）。
 * team_id / organization_id は teams / organizations ドメインへのクロスドメイン参照のため FK なし、
 * インデックスのみで整合性をアプリ層で保証する（アーキ原則1）。</p>
 *
 * <p>レコードが存在しないスコープは
 * {@link com.mannschaft.app.payment.service.PaymentBeneficiarySettingService#isMemberOnly(Long, Long)} が
 * 既定値（true＝会員のみ）として扱う（後方互換）。</p>
 */
@Entity
@Table(name = "payment_beneficiary_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PaymentBeneficiarySettingEntity extends UuidV7Entity {

    /** チームID（teams テーブルへのクロスドメイン参照・FK なし）。組織スコープのときは null。 */
    @Column(name = "team_id", unique = true)
    private Long teamId;

    /** 組織ID（organizations テーブルへのクロスドメイン参照・FK なし）。チームスコープのときは null。 */
    @Column(name = "organization_id", unique = true)
    private Long organizationId;

    /**
     * 受益者を会員(MEMBER)のみに限定するか。
     * <b>既定 true（会員のみ・純 SUPPORTER 除外）</b>。false にすると応援者も受益者にできる。
     */
    @Column(name = "beneficiary_member_only", nullable = false)
    @Builder.Default
    private Boolean beneficiaryMemberOnly = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 受益者制限フラグを更新する。null を渡した場合は更新しない（部分更新）。
     *
     * @param beneficiaryMemberOnly 会員のみ限定フラグ（null の場合は据え置き）
     */
    public void updateSetting(Boolean beneficiaryMemberOnly) {
        if (beneficiaryMemberOnly != null) {
            this.beneficiaryMemberOnly = beneficiaryMemberOnly;
        }
    }
}
