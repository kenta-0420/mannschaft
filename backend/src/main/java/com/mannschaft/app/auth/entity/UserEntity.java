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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
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

    // === プライバシーポリシー同意記録（F_privacy_policy）===

    /**
     * プライバシーポリシー同意日時。
     *
     * <p>NULL の場合は未同意または旧登録（V131.001 より前に登録したアカウント）を意味する。
     * GDPR Art.7 / 個人情報保護法 準拠のため、同意タイムスタンプを保存する。</p>
     */
    @Column(name = "privacy_policy_accepted_at")
    private LocalDateTime privacyPolicyAcceptedAt;

    /**
     * プライバシーポリシー同意時のバージョン文字列（例: "1.1.0"）。
     *
     * <p>NULL の場合は旧登録（同意取得前）を意味する。
     * ポリシー改訂時に同意バージョンを比較し、再同意要否の判定に使用する。</p>
     */
    @Column(name = "privacy_policy_version", length = 20)
    private String privacyPolicyVersion;

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
     * AGE_RANGE セグメント検索用の生年（西暦）。
     *
     * <p>{@code birth_date} は AES-256-GCM 暗号化されているため SQL での範囲検索が不可能。
     * 生年のみ平文の SMALLINT として保持し、INDEX を張ることで AGE_RANGE ターゲティングを実現する。
     * F09.17 AdSegmentEvaluator Phase B で追加（V68.004）。</p>
     */
    @Column(name = "birth_year")
    private Integer birthYear;

    /**
     * F19.1 Phase 6: 個人プロフィール公開フラグ。
     *
     * <p>true に設定すると未ログイン訪問者も {@code GET /api/v1/public/users/{userId}} で
     * プロフィールと公開投稿一覧を閲覧できる。デフォルト false（非公開）。</p>
     *
     * <p>Flyway V68.006 で追加。</p>
     */
    @Column(name = "public_profile_enabled", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private boolean publicProfileEnabled = false;

    /**
     * ユーザーステータス
     */
    public enum UserStatus {
        PENDING_VERIFICATION,
        /** 保護者同意待ち（F01.9 年齢確認・保護者同意機能: 未成年ユーザーの同意取得中） */
        PENDING_PARENTAL_CONSENT,
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
     * ユーザーを保護者同意待ち状態にする（F01.9 年齢確認）。
     */
    public void pendingParentalConsent() {
        this.status = UserStatus.PENDING_PARENTAL_CONSENT;
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
        // AGE_RANGE セグメント用 birth_year を消去（F09.17 Phase B）
        this.birthYear = null;
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

    /**
     * F19.1 Phase 6: 個人プロフィール公開設定を更新する。
     */
    public void updatePublicProfileEnabled(boolean enabled) {
        this.publicProfileEnabled = enabled;
    }

    /**
     * プライバシーポリシー同意情報を記録する（F_privacy_policy）。
     *
     * <p>登録時に呼び出す。同意日時は {@code LocalDateTime.now()} を渡すこと。</p>
     *
     * @param acceptedAt 同意日時
     * @param version    同意したポリシーバージョン（例: "1.1.0"）
     */
    public void recordPrivacyPolicyConsent(LocalDateTime acceptedAt, String version) {
        this.privacyPolicyAcceptedAt = acceptedAt;
        this.privacyPolicyVersion = version;
    }

    /**
     * プロフィールの更新可能フィールドを一括で書き換える（部分更新）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * {@link UserEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE ではなく INSERT が走り、email 一意制約違反で 500 になる
     * （PR #1643 と同型の根治）。よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * <p>各引数は「既に呼び出し側で null 合体・暗号化・HMAC 計算が済んだ確定値」を受け取り、そのまま代入する。
     * 暗号化・HMAC は {@code EncryptionService} を要するため呼び出し側（{@code UserService}）の責務とする。
     *
     * @param lastName        新姓（暗号化はコンバータが担う・平文を渡す）
     * @param firstName       新名
     * @param lastNameKana    新姓カナ
     * @param firstNameKana   新名カナ
     * @param displayName     新表示名
     * @param nickname2       新ニックネーム2
     * @param isSearchable    新検索許可フラグ
     * @param avatarUrl       新アバターURL
     * @param phoneNumber     新電話番号
     * @param postalCode      新郵便番号
     * @param lastNameHash    新姓 HMAC
     * @param firstNameHash   新名 HMAC
     * @param phoneNumberHash 新電話番号 HMAC
     * @param locale          新ロケール
     * @param countryCode     新国コード
     * @param timezone        新タイムゾーン
     * @param dmReceiveFrom   新DM受信制限設定
     */
    public void applyProfileUpdate(String lastName, String firstName, String lastNameKana,
                                   String firstNameKana, String displayName, String nickname2,
                                   Boolean isSearchable, String avatarUrl, String phoneNumber,
                                   String postalCode, String lastNameHash, String firstNameHash,
                                   String phoneNumberHash, String locale, String countryCode,
                                   String timezone, DmReceiveFrom dmReceiveFrom) {
        this.lastName = lastName;
        this.firstName = firstName;
        this.lastNameKana = lastNameKana;
        this.firstNameKana = firstNameKana;
        this.displayName = displayName;
        this.nickname2 = nickname2;
        this.isSearchable = isSearchable;
        this.avatarUrl = avatarUrl;
        this.phoneNumber = phoneNumber;
        this.postalCode = postalCode;
        this.lastNameHash = lastNameHash;
        this.firstNameHash = firstNameHash;
        this.phoneNumberHash = phoneNumberHash;
        this.locale = locale;
        this.countryCode = countryCode;
        this.timezone = timezone;
        this.dmReceiveFrom = dmReceiveFrom;
    }

    /**
     * メールアドレスを更新する（メールアドレス変更確認フロー用）。
     *
     * <p>新メールアドレスの一意性検証は呼び出し側（{@code UserService}）の責務とし、
     * 本メソッドは検証済みの値を managed entity に直接代入する。{@code toBuilder()} で
     * 作り直さない理由は {@link #applyProfileUpdate} と同じ（id 欠落で INSERT 化を防ぐ）。
     */
    public void updateEmail(String email) {
        this.email = email;
    }

    /**
     * AGE_RANGE セグメント検索用の生年を更新する（F09.17 Phase B）。
     *
     * <p>{@code birth_date} が "YYYY-MM-DD" 形式の平文（復号済み）である場合に生年を抽出して保存する。
     * プロフィール更新 API で {@code birth_date} をセットする際に合わせて呼び出すこと。</p>
     *
     * @param birthYearValue 西暦年（null の場合はカラムを NULL にする）
     */
    public void updateBirthYear(Integer birthYearValue) {
        this.birthYear = birthYearValue;
    }
}
