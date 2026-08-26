package com.mannschaft.app.resume.entity;

import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 履歴書バージョンエンティティ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §4.2
 *
 * <p>1 ユーザーが複数バージョンの履歴書を保持できる。{@code title} で識別する
 * （例:「標準」「○○社応募用」）。個人単位のドメインであり {@code organization_id} は持たない。
 *
 * <p>住所・連絡先等の個人識別情報（PII）は {@link EncryptedStringConverter} で
 * AES-256-GCM 暗号化して保存する。
 *
 * <p>楽観ロック用の {@code version} フィールドに {@link Version} を付与。
 * 一括保存（PUT /full）時の競合を検出する。
 */
@Entity
@Table(name = "resumes")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ResumeEntity extends UuidV7Entity {

    /** 元号フォーマット選択肢。 */
    public enum EraFormat {
        /** 西暦（例: 2024年）。 */
        WESTERN,
        /** 和暦（例: 令和6年）。 */
        JAPANESE
    }

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    /** 証明写真ストレージキー。R2/S3 上のパス。 */
    @Column(name = "photo_key", length = 500)
    private String photoKey;

    /** 出力時の元号フォーマット。デフォルトは西暦。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "era_format", nullable = false, length = 8)
    @Builder.Default
    private EraFormat eraFormat = EraFormat.WESTERN;

    /** 現住所（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "current_address", columnDefinition = "TEXT")
    private String currentAddress;

    /** 現住所フリガナ（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "current_address_kana", columnDefinition = "TEXT")
    private String currentAddressKana;

    /** 連絡先住所（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_address", columnDefinition = "TEXT")
    private String contactAddress;

    /** 連絡先住所フリガナ（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_address_kana", columnDefinition = "TEXT")
    private String contactAddressKana;

    /** 連絡先電話番号（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_phone", columnDefinition = "TEXT")
    private String contactPhone;

    /** 連絡先メールアドレス（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_email", columnDefinition = "TEXT")
    private String contactEmail;

    /** 志望動機。 */
    @Column(name = "motivation", columnDefinition = "TEXT")
    private String motivation;

    /** 自己PR。 */
    @Column(name = "self_pr", columnDefinition = "TEXT")
    private String selfPr;

    /** 本人希望記入欄。 */
    @Column(name = "personal_request", columnDefinition = "TEXT")
    private String personalRequest;

    /** 通勤所要時間（分）。 */
    @Column(name = "commute_minutes")
    private Short commuteMinutes;

    /** 扶養家族数（配偶者を除く）。 */
    @Column(name = "dependents_count")
    private Short dependentsCount;

    /** 配偶者の有無。 */
    @Column(name = "has_spouse")
    private Boolean hasSpouse;

    /** 配偶者の扶養義務。 */
    @Column(name = "spouse_support")
    private Boolean spouseSupport;

    /**
     * 職務要約（職務経歴書冒頭の概要テキスト）。
     * 1 履歴書につき 1 つ。自由記述テキスト。
     */
    @Column(name = "career_summary", columnDefinition = "TEXT")
    private String careerSummary;

    /**
     * 活かせる経験・知識・技術（職務経歴書の散文ブロック）。
     * {@code resume_skills} テーブルの構造化スキルリストとは別物・併存する。
     */
    @Column(name = "skills_summary", columnDefinition = "TEXT")
    private String skillsSummary;

    /** 楽観ロック用バージョン。 */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** ヘッダー情報を更新する（一括保存 / PATCH 共通）。 */
    public void updateHeader(String title, String photoKey, EraFormat eraFormat,
                             String currentAddress, String currentAddressKana,
                             String contactAddress, String contactAddressKana,
                             String contactPhone, String contactEmail,
                             String motivation, String selfPr, String personalRequest,
                             Short commuteMinutes, Short dependentsCount,
                             Boolean hasSpouse, Boolean spouseSupport,
                             String careerSummary, String skillsSummary) {
        this.title = title;
        this.photoKey = photoKey;
        this.eraFormat = eraFormat;
        this.currentAddress = currentAddress;
        this.currentAddressKana = currentAddressKana;
        this.contactAddress = contactAddress;
        this.contactAddressKana = contactAddressKana;
        this.contactPhone = contactPhone;
        this.contactEmail = contactEmail;
        this.motivation = motivation;
        this.selfPr = selfPr;
        this.personalRequest = personalRequest;
        this.commuteMinutes = commuteMinutes;
        this.dependentsCount = dependentsCount;
        this.hasSpouse = hasSpouse;
        this.spouseSupport = spouseSupport;
        this.careerSummary = careerSummary;
        this.skillsSummary = skillsSummary;
    }

    /** 証明写真キーを更新する。 */
    public void updatePhotoKey(String photoKey) {
        this.photoKey = photoKey;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
