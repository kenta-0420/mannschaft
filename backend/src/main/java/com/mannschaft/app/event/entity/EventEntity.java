package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * イベントエンティティ。チーム・組織スコープのイベントを管理する。
 */
@Entity
@Table(name = "events")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long scheduleId;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(length = 200)
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 300)
    private String coverImageKey;

    @Column(length = 200)
    private String venueName;

    @Column(length = 500)
    private String venueAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal venueLatitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal venueLongitude;

    @Column(columnDefinition = "TEXT")
    private String venueAccessInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EventStatus status = EventStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private EventVisibility visibility = EventVisibility.MEMBERS_ONLY;

    private LocalDateTime registrationStartsAt;

    private LocalDateTime registrationEndsAt;

    private Integer maxCapacity;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isApprovalRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EventAttendanceMode attendanceMode = EventAttendanceMode.REGISTRATION;

    private Long preSurveyId;

    private Long postSurveyId;

    private Long workflowRequestId;

    @Column(length = 200)
    private String ogpTitle;

    @Column(length = 500)
    private String ogpDescription;

    @Column(length = 300)
    private String ogpImageKey;

    @Column(nullable = false)
    @Builder.Default
    private Integer registrationCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer checkinCount = 0;

    private Long createdBy;

    @Version
    private Long version;

    /**
     * F19.1 Phase 2: 投稿時の本名スナップショット。
     * 投稿者が属するチーム/組織の supporter_name_disclosure = REAL_NAME の場合のみ格納する。
     * DISPLAY_NAME モード時は NULL。
     */
    @Column(name = "author_real_name_snapshot", length = 100)
    private String authorRealNameSnapshot;

    /**
     * F19.1 Phase 2: 投稿の公開表示フラグ。
     * false の場合、公開ページ・sitemap・OGP から除外する（ログイン後の通常ビューには変化なし）。
     */
    @Column(name = "public_visible", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    @Builder.Default
    private boolean publicVisible = true;

    /**
     * F03.10 代理出席: 代理出席を許可するか。
     * TRUE のイベントのみ代理指定 API が有効になる。
     */
    @Column(name = "allow_proxy_attendance", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean allowProxyAttendance = false;

    /**
     * F03.10 代理出席: 代理人の承認不要（TRUE = 指定時に即 ACCEPTED）。
     */
    @Column(name = "is_proxy_auto_accept", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean isProxyAutoAccept = false;

    private LocalDateTime deletedAt;

    // F03.12 解散通知・リマインド
    private LocalDateTime dismissalNotificationSentAt;
    private Long dismissalNotifiedBy;

    @Column(nullable = false)
    @Builder.Default
    private Byte organizerReminderSentCount = 0;

    private LocalDateTime lastOrganizerReminderAt;

    /**
     * イベントの更新可能フィールドを一括で書き換える（部分更新）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * {@link EventEntity} は {@code @Builder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @Builder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE ではなく INSERT が走り、slug 一意制約違反で 500 になる
     * （実機で確認・本メソッド導入の動機）。よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * <p>各フィールドは「リクエスト値が非 null なら採用、null なら現値を維持」の部分更新セマンティクス。
     * slug の一意性検証・visibility 文字列の enum 解決は呼び出し側（{@code EventService}）の責務とし、
     * 本メソッドは解決済みの値を受け取る。
     *
     * @param slug                 新 slug（null なら現値維持・一意性は呼び出し側で検証済み）
     * @param subtitle             新サブタイトル
     * @param summary              新サマリ
     * @param coverImageKey        新カバー画像キー
     * @param venueName            新会場名
     * @param venueAddress         新会場住所
     * @param venueLatitude        新会場緯度
     * @param venueLongitude       新会場経度
     * @param venueAccessInfo      新アクセス情報
     * @param visibility           新公開範囲（解決済み enum・null なら現値維持）
     * @param registrationStartsAt 新登録開始日時
     * @param registrationEndsAt   新登録締切日時
     * @param maxCapacity          新定員
     * @param isApprovalRequired   新承認要否
     * @param attendanceMode       新出席管理モード
     * @param preSurveyId          新事前アンケートID
     * @param ogpTitle             新 OGP タイトル
     * @param ogpDescription       新 OGP 説明
     * @param ogpImageKey          新 OGP 画像キー
     */
    public void applyUpdate(String slug, String subtitle, String summary, String coverImageKey,
                            String venueName, String venueAddress, BigDecimal venueLatitude,
                            BigDecimal venueLongitude, String venueAccessInfo, EventVisibility visibility,
                            LocalDateTime registrationStartsAt, LocalDateTime registrationEndsAt,
                            Integer maxCapacity, Boolean isApprovalRequired, EventAttendanceMode attendanceMode,
                            Long preSurveyId, String ogpTitle, String ogpDescription, String ogpImageKey) {
        if (slug != null) {
            this.slug = slug;
        }
        if (subtitle != null) {
            this.subtitle = subtitle;
        }
        if (summary != null) {
            this.summary = summary;
        }
        if (coverImageKey != null) {
            this.coverImageKey = coverImageKey;
        }
        if (venueName != null) {
            this.venueName = venueName;
        }
        if (venueAddress != null) {
            this.venueAddress = venueAddress;
        }
        if (venueLatitude != null) {
            this.venueLatitude = venueLatitude;
        }
        if (venueLongitude != null) {
            this.venueLongitude = venueLongitude;
        }
        if (venueAccessInfo != null) {
            this.venueAccessInfo = venueAccessInfo;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        if (registrationStartsAt != null) {
            this.registrationStartsAt = registrationStartsAt;
        }
        if (registrationEndsAt != null) {
            this.registrationEndsAt = registrationEndsAt;
        }
        if (maxCapacity != null) {
            this.maxCapacity = maxCapacity;
        }
        if (isApprovalRequired != null) {
            this.isApprovalRequired = isApprovalRequired;
        }
        if (attendanceMode != null) {
            this.attendanceMode = attendanceMode;
        }
        if (preSurveyId != null) {
            this.preSurveyId = preSurveyId;
        }
        if (ogpTitle != null) {
            this.ogpTitle = ogpTitle;
        }
        if (ogpDescription != null) {
            this.ogpDescription = ogpDescription;
        }
        if (ogpImageKey != null) {
            this.ogpImageKey = ogpImageKey;
        }
    }

    /**
     * イベントを公開する。
     */
    public void publish() {
        this.status = EventStatus.PUBLISHED;
    }

    /**
     * イベントをキャンセルする。
     */
    public void cancel() {
        this.status = EventStatus.CANCELLED;
    }

    /**
     * イベントを完了にする。
     */
    public void complete() {
        this.status = EventStatus.COMPLETED;
    }

    /**
     * 参加登録を開始する。
     */
    public void openRegistration() {
        this.status = EventStatus.REGISTRATION_OPEN;
    }

    /**
     * 参加登録を締め切る。
     */
    public void closeRegistration() {
        this.status = EventStatus.REGISTRATION_CLOSED;
    }

    /**
     * 参加登録数をインクリメントする。
     */
    public void incrementRegistrationCount() {
        this.registrationCount++;
    }

    /**
     * チェックイン数をインクリメントする。
     */
    public void incrementCheckinCount() {
        this.checkinCount++;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 解散通知を送信済みとして記録する。
     *
     * @param notifiedByUserId 解散通知を送信したユーザーID
     */
    public void recordDismissal(Long notifiedByUserId) {
        this.dismissalNotificationSentAt = LocalDateTime.now();
        this.dismissalNotifiedBy = notifiedByUserId;
    }

    /**
     * 主催者向けリマインダー送信回数をインクリメントする。
     */
    public void incrementOrganizerReminder() {
        this.organizerReminderSentCount = (byte) (this.organizerReminderSentCount + 1);
        this.lastOrganizerReminderAt = LocalDateTime.now();
    }
}
