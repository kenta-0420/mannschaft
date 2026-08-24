package com.mannschaft.app.contact.event;

/**
 * 連絡先申請ドメインの通知発火イベント（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code ContactRequestService#sendRequest} / {@code #acceptRequest} は業務トランザクションの
 * 内側で本イベントを publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、通知の文面組み立て
 * （{@code userRepository.findById} によるアクター名解決・{@code userLocaleCache.getLocale} による
 * ロケール解決・{@code messageSource.getMessage} による件名/本文組み立て）は行わない。
 *
 * <p><b>Codex 独立検分 [P2]（2026-08-21）で指摘・是正</b>: 初版は文面組み立て済みの
 * {@code NotificationDeliveryRequest} をイベントに積んでいたため、組み立て処理（DB読み取り・
 * {@code MessageFormat} 例外を伴いうる）が業務トランザクションの内側に残ってしまい、原則5
 * （付随通知は業務トランザクションの外で発火する）が「配送だけ外、組み立ては内」という中途半端な
 * 状態になっていた。Issue #2871 の教訓（配信が後で起きるなら描画済み文字列を先に作って持ち回るな）
 * にも反する。本イベントは ID のみを運び、組み立ては {@link ContactRequestNotificationListener}
 * （{@code AFTER_COMMIT}）側で行う。</p>
 *
 * @param kind      通知種別（受信 or 承認）
 * @param actorId   通知本文に載せるアクターのユーザーID（受信＝申請者、承認＝承認者）
 * @param targetId  通知の宛先ユーザーID
 * @param requestId 連絡先申請ID（{@code sourceId} に使う）
 */
public record ContactRequestNotificationEvent(Kind kind, Long actorId, Long targetId, Long requestId) {

    /** 通知種別。 */
    public enum Kind {
        /** 申請受信通知（宛先=申請の targetId、アクター=申請者）。 */
        REQUEST_RECEIVED,
        /** 申請承認通知（宛先=申請者、アクター=承認者）。 */
        REQUEST_ACCEPTED
    }
}
