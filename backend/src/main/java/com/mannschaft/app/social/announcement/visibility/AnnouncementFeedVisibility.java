package com.mannschaft.app.social.announcement.visibility;

/**
 * お知らせウィジェットフィード（{@code announcement_feeds.visibility}）の可視性 enum（F02.6 / F08.9 P4b）。
 *
 * <p>DB カラムは {@code VARCHAR(30)} で {@link com.mannschaft.app.social.announcement.AnnouncementVisibility}
 * の String 定数と同じ値を保持する。本 enum はその Java 型安全なラッパーである。</p>
 *
 * <p><strong>F08.9 P4b ペイウォール連結</strong>: {@link #CUSTOM} は
 * {@link com.mannschaft.app.common.visibility.StandardVisibility#CUSTOM} に写像され、
 * {@link AnnouncementFeedVisibilityResolver#evaluateCustom} 経由で
 * {@link com.mannschaft.app.payment.service.PaymentGateService#checkAccess}
 * を呼ぶ（設計書 F08.9 02 §6 / F00 §5.1.4）。</p>
 *
 * <p>既存の {@link com.mannschaft.app.social.announcement.AnnouncementVisibility} 文字列定数との対応:
 * <ul>
 *   <li>{@code "PUBLIC"} → {@link #PUBLIC}</li>
 *   <li>{@code "SUPPORTERS_AND_ABOVE"} → {@link #SUPPORTERS_AND_ABOVE}</li>
 *   <li>{@code "MEMBERS_AND_ABOVE"} → {@link #MEMBERS_AND_ABOVE}</li>
 *   <li>{@code "CUSTOM"}（F08.9 P4b 新規）→ {@link #CUSTOM}</li>
 * </ul>
 * </p>
 */
public enum AnnouncementFeedVisibility {

    /** 全員（未ログイン含む）が閲覧可能。 */
    PUBLIC,

    /** SUPPORTER 以上が閲覧可能。 */
    SUPPORTERS_AND_ABOVE,

    /**
     * MEMBER 以上が閲覧可能（SUPPORTER は不可・「内輪」）。
     *
     * <p>正準ラダー {@code StandardVisibility.MEMBERS_AND_ABOVE} と同一閾値。</p>
     */
    MEMBERS_AND_ABOVE,

    /**
     * カスタム判定（F08.9 P4b ペイウォール連結）。
     *
     * <p>{@link com.mannschaft.app.common.visibility.StandardVisibility#CUSTOM} に写像され、
     * {@link AnnouncementFeedVisibilityResolver#evaluateCustom} 経由で
     * {@link com.mannschaft.app.payment.service.PaymentGateService} にペイウォール判定を委譲する。</p>
     */
    CUSTOM
}
