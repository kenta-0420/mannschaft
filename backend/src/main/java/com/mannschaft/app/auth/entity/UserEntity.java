package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.contact.OnlineVisibility;
import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.social.FollowListVisibility;
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
import java.util.UUID;

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

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

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

    /** フォロー一覧の公開設定。デフォルト: PUBLIC。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "follow_list_visibility", nullable = false, length = 16)
    @Builder.Default
    private FollowListVisibility followListVisibility = FollowListVisibility.PUBLIC;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    /** 物理削除完了日時。NULLの場合は未実行。 */
    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    // === ケア対象者属性（F03.12）===

    /** 生年月日（暗号化保存）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "VARBINARY(255)")
    private String birthDate;

    /** ケアカテゴリ。MINOR / ELDERLY / DISABILITY_SUPPORT / GENERAL_FAMILY。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CareCategory careCategory;

    /** ケア通知の受信フラグ。デフォルト true。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean careNotificationEnabled = true;

    /** スマートフォン・PCを持たない住民フラグ（F14.1 非デジタル住民対応）。 */
    @Column(name = "offline_only", nullable = false)
    @Builder.Default
    private Boolean offlineOnly = false;

    /** 見守り者がアカウントを代理作成した場合の作成者ユーザーID。 */
    private Long accountCreatedByWatcherUserId;

    // === 広告ターゲティング用フィールド（F09.17 AdSegmentEvaluator Phase A）===

    /** 性別（AES-256-GCM 暗号化、任意）。広告ターゲティング用。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "gender", columnDefinition = "TEXT")
    private String gender;

    /** gender の HMAC-SHA256 ブラインドインデックス。広告ターゲティング検索用。 */
    @Column(name = "gender_hash", length = 64)
    private String genderHash;

    /** 都道府県コード（AES-256-GCM 暗号化、JIS X 0401 01〜47、任意）。広告ターゲティング用。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "prefecture_code", columnDefinition = "TEXT")
    private String prefectureCode;

    /** prefecture_code の HMAC-SHA256 ブラインドインデックス。広告ターゲティング検索用。 */
    @Column(name = "prefecture_code_hash", length = 64)
    private String prefectureCodeHash;

    /** 市区町村コード（AES-256-GCM 暗号化、JIS X 0402、任意）。広告ターゲティング用。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "city_code", columnDefinition = "TEXT")
    private String cityCode;

    /** city_code の HMAC-SHA256 ブラインドインデックス。広告ターゲティング検索用。 */
    @Column(name = "city_code_hash", length = 64)
    private String cityCodeHash;

    /** birth_date の HMAC-SHA256 ブラインドインデックス。AGE_RANGE ターゲティング検索用。 */
    @Column(name = "birth_date_hash", length = 64)
    private String birthDateHash;

    /**
     * ユーザーステータス
     */
    public enum UserStatus {
        PENDING_VERIFICATION,
        ACTIVE,
        FROZEN,
        ARCHIVED,
        /** 死亡（F14.1 ライフイベント: 代理入力同意書を自動失効させる） */
        DECEASED,
        /** 転居（F14.1 ライフイベント: 代理入力同意書を自動失効させる） */
        RELOCATED
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
     * ユーザーステータスを変更する。
     * ライフイベント処理（DECEASED/RELOCATED）に使用する。
     */
    public void changeStatus(UserStatus newStatus) {
        this.status = newStatus;
        if (newStatus == UserStatus.ARCHIVED) {
            this.archivedAt = LocalDateTime.now();
        }
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
     * ユーザー退会時の匿名化処理。個人情報（PII）を消去し論理削除する。
     * 投稿・履歴・統計データは保持する（統計価値 + GDPR対応の両立）。
     *
     * <p>email は UNIQUE 制約かつ NOT NULL のため、衝突を避けるために
     * 「withdrawn-{UUID}@deleted.mannschaft.internal」形式のダミー値で上書きする。
     * contactHandle は UNIQUE かつ NULL 許容のため null にする。</p>
     */
    public void anonymize() {
        // メールアドレスは UNIQUE + NOT NULL のためダミー値で上書き（null 不可）
        this.email = "withdrawn-" + UUID.randomUUID() + "@deleted.mannschaft.internal";
        // パスワードハッシュを消去（ログイン不可にする）
        this.passwordHash = null;
        // 氏名（暗号化 PII）を固定値で上書き
        this.lastName = "退会済み";
        this.firstName = "ユーザー";
        this.lastNameKana = null;
        this.firstNameKana = null;
        // 表示名・ニックネームを匿名化
        this.displayName = "退会済みユーザー";
        this.nickname2 = null;
        // @ハンドルは UNIQUE なため null にする
        this.contactHandle = null;
        this.handleSearchable = false;
        // アバター・バナー画像を消去
        this.avatarUrl = null;
        this.bannerUrl = null;
        // 電話番号・郵便番号（暗号化 PII）を消去
        this.phoneNumber = null;
        this.postalCode = null;
        // 検索用ハッシュを消去
        this.lastNameHash = null;
        this.firstNameHash = null;
        this.phoneNumberHash = null;
        // 生年月日・ケアカテゴリ（暗号化 PII）を消去
        this.birthDate = null;
        this.careCategory = null;
        // 広告ターゲティング用 PII を消去（F09.17）
        this.gender = null;
        this.genderHash = null;
        this.prefectureCode = null;
        this.prefectureCodeHash = null;
        this.cityCode = null;
        this.cityCodeHash = null;
        this.birthDateHash = null;
        // 検索不可に設定
        this.isSearchable = false;
        // 論理削除フラグ自体は softDelete() に分離している（責任分離）。
        // CLAUDE.md「DB設計の原則 §4」で `user.anonymize(); user.softDelete();` の二段階呼出を規定。
    }

    /**
     * 論理削除（soft delete）— deleted_at にタイムスタンプを設定する。
     *
     * <p>退会フローでは {@link #anonymize()} の直後に呼び出す。冪等。</p>
     */
    public void softDelete() {
        if (this.deletedAt == null) {
            this.deletedAt = LocalDateTime.now();
        }
    }

    /**
     * 最終ログイン日時を更新する。
     */
    public void updateLastLoginAt() {
        this.lastLoginAt = LocalDateTime.now();
    }

    /**
     * パスワードハッシュを更新する（パスワードリセット用）。
     */
    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    /**
     * フォロー一覧の公開設定を更新する。
     */
    public void updateFollowListVisibility(FollowListVisibility followListVisibility) {
        this.followListVisibility = followListVisibility;
    }

    /**
     * アバター画像URLを更新する。
     */
    public void updateAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    /**
     * バナー画像URLを更新する。
     */
    public void updateBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }
}
