package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.contact.OnlineVisibility;
import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ユーザーマスターエンティティ。認証・プロフィール情報を管理する。
 * 氏名・電話番号・郵便番号はAES-256-GCMで暗号化して保存する。
 */
@PersonalData(category = "account")
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    private String passwordHash;

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

    @Column(nullable = false, length = 50)
    private String displayName;

    /** アプリ内@ハンドル。英数字・アンダースコア・ハイフン3〜30文字。 */
    @Column(length = 30, unique = true)
    private String contactHandle;

    /** ハンドル検索許可フラグ。true=検索可能（デフォルト）。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean handleSearchable = true;

    /** 連絡先申請に承認が必要かどうか。true=承認制（デフォルト）。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean contactApprovalRequired = true;

    /** オンライン状態の公開範囲。デフォルト: NOBODY。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OnlineVisibility onlineVisibility = OnlineVisibility.NOBODY;

    @Column(length = 50)
    private String nickname2;

    @Column(nullable = false)
    private Boolean isSearchable;

    /** DM受信制限設定。誰からのDMを受け取るかを制御する。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DmReceiveFrom dmReceiveFrom = DmReceiveFrom.ANYONE;

    @Column(length = 500)
    private String avatarUrl;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String phoneNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String postalCode;

    @Column(length = 64)
    private String lastNameHash;

    @Column(length = 64)
    private String firstNameHash;

    @Column(length = 64)
    private String phoneNumberHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(nullable = false, length = 10)
    private String locale;

    /** ISO 3166-1 alpha-2 国コード（例: JP・US・DE）。カレンダー祝日表示用。NULLの場合はlocaleから推定する。 */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserStatus status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime reminderSentAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean reportingRestricted = false;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    /** 物理削除完了日時。NULLの場合は未実行。 */
    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    /**
     * ユーザーステータス
     */
    public enum UserStatus {
        PENDING_VERIFICATION,
        ACTIVE,
        FROZEN,
        ARCHIVED
    }

    /**
     * ユーザーを有効化する。
     */
    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * ユーザーを凍結する。
     */
    public void freeze() {
        this.status = UserStatus.FROZEN;
    }

    /**
     * ユーザーの凍結を解除する。
     */
    public void unfreeze() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * ユーザーをアーカイブする。
     */
    public void archive() {
        this.status = UserStatus.ARCHIVED;
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * ユーザーのアーカイブを解除する。
     */
    public void unarchive() {
        this.status = UserStatus.ACTIVE;
        this.archivedAt = null;
    }

    /**
     * 退会リクエストを処理する（論理削除）。
     */
    public void requestDeletion() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 退会リクエストを取り消す。
     */
    public void cancelDeletion() {
        this.deletedAt = null;
    }

    /**
     * 最終ログイン日時を更新する。
     */
    public void updateLastLoginAt() {
        this.lastLoginAt = LocalDateTime.now();
    }

    /**
     * 通報権限の制限状態を更新する。
     */
    public void setReportingRestricted(boolean restricted) {
        this.reportingRestricted = restricted;
    }

    /**
     * 物理削除完了日時を記録する（AccountPurgeService用）。
     */
    public void setPurgedAt(LocalDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }

    /**
     * リマインドメール送信日時を記録する（WithdrawalReminderService用）。
     */
    public void setReminderSentAt(LocalDateTime reminderSentAt) {
        this.reminderSentAt = reminderSentAt;
    }

    /**
     * DM受信制限設定を更新する。
     */
    public void updateDmReceiveFrom(DmReceiveFrom dmReceiveFrom) {
        this.dmReceiveFrom = dmReceiveFrom;
    }

    /**
     * @ハンドルを設定・変更する。
     */
    public void updateContactHandle(String contactHandle) {
        this.contactHandle = contactHandle;
    }

    /**
     * プライバシー設定を更新する。
     */
    public void updateContactPrivacy(Boolean handleSearchable, Boolean contactApprovalRequired,
                                     DmReceiveFrom dmReceiveFrom, OnlineVisibility onlineVisibility) {
        if (handleSearchable != null) this.handleSearchable = handleSearchable;
        if (contactApprovalRequired != null) this.contactApprovalRequired = contactApprovalRequired;
        if (dmReceiveFrom != null) this.dmReceiveFrom = dmReceiveFrom;
        if (onlineVisibility != null) this.onlineVisibility = onlineVisibility;
    }
}
