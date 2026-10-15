package com.mannschaft.app.notification.confirmable.entity;

/**
 * F04.9 Phase D 未確認者一覧の公開範囲。
 *
 * <p>確認通知作成時に送信者が指定する公開範囲。送信時に
 * {@link ConfirmableNotificationEntity#unconfirmedVisibility} へスナップショットされる。
 * リクエスト省略時はスコープ設定
 * （{@link ConfirmableNotificationSettingsEntity#defaultUnconfirmedVisibility}）の値を採用する。</p>
 *
 * <p><b>認可ルール</b>:
 * <ul>
 *   <li>{@link #HIDDEN} — 受信者リストはメンバーに非公開。ADMIN+ のみ閲覧可</li>
 *   <li>{@link #CREATOR_AND_ADMIN} — 既存挙動。送信者本人 + ADMIN/DEPUTY_ADMIN のみ閲覧可</li>
 *   <li>{@link #ALL_MEMBERS} — スコープ内の受信者全員が未確認者のみ閲覧可</li>
 * </ul>
 * </p>
 */
public enum UnconfirmedVisibility {

    /** 誰にも未確認者リストを表示しない（ADMIN+ は別途閲覧可） */
    HIDDEN,

    /** 送信者本人と ADMIN/DEPUTY_ADMIN のみ閲覧可（デフォルト・既存挙動） */
    CREATOR_AND_ADMIN,

    /** スコープ内の受信者全員が未確認者のみ閲覧可（相互声掛け文化のチーム向け） */
    ALL_MEMBERS
}
