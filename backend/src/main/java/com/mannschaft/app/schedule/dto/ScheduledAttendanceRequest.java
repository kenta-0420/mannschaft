package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.mannschaft.app.config.jackson.LenientOffsetDateTimeDeserializer;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 予約出欠募集リクエスト（機能55 第二陣）。
 *
 * <p>予定作成時に「この時刻になったら出欠募集を開始する（出欠レコードを生成し対象メンバーへ募集通知を配信する）」
 * 予約を表す。{@link #scheduledAt} 到来時に後続バッチ（{@code ScheduleScheduledTaskBatchService}）が
 * {@code ScheduleAttendanceService.openAttendanceSolicitation(scheduleId, settings)} を呼び出す。</p>
 *
 * <p>出欠の各種設定（締切・コメント要否・最低応答ロール）は本 DTO で受け取り、
 * 予約タスクの {@code payload_json} にスナップショットとして保持する。materialize 時に
 * 予定本体（{@code schedules}）の出欠属性へ適用される（未指定項目は予定の既存値を保つ）。</p>
 *
 * <p>{@link #scheduledAt} / {@link #attendanceDeadline} はいずれも {@link OffsetDateTime}（TZ 付き）で
 * 受け取り、サービス層で Asia/Tokyo の {@link LocalDateTime} に変換して保存する。
 * これにより非 JST ユーザーが指定した絶対時刻が正確に扱われる。</p>
 *
 * <p><b>Issue #2508 早馬（欠陥A）</b>: 以前 {@link #attendanceDeadline} だけが {@link LocalDateTime}
 * 宣言だったため、FE が両フィールドをオフセット付きで送っているにもかかわらず締切だけデシリアライズに
 * 失敗し、「締切を指定すると必ず 400」という不具合になっていた。
 * {@link LenientOffsetDateTimeDeserializer} により、オフセット無しの旧形式も後方互換で受理する。</p>
 */
@Getter
@RequiredArgsConstructor
public class ScheduledAttendanceRequest {

    /** この時刻に出欠募集を開始する（TZ 付き）。 */
    @NotNull
    @Future
    private final OffsetDateTime scheduledAt;

    /**
     * 出欠回答期限（任意・TZ 付き）。materialize 時に予定本体の出欠期限へ適用される。
     * オフセット無しの旧形式は JST として解釈する（後方互換）。
     */
    @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class)
    private final OffsetDateTime attendanceDeadline;

    /** コメント要否（HIDDEN / OPTIONAL / REQUIRED。任意。{@code schedules.comment_option} に準ずる）。 */
    private final String commentOption;

    /**
     * 出欠回答の最低ロール（SUPPORTER_PLUS / MEMBER_PLUS / ADMIN_ONLY。任意。
     * {@code schedules.min_response_role} に準ずる）。
     */
    private final String minResponseRole;
}
