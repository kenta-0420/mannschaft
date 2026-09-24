package com.mannschaft.app.notification.service;

/**
 * {@link NotificationDeliveryRunner#sendOne} の結果種別（Issue #2959）。
 *
 * <p>従来は戻り値が {@code NotificationEntity} だったため、他ドメインの配送リスナーが
 * 通知ドメインの Entity を import せざるを得ず、D-1（クロスドメイン Entity 参照禁止）に
 * 違反していた。呼び出し元は null 判定にしか使っていなかったため、Entity を返す必然性が
 * 無く、本 enum に置き換える。</p>
 */
public enum NotificationDeliveryResult {

    /** 通知の作成・配送に成功した。 */
    DELIVERED,

    /** visibility deny により通知の作成をスキップした（{@code NotificationService} 側で
     * 既に WARN ログ済み。呼び出し元は例外ではなく非例外の deny として扱うこと）。 */
    VISIBILITY_DENIED,
}
