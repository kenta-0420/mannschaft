package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュールレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PersonalScheduleResponse {

    Long               id;
    PersonalContentDto content;   // title, description, eventType, color, location
    PersonalTimeDto    time;      // startAt, endAt, allDay
    PersonalStatusDto  status;    // status, isException, parentScheduleId, recurrenceRule, googleSynced

    /**
     * 相対指定リマインダー（開始 N 分前）の分数一覧。後方互換のため維持。
     * 絶対指定リマインダーはここには載らない（{@link #detailedReminders} を参照）。
     */
    List<Integer>      reminders;

    /**
     * リマインダー詳細一覧（機能55 第三陣）。相対（remindBeforeMinutes）と絶対（remindAt）の
     * <b>両方</b>を {@code reminderKind} 付きで露出する。詳細 GET のみ populate（一覧 GET では null）。
     * 足軽3 時点で相対のみ露出だった不足を是正するためのフィールド。
     */
    List<ReminderResponse> detailedReminders;

    PersonalAuditDto   audit;     // createdAt, updatedAt, createdByDisplayName

    /**
     * 個人予定の内容。
     *
     * <p>F03.19 §3.4.1【R1 裁定】: 個人予定もカレンダー面に並ぶ以上、レイヤー色体系の外に置かない。
     * {@code listPersonalSchedules}（カレンダーが読む一覧経路）では {@code color} に
     * <b>解決済みの色</b>（レイヤー色 &gt; 予定色 &gt; 自動色）が入り、{@code colorSource} が
     * その由来を示す。詳細・作成・更新の各応答は編集フォームの入力値を壊さないため
     * {@code schedules.color} 生値のまま（{@code colorSource} は null）。</p>
     *
     * @param colorSource 色の由来（§4.3.2 の共通4値）。一覧経路のみ非 null
     */
    public record PersonalContentDto(String title, String description, String eventType, String color,
                                     String location, CalendarColorSource colorSource) {

        /** 色解決を行わない経路（詳細・作成・更新）用の後方互換コンストラクタ。 */
        public PersonalContentDto(String title, String description, String eventType, String color,
                                  String location) {
            this(title, description, eventType, color, location, null);
        }
    }

    public record PersonalTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    public record PersonalStatusDto(String status, Boolean isException, Long parentScheduleId,
                                    RecurrenceRuleDto recurrenceRule, boolean googleSynced) {
    }

    public record PersonalAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt,
                                   String createdByDisplayName) {
    }
}
