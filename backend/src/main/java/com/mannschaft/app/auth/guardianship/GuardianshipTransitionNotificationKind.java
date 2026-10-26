package com.mannschaft.app.auth.guardianship;

/**
 * F08.9 P3c-3 自立移行通知の種別（02_api_design §2.3）。
 *
 * <p>自立移行の保険として日次で走る 2 つのバッチが送る通知の種別。
 * {@code guardianship_transition_notifications} の冪等キーの一部として使い、
 * 同一（受信者×子×境界日×種別）で 1 回限りの送信を保証する。</p>
 */
public enum GuardianshipTransitionNotificationKind {

    /** 進学予告（封印境界日の 3ヶ月前から保護者へ事前通知）。宛先＝保護者。 */
    PROGRESSION_NOTICE,

    /** 封印時の未設定メール（封印日以降にパスワード未設定の子へ送付）。宛先＝子本人。 */
    SEAL_UNSET_PASSWORD
}
