package com.mannschaft.app.notification;

import lombok.Getter;

import java.util.Optional;

/**
 * F04.3 通知種別カタログ（設計書 §5 通知種別一覧）。
 *
 * <p>各種別は優先度（{@link NotificationPriority}）・ソース種別・表示ラベルキー
 * （{@code MessageSource} キー）・既定 ON/OFF を保持する。</p>
 *
 * <p><b>ロック種別</b>: {@code priority == URGENT} の種別はユーザー設定で無効化できない
 * （{@link #isLocked()} が true）。配信判定では全チャネル強制配信となる。</p>
 *
 * <p><b>既定 OFF</b>: {@code DAILY_DIGEST} のみ opt-in 方式で既定 OFF
 * （{@link #isDefaultEnabled()} が false）。それ以外は既定 ON。</p>
 *
 * <p>VARCHAR 永続化との後方互換のため、{@code notification_type_preferences.notification_type}
 * は本 enum の {@code name()} を文字列として保存する。</p>
 */
@Getter
public enum NotificationType {

    SCHEDULE_CREATED(NotificationPriority.NORMAL, "SCHEDULE"),
    SCHEDULE_UPDATED(NotificationPriority.NORMAL, "SCHEDULE"),
    SCHEDULE_CANCELLED(NotificationPriority.HIGH, "SCHEDULE"),
    ATTENDANCE_REMINDER(NotificationPriority.HIGH, "SCHEDULE"),
    ATTENDANCE_RESPONDED(NotificationPriority.LOW, "SCHEDULE"),
    RESERVATION_REMINDER(NotificationPriority.HIGH, "RESERVATION"),
    RESERVATION_CONFIRMED(NotificationPriority.NORMAL, "RESERVATION"),
    RESERVATION_CANCELLED(NotificationPriority.HIGH, "RESERVATION"),
    CHAT_MENTION(NotificationPriority.NORMAL, "CHAT_MESSAGE"),
    CHAT_DM(NotificationPriority.NORMAL, "CHAT_MESSAGE"),
    TIMELINE_MENTION(NotificationPriority.NORMAL, "TIMELINE_POST"),
    TIMELINE_REPLY(NotificationPriority.LOW, "TIMELINE_POST"),
    BLOG_PUBLISHED(NotificationPriority.NORMAL, "BLOG_POST"),
    ANNOUNCEMENT(NotificationPriority.HIGH, "BLOG_POST"),
    SURVEY_CREATED(NotificationPriority.NORMAL, "SURVEY"),
    SAFETY_CHECK(NotificationPriority.URGENT, "SAFETY_CHECK"),
    MEMBER_JOINED(NotificationPriority.LOW, "USER"),
    MODULE_AVAILABLE(NotificationPriority.LOW, "MODULE"),
    SYSTEM_NOTICE(NotificationPriority.NORMAL, "SYSTEM"),
    RESERVATION_RECEIVED(NotificationPriority.HIGH, "RESERVATION"),
    RESERVATION_PENDING_APPROVAL(NotificationPriority.HIGH, "RESERVATION"),
    RESERVATION_CANCELLED_BY_MEMBER(NotificationPriority.NORMAL, "RESERVATION"),
    /** F03.4.5 §6.1: 満席枠のキャンセルで空きが出たときのキャンセル待ち一斉通知（HIGH）。 */
    RESERVATION_WAITLIST_OPENING(NotificationPriority.HIGH, "RESERVATION"),
    INQUIRY_RECEIVED(NotificationPriority.HIGH, "CHAT_MESSAGE"),
    /** 日次ダイジェスト。opt-in 方式のため既定 OFF。 */
    DAILY_DIGEST(NotificationPriority.LOW, "SYSTEM", false),
    TODO_HANDED_OFF(NotificationPriority.NORMAL, "TODO"),
    /**
     * F01.2: オーナー委譲（承諾型）の打診が指名相手に届いたことの到達通知（HIGH）。
     * 宛先が承諾/辞退画面（{@code /teams|organizations/{slug}/members?offerId=...}）へ到達するための導線。
     */
    OWNERSHIP_TRANSFER_OFFERED(NotificationPriority.HIGH, "USER"),
    /** F01.2: オーナー委譲の打診が指名相手に辞退されたことの発行者向け通知（NORMAL・設計書 step 辞退）。 */
    OWNERSHIP_TRANSFER_DECLINED(NotificationPriority.NORMAL, "USER");

    private final NotificationPriority priority;
    private final String sourceType;
    /** 既定で受信 ON か。DAILY_DIGEST のみ false（opt-in）。 */
    private final boolean defaultEnabled;

    NotificationType(NotificationPriority priority, String sourceType) {
        this(priority, sourceType, true);
    }

    NotificationType(NotificationPriority priority, String sourceType, boolean defaultEnabled) {
        this.priority = priority;
        this.sourceType = sourceType;
        this.defaultEnabled = defaultEnabled;
    }

    /**
     * 表示ラベルの {@code MessageSource} キー。
     * 例: {@code notification.type.SCHEDULE_CREATED.label}
     *
     * @return MessageSource ラベルキー
     */
    public String getLabelKey() {
        return "notification.type." + name() + ".label";
    }

    /**
     * ユーザー設定で無効化できない（ロックされた）種別か。
     * URGENT 種別は全チャネル強制配信のためロックされる。
     *
     * @return ロックされている場合 true
     */
    public boolean isLocked() {
        return priority == NotificationPriority.URGENT;
    }

    /**
     * 文字列（永続化された notification_type）から enum を安全に解決する。
     *
     * @param value notification_type 値
     * @return 該当する {@link NotificationType}。未知の値は空
     */
    public static Optional<NotificationType> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (NotificationType type : values()) {
            if (type.name().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
