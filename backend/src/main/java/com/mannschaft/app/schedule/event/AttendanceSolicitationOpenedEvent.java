package com.mannschaft.app.schedule.event;

/**
 * 出欠募集の開始通知イベント（機能55 / Issue #2990 L8）。
 *
 * <p>{@code ScheduleAttendanceService#openAttendanceSolicitation} の業務トランザクション
 * （出欠設定の適用＋出欠レコードの一括生成）の内側で publish し、
 * {@code ScheduleAttendanceSolicitationNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>是正前は何が巻き戻っていたか</h2>
 * <p>是正前は同じ {@code @Transactional} の内側で受信者ごとに
 * {@code createNotificationPreAuthorized} + {@code dispatch} を try 無しで呼んでいた。通知の
 * INSERT が 1 件でも落ちると業務トランザクションごとロールバックし、
 * <b>生成済みの出欠レコード（{@code schedule_attendances}）と、予約募集で指定された回答締切・
 * コメント要否・最低応答ロールの適用がまとめて失われていた</b>。即時経路の呼び出し元は
 * {@code ScheduleAttendanceSolicitationEventListener}（{@code AFTER_COMMIT} + {@code @Async}）で
 * あり<b>再試行されない</b>ため、出欠募集そのものが黙って行われないまま終わる。</p>
 *
 * <h2>イベントには予定 ID だけを載せる</h2>
 * <p>受信者・通知本文・スコープはすべて業務データであるため積まず、配送側が
 * {@code scheduleId} から読み直す。受信者は業務トランザクションで生成された
 * {@code schedule_attendances} の行そのもの（＝配信母集団を materialize した結果）であり、
 * コミット後に読み直せば是正前と同一の集合になる。</p>
 *
 * @param scheduleId 出欠募集を開始した予定 {@code schedules.id}
 */
public record AttendanceSolicitationOpenedEvent(Long scheduleId) {
}
