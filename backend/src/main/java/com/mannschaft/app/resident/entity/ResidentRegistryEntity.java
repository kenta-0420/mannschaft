package com.mannschaft.app.resident.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 居住者台帳エンティティ。
 * 氏名・連絡先はAES-256-GCMで暗号化して保存する。
 */
@Entity
@Table(name = "resident_registry")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ResidentRegistryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long dwellingUnitId;

    private Long userId;

    @Column(nullable = false, length = 20)
    private String residentType;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lastName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String firstName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String lastNameKana;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String firstNameKana;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String phone;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String emergencyContact;

    @Column(length = 64)
    private String lastNameHash;

    @Column(length = 64)
    private String firstNameHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(nullable = false)
    private LocalDate moveInDate;

    private LocalDate moveOutDate;

    private BigDecimal ownershipRatio;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    private Long verifiedBy;

    private LocalDateTime verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime deletedAt;

    // ─── F09.15 居住者死亡管理（V9.102 で追加）────────────────────────────
    /**
     * 死亡状態。デフォルト ALIVE。状態遷移時に監査ログ発火（10 年保持）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DeathStatus deathStatus = DeathStatus.ALIVE;

    /** 死亡状態の最終変更日時。 */
    private LocalDateTime deathStatusChangedAt;

    /** 死亡状態を変更した user の ID（クロスドメイン弱参照・FKなし）。 */
    private Long deathStatusChangedBy;

    /**
     * 居住実態推定スコア（0〜100）。F09.16 が ResidentActivityUpdatedEvent で更新する。
     * 本人非開示（管理者のみ閲覧可）。
     */
    private Integer presumedDeathScore;

    /** 直近アクティビティ日時のキャッシュ。F09.16 ActivitySnapshotAggregator が更新する。 */
    private LocalDateTime activityLastSeenAt;

    // ─── F09.16 居住実態管理（V9.103 で追加）─────────────────────────────
    /** 居住実態区分。デフォルト UNKNOWN。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private OccupancyStatus occupancyStatus = OccupancyStatus.UNKNOWN;

    /** 直近の年次居住実態更新日時（annual_review_responses からの派生キャッシュ）。 */
    private LocalDateTime lastAnnualReviewAt;

    /** 次回年次居住実態更新の期限日。 */
    private LocalDate annualReviewDueAt;

    /** セカンドハウス・別荘扱いフラグ（通常の見守り対象から除外）。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isSecondaryHome = false;

    /** 推定年齢（0〜200、自己申告ベース）。 */
    private Integer ageEstimated;

    // ─── F14.3 住民ライフイベント（逝去・転出）アーカイブ（V187 で追加）────────
    /**
     * 転出を記録／取り消した実行者の user_id（クロスドメイン弱参照・FKなし）。
     * death_status_changed_by と対称（§5.2.0.1）。
     */
    private Long moveOutChangedBy;

    /**
     * 転出の記録操作が行われた日時（起きた瞬間）。move_out_date（業務上の転出日）とは別の事実。
     *
     * <p>docs/architecture/datetime_policy_utc_instant_vs_wallclock.md の方針により
     * {@code Instant} を用いる（{@code LocalDateTime} は新規追加禁止）。DB は {@code DATETIME}（UTC格納）。</p>
     */
    @Column(columnDefinition = "DATETIME(3)")
    private Instant moveOutChangedAt;

    /**
     * 死亡状態を更新する（F09.15）。
     *
     * @param status      新しい死亡状態
     * @param changedBy   状態変更を行った user の ID
     */
    public void updateDeathStatus(DeathStatus status, Long changedBy) {
        this.deathStatus = status;
        this.deathStatusChangedAt = LocalDateTime.now();
        this.deathStatusChangedBy = changedBy;
    }

    /**
     * 居住実態推定スコアと直近アクティビティ日時を更新する（F09.16 ActivitySnapshotAggregator から）。
     */
    public void updatePresumedDeathScore(Integer score, LocalDateTime activityLastSeenAt) {
        this.presumedDeathScore = score;
        this.activityLastSeenAt = activityLastSeenAt;
    }

    /**
     * 居住実態区分を更新する（F09.16）。
     */
    public void updateOccupancyStatus(OccupancyStatus status, Boolean isSecondaryHome) {
        this.occupancyStatus = status;
        if (isSecondaryHome != null) {
            this.isSecondaryHome = isSecondaryHome;
        }
    }

    /**
     * 年次居住実態更新を記録する（F09.16）。
     */
    public void recordAnnualReview(LocalDateTime reviewedAt, LocalDate nextDueAt) {
        this.lastAnnualReviewAt = reviewedAt;
        this.annualReviewDueAt = nextDueAt;
    }

    /**
     * 居住者情報を更新する。
     */
    public void update(String residentType, String lastName, String firstName,
                       String lastNameKana, String firstNameKana,
                       String phone, String email, String emergencyContact,
                       LocalDate moveInDate, BigDecimal ownershipRatio,
                       Boolean isPrimary, String notes) {
        this.residentType = residentType;
        this.lastName = lastName;
        this.firstName = firstName;
        this.lastNameKana = lastNameKana;
        this.firstNameKana = firstNameKana;
        this.phone = phone;
        this.email = email;
        this.emergencyContact = emergencyContact;
        this.moveInDate = moveInDate;
        this.ownershipRatio = ownershipRatio;
        this.isPrimary = isPrimary;
        this.notes = notes;
    }

    /**
     * ブラインドインデックスを更新する。
     */
    public void updateHashes(String lastNameHash, String firstNameHash) {
        this.lastNameHash = lastNameHash;
        this.firstNameHash = firstNameHash;
    }

    /**
     * 管理者確認済みにする。
     */
    public void verify(Long verifierId) {
        this.isVerified = true;
        this.verifiedBy = verifierId;
        this.verifiedAt = LocalDateTime.now();
    }

    /**
     * 退去処理を行う。
     */
    public void moveOut(LocalDate moveOutDate) {
        this.moveOutDate = moveOutDate;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
