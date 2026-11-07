package com.mannschaft.app.reservation.event;

import java.util.List;

/**
 * 臨時休業の一括通知の配送要求イベント（Issue #2997 / CMP-260827-1152 G3）。
 *
 * <p>{@code EmergencyClosureService#sendClosure} の業務トランザクション内で publish され、
 * {@link EmergencyClosureNotificationListener} が {@code AFTER_COMMIT} で受け取る。
 * 業務TXがロールバックすれば通知は 1 件も作られない。</p>
 *
 * <h2>イベントに載せる値の方針</h2>
 * <p>「イベントには ID だけを載せる」原則に従い、件名・理由・チームID・実行者IDは
 * {@code emergency_closures} を {@code closureId} で読み直して得る（コミット後は必ず生存している）。
 * 読み直せないもの（コミット時点の受信者集合）だけを載せる。受信者は呼び出し側で重複除去済みの
 * スナップショットで、不変コピーを渡すこと。</p>
 *
 * @param closureId        臨時休業ID
 * @param recipientUserIds 受信者ユーザーID（重複除去済み・1 人以上）
 */
public record EmergencyClosureNotificationEvent(Long closureId, List<Long> recipientUserIds) {

    public EmergencyClosureNotificationEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }
}
