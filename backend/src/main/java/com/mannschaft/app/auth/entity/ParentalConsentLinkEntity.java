package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意リンクエンティティ。
 *
 * <p>未成年ユーザー（子ユーザー）の保護者に対してメールで同意確認を送り、
 * その承認／拒否状態を管理するテーブルに対応する。</p>
 *
 * <p>クロスドメイン FK は持たない（childUserId / parentUserId はユーザーの ID を
 * 値として保持するのみ。外部キー制約は設けていない）。</p>
 */
@Entity
@Table(name = "parental_consent_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ParentalConsentLinkEntity extends UuidV7Entity {

    /** 同意確認対象の未成年ユーザー ID（クロスドメイン FK ではなく値参照）*/
    @Column(name = "child_user_id", nullable = false)
    private Long childUserId;

    /**
     * 保護者として登録済みユーザーの ID（NULL = メールのみの外部保護者）。
     * 保護者がシステムに登録済みの場合のみセットされる。
     */
    @Column(name = "parent_user_id")
    private Long parentUserId;

    /** 同意確認メールを送付する保護者のメールアドレス */
    @Column(name = "parent_email", nullable = false, length = 255)
    private String parentEmail;

    /**
     * 同意確認メールに含まれるトークンのハッシュ値（SHA-256）。
     * トークン本体は DB に保存せず、ハッシュのみ保持してセキュリティを確保する。
     */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** 同意リンクの現在のステータス */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ParentalConsentLinkStatus status;

    /** トークンの有効期限（これを過ぎた PENDING は無効とみなす）*/
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 保護者が承認した日時（PENDING 以外の場合は NULL）*/
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** 保護者が拒否した日時 */
    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** 同意が取り消された日時 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 取り消しを実施したユーザーの ID（管理者 or 保護者）*/
    @Column(name = "revoked_by")
    private Long revokedBy;

    /** レコード作成日時（変更不可）*/
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** レコード最終更新日時 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------
    // ライフサイクルコールバック
    // -------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        // デフォルトステータスは PENDING
        if (this.status == null) {
            this.status = ParentalConsentLinkStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // -------------------------------------------------------------------
    // ビジネスメソッド
    // -------------------------------------------------------------------

    /**
     * 保護者が同意を承認する。
     *
     * @param parentUserId 承認した保護者のシステムユーザー ID（未登録の場合は NULL 可）
     */
    public void approve(Long parentUserId) {
        this.status = ParentalConsentLinkStatus.APPROVED;
        this.parentUserId = parentUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 保護者が同意を拒否する。
     */
    public void reject() {
        this.status = ParentalConsentLinkStatus.REJECTED;
        this.rejectedAt = LocalDateTime.now();
    }

    /**
     * 同意を取り消す（管理者または保護者による操作）。
     *
     * @param revokedBy 取り消しを実施したユーザーの ID
     */
    public void revoke(Long revokedBy) {
        this.status = ParentalConsentLinkStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revokedBy = revokedBy;
    }
}
