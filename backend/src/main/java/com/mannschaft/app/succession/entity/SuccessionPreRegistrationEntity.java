package com.mannschaft.app.succession.entity;

import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 「もしもの備え」事前登録エンティティ（F09.15 S1）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.4
 *
 * <p>区分所有者本人が生前に登録する緊急連絡先・相続人候補・遺言メモ・凍結予防口座を
 * AES-256-GCM 透過暗号化で保存する。封緘状態（SEALED / UNSEAL_REQUESTED /
 * UNSEALED / RE_SEALED）に応じて管理組合からの可視性が変化する。
 *
 * <p>1 居住者 1 事前登録（{@code resident_registry_id} + {@code deleted_at} の複合 UNIQUE）。
 */
@Entity
@Table(name = "succession_pre_registrations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class SuccessionPreRegistrationEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** SEALED / UNSEAL_REQUESTED / UNSEALED / RE_SEALED */
    @Column(name = "seal_status", nullable = false, length = 20)
    @Builder.Default
    private String sealStatus = "SEALED";

    /** 緊急連絡先 JSON 配列（氏名・続柄・連絡先・優先順位）。AES-256-GCM 暗号化。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "emergency_contacts", columnDefinition = "TEXT")
    private String emergencyContacts;

    /** 想定相続人 JSON 配列。AES-256-GCM 暗号化。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "inheritance_candidates", columnDefinition = "TEXT")
    private String inheritanceCandidates;

    /** 遺言メモ・葬儀社希望。AES-256-GCM 暗号化。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "will_memo", columnDefinition = "TEXT")
    private String willMemo;

    /** 凍結予防口座情報。AES-256-GCM 暗号化。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "frozen_account_info", columnDefinition = "TEXT")
    private String frozenAccountInfo;

    /**
     * 長期不在期間配列（推定スコアから除外する期間）。
     * 暗号化不要・JSON 平文保存。
     */
    @Column(name = "expected_absence_periods", columnDefinition = "JSON")
    private String expectedAbsencePeriods;

    @Column(name = "last_updated_by_owner_at")
    private LocalDateTime lastUpdatedByOwnerAt;

    /** 72h 自動再封予定日時（UNSEALED 時のみセット）。 */
    @Column(name = "auto_reseal_at")
    private LocalDateTime autoResealAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
        this.updatedAt = now;
        if (this.sealStatus == null) {
            this.sealStatus = "SEALED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
