package com.mannschaft.app.jobmatching.event;

/**
 * 求人マッチングドメインの通知発火イベント（Issue #2997 / CMP-260827-1152 第1陣）。
 *
 * <p>{@code JobApplicationService} / {@code JobContractService} / {@code JobCheckInService} は
 * 業務トランザクションの内側で本イベントを publish するだけに留める。<b>業務上の事実（ID）だけ</b>を
 * 積み、通知の文面組み立て・受信者解決・配送は {@link JobNotificationListener}
 * （{@code AFTER_COMMIT} + {@code @Async("event-pool")}）が行う。</p>
 *
 * @param kind           通知種別
 * @param applicationId  応募ID（{@link Kind#APPLIED} のみ）
 * @param contractId     契約ID（APPLIED 以外）
 * @param postingId      求人ID（APPLIED / MATCHED / COMPLETION_REPORTED）
 * @param distanceMeters 業務場所との距離（{@link Kind#GEO_ANOMALY} のみ。不明は -1）
 */
public record JobNotificationEvent(Kind kind, Long applicationId, Long contractId, Long postingId,
                                   double distanceMeters) {

    /** 通知種別。 */
    public enum Kind {
        /** 応募通知（宛先=Requester）。 */
        APPLIED,
        /** 採用通知（宛先=Worker）。 */
        MATCHED,
        /** 完了報告通知（宛先=Requester）。 */
        COMPLETION_REPORTED,
        /** チェックイン通知（宛先=Requester）。 */
        CHECKED_IN,
        /** チェックアウト通知（宛先=Requester）。 */
        CHECKED_OUT,
        /** 位置乖離通知（宛先=Requester）。 */
        GEO_ANOMALY
    }

    public static JobNotificationEvent applied(Long applicationId, Long postingId) {
        return new JobNotificationEvent(Kind.APPLIED, applicationId, null, postingId, -1.0);
    }

    public static JobNotificationEvent matched(Long contractId, Long postingId) {
        return new JobNotificationEvent(Kind.MATCHED, null, contractId, postingId, -1.0);
    }

    public static JobNotificationEvent completionReported(Long contractId, Long postingId) {
        return new JobNotificationEvent(Kind.COMPLETION_REPORTED, null, contractId, postingId, -1.0);
    }

    public static JobNotificationEvent checkedIn(Long contractId) {
        return new JobNotificationEvent(Kind.CHECKED_IN, null, contractId, null, -1.0);
    }

    public static JobNotificationEvent checkedOut(Long contractId) {
        return new JobNotificationEvent(Kind.CHECKED_OUT, null, contractId, null, -1.0);
    }

    public static JobNotificationEvent geoAnomaly(Long contractId, double distanceMeters) {
        return new JobNotificationEvent(Kind.GEO_ANOMALY, null, contractId, null, distanceMeters);
    }
}
