package com.mannschaft.app.event.event;

/**
 * F03.12 事前遅刻・欠席連絡の通知発火イベント（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code EventRsvpService#submitLateNotice} / {@code #submitAbsenceNotice} は業務トランザクション
 * の内側で本イベントを publish するだけに留める。<b>業務上の事実（ID・数値・理由コード）だけ</b>を
 * 積み、通知の文面組み立て（主催者・見守り者の解決、ロケール解決、件名/本文組み立て）は行わない。
 *
 * <p><b>Codex 独立検分 [P2]（2026-08-21）で指摘・是正</b>: 初版は主催者・見守り者ぶんの文面組み立て済み
 * {@code NotificationDeliveryRequest} のリストをイベントに積んでいたため、組み立て処理
 * （{@code eventService.findEventOrThrow} の再取得・{@code careLinkService.getActiveWatchers}・
 * {@code userLocaleCache} 解決・{@code messageSource.getMessage} 例外を伴いうる）が業務トランザクション
 * の内側に残ってしまっていた。本イベントは ID と数値のみを運び、組み立ては
 * {@code EventAdvanceNoticeNotificationListener}（{@code AFTER_COMMIT}）側で行う。
 *
 * @param eventId                      イベントID
 * @param teamId                       チームID（スコープID・actionUrl 構築用）
 * @param operatorUserId               操作者ユーザーID（本人または見守り者の userId）
 * @param targetUserId                 ケア対象者（＝連絡対象）のユーザーID
 * @param kind                         事前連絡種別（遅刻／欠席）
 * @param expectedArrivalMinutesLate   遅刻予定分数（{@code kind == LATE} のときのみ非null）
 * @param absenceReason                欠席理由（{@code kind == ABSENCE} のときのみ非null）
 */
public record EventAdvanceNoticeNotificationEvent(
        Long eventId,
        Long teamId,
        Long operatorUserId,
        Long targetUserId,
        Kind kind,
        Integer expectedArrivalMinutesLate,
        String absenceReason) {

    /** 事前連絡種別。 */
    public enum Kind {
        LATE,
        ABSENCE
    }
}
