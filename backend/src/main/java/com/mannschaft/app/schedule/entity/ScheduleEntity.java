package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceGenerationStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import jakarta.persistence.Column;
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

/**
 * スケジュールエンティティ。チーム・組織・個人スコープのスケジュールを管理する。
 */
@Entity
@Table(name = "schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScheduleEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 300)
    private String location;

    private Long venueId;

    @Column(nullable = false)
    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allDay = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleVisibility visibility;

    /** カスタム公開範囲テンプレートID (F01.7)。visibility = CUSTOM_TEMPLATE の場合のみ使用 */
    @Column(name = "visibility_template_id")
    private Long visibilityTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MinViewRole minViewRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MinResponseRole minResponseRole = MinResponseRole.MEMBER_PLUS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Boolean attendanceRequired = false;

    /**
     * 出欠確認の配信母集団にサポーター（応援者）を含めるか。
     * (B) 組織→参加チーム配信 案C フェーズA 隊A で追加。
     * 既定 false（組織配信時はサポーター除外）。値を使った母集団絞り込みの配線は後続隊。
     */
    @Column(name = "include_supporters", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean includeSupporters = false;

    /**
     * 出欠確認の集計を「チームごとの内訳（by_team）」でも収集・表示するか。
     * (B) 組織→参加チーム配信 案C フェーズB（出欠のチーム別内訳）で追加。
     * 既定 false（従来挙動＝by_team は省略・全体集計のみ）。TRUE のときのみ組織出欠集計が
     * by_team を算出して返す。配下の複数チーム所属者は所属全チームへ計上されるため
     * by_team 各チームの合計は実人数（total・DISTINCT）以上になりうる（御裁可A・重複計上）。
     * @Builder.Default 必須（NULL 挿入バグ回避）。
     */
    @Column(name = "team_breakdown_enabled", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean teamBreakdownEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AttendanceGenerationStatus attendanceStatus = AttendanceGenerationStatus.READY;

    private LocalDateTime attendanceDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CommentOption commentOption = CommentOption.OPTIONAL;

    private Long eventCategoryId;

    private Long sourceScheduleId;

    private Short academicYear;

    private Long parentScheduleId;

    @Column(columnDefinition = "JSON")
    private String recurrenceRule;

    @Column(name = "linked_todo_id")
    private Long linkedTodoId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isException = false;

    @Column(length = 7)
    private String color;

    /**
     * F03.10 代理出席: 代理出席を許可するか。
     * TRUE のスケジュールのみ代理指定 API が有効になる。
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

    @Column(length = 255)
    private String googleCalendarEventId;

    /**
     * スケジュールの作成元。
     * Google カレンダーからインポートされたスケジュールを Mannschaft 作成分と区別するために使用する。
     * @Builder.Default 必須（NULL 挿入バグ回避）。
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "source", nullable = false, length = 14)
    private ScheduleSource source = ScheduleSource.MANNSCHAFT;

    /**
     * F03.15 等の外部参照 JSON（idempotency 用）。
     * 例: {"source":"F03.15","timetable_change_id":1,"personal_timetable_slot_id":2}
     */
    @Column(name = "external_ref", columnDefinition = "TEXT")
    private String externalRef;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * external_ref を更新するためのセッター（リスナー経由の冪等更新用）。
     */
    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    /**
     * リスナー側からタイトル・時刻・色などを更新するためのセッター群。
     */
    public void updateScheduleFields(String title, String description, String location,
                                      LocalDateTime startAt, LocalDateTime endAt, String color) {
        this.title = title;
        this.description = description;
        this.location = location;
        this.startAt = startAt;
        this.endAt = endAt;
        this.color = color;
    }

    /**
     * Google カレンダー同期で all_day フラグを更新する。
     * Google 側で「時刻付き予定 <-> 全日予定」が変更されたとき、
     * {@code updateScheduleFields} と組み合わせて呼び出す。
     */
    public void updateAllDay(boolean allDay) {
        this.allDay = allDay;
    }

    /**
     * 個人スケジュールのPATCH更新を適用する。
     * toBuilder() は BaseEntity の id をコピーするため UPDATE になるが、ここでは既存レコードの更新が目的。
     * null の項目はスキップ（PATCHセマンティクス）。直接フィールド変更方式を採用する。
     */
    public void applyPersonalScheduleUpdate(String title, String description, String location,
                                             LocalDateTime startAt, LocalDateTime endAt,
                                             Boolean allDay, EventType eventType, String color) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (location != null) this.location = location;
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (allDay != null) this.allDay = allDay;
        if (eventType != null) this.eventType = eventType;
        if (color != null) this.color = color;
    }

    /**
     * 予約出欠募集の materialize 時に、予約時点で指定された出欠設定を予定へ適用する
     * （機能55 / Issue #2508 欠陥B）。
     *
     * <p>各引数は <b>null = 未指定</b> を意味し、その項目は既存値のまま保つ（PATCH セマンティクス）。
     * toBuilder() は使わず直接フィールドを更新する（toBuilder 経由の更新破壊を避けるため）。</p>
     *
     * @param attendanceDeadline 出欠回答期限（JST。null なら据え置き）
     * @param commentOption      コメント要否（null なら据え置き）
     * @param minResponseRole    出欠回答の最低ロール（null なら据え置き）
     */
    public void applyAttendanceSolicitationSettings(LocalDateTime attendanceDeadline,
                                                    CommentOption commentOption,
                                                    MinResponseRole minResponseRole) {
        if (attendanceDeadline != null) this.attendanceDeadline = attendanceDeadline;
        if (commentOption != null) this.commentOption = commentOption;
        if (minResponseRole != null) this.minResponseRole = minResponseRole;
    }

    /**
     * 繰り返しスケジュールの例外フラグを立てる（THIS_ONLY更新時に使用）。
     * toBuilder() は id を引き継ぐため UPDATE になるが、このメソッドは直接フィールド変更で簡潔かつ安全に例外フラグを立てる。
     */
    public void markAsException() {
        this.isException = true;
    }

    /**
     * スケジュールをキャンセルする。
     */
    public void cancel() {
        this.status = ScheduleStatus.CANCELLED;
    }

    /**
     * スケジュールを完了にする。
     */
    public void complete() {
        this.status = ScheduleStatus.COMPLETED;
    }

    /**
     * 繰り返しルールを更新する（個人スケジュール PATCH 用）。
     * null を渡すとルールを削除（繰り返しなし）。
     */
    public void setRecurrenceRule(String recurrenceRule) {
        this.recurrenceRule = recurrenceRule;
    }

    /**
     * 繰り返しスケジュールかどうかを判定する。
     *
     * @return 繰り返しルールが設定されている場合 true
     */
    public boolean isRecurring() {
        return this.recurrenceRule != null;
    }

    /**
     * 個人スコープかどうかを判定する。
     *
     * @return userId が設定されている場合 true
     */
    public boolean isPersonal() {
        return this.userId != null;
    }

    /**
     * チームスコープかどうかを判定する。
     *
     * @return teamId が設定されている場合 true
     */
    public boolean isTeamScope() {
        return this.teamId != null;
    }

    /**
     * 組織スコープかどうかを判定する。
     *
     * @return organizationId が設定されている場合 true
     */
    public boolean isOrganizationScope() {
        return this.organizationId != null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
