package com.mannschaft.app.event.event;

import java.util.List;

/**
 * イベント解散通知の発火イベント（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code EventDismissalService#sendDismissalNotification} は業務トランザクション
 * （{@code events.dismissal_notification_sent_at} の記録）の内側で本イベントを publish するだけに留める。
 * イベント表示ラベルの解決・ロケール解決・件名/本文の組み立て・見守り者への通知は
 * {@code EventDismissalNotificationListener}（{@code AFTER_COMMIT}）側で行う。</p>
 *
 * <h2>{@code customMessage} を載せてよい理由</h2>
 * <p>確定設計の「描画済み文字列を載せるな」は i18n 解決済みの件名/本文を指す。{@code customMessage} は
 * 主催者がリクエストで入力した<b>業務上の事実</b>（{@code DismissalRequest#getMessage}）であり、DB に
 * 保存されないためコミット後に読み直すことができない。よってイベントに載せる（i18n 対象外の
 * ユーザー入力値であることは是正前の {@code resolveDismissalBody} と同じ扱い）。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>解散処理は {@code EventEntity#recordDismissal} で送信日時を記録するだけであり、
 * イベント行を削除も論理削除もしない。よって {@code sourceType=EVENT}（visibility ガード対象）の
 * 参照先は {@code AFTER_COMMIT} 時点でも生存しており、コミット後発火による「静かな deny」は起きない。</p>
 *
 * @param eventId         対象イベントID（{@code sourceId} 兼ラベル解決キー）
 * @param teamId          チームID（{@code actionUrl} 構築用）
 * @param operatorUserId  解散通知を送信した操作者ユーザーID
 * @param customMessage   主催者が入力したカスタム本文（{@code null}/空なら locale 別の既定文言）
 * @param notifyGuardians 見守り者にも通知するか
 * @param targetUserIds   通知対象参加者のユーザーID一覧（RSVP=ATTENDING + チェックイン、重複排除済み）
 */
public record EventDismissalNotificationEvent(
        Long eventId,
        Long teamId,
        Long operatorUserId,
        String customMessage,
        boolean notifyGuardians,
        List<Long> targetUserIds) {
}
