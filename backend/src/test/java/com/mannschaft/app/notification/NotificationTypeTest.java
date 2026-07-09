package com.mannschaft.app.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationType} カタログ enum の単体テスト（F04.3 §5）。
 */
@DisplayName("NotificationType 単体テスト")
class NotificationTypeTest {

    @Test
    @DisplayName("通知種別が全て定義されている（設計書§5の25種別 + TODO_HANDED_OFF = 26）")
    void 全26種別が定義() {
        // 内訳: 設計書§5 の通知種別（F03.4.5 §6.1 の RESERVATION_WAITLIST_OPENING を含む 25 種別）
        //       ＋ TODO_HANDED_OFF（後付け）= 計 26 種別。
        assertThat(NotificationType.values()).hasSize(26);
    }

    @Test
    @DisplayName("URGENT 種別（SAFETY_CHECK）は isLocked=true")
    void URGENTはロック() {
        assertThat(NotificationType.SAFETY_CHECK.getPriority()).isEqualTo(NotificationPriority.URGENT);
        assertThat(NotificationType.SAFETY_CHECK.isLocked()).isTrue();
    }

    @Test
    @DisplayName("非 URGENT 種別は isLocked=false")
    void 非URGENTは非ロック() {
        assertThat(NotificationType.SCHEDULE_CREATED.isLocked()).isFalse();
        assertThat(NotificationType.ATTENDANCE_RESPONDED.isLocked()).isFalse();
    }

    @Test
    @DisplayName("DAILY_DIGEST のみ既定 OFF（defaultEnabled=false）")
    void DAILY_DIGESTは既定OFF() {
        assertThat(NotificationType.DAILY_DIGEST.isDefaultEnabled()).isFalse();
        assertThat(NotificationType.SCHEDULE_CREATED.isDefaultEnabled()).isTrue();
        assertThat(NotificationType.BLOG_PUBLISHED.isDefaultEnabled()).isTrue();
    }

    @Test
    @DisplayName("labelKey は notification.type.{NAME}.label 形式")
    void ラベルキー形式() {
        assertThat(NotificationType.SCHEDULE_CREATED.getLabelKey())
                .isEqualTo("notification.type.SCHEDULE_CREATED.label");
    }

    @Test
    @DisplayName("fromValue: 既知の値は解決、未知の値・null は空")
    void fromValue() {
        assertThat(NotificationType.fromValue("BLOG_PUBLISHED"))
                .contains(NotificationType.BLOG_PUBLISHED);
        assertThat(NotificationType.fromValue("UNKNOWN_XYZ")).isEmpty();
        assertThat(NotificationType.fromValue(null)).isEmpty();
    }

    @Test
    @DisplayName("各種別が priority と sourceType を保持する")
    void priorityとsourceType保持() {
        assertThat(NotificationType.SCHEDULE_CANCELLED.getPriority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(NotificationType.SCHEDULE_CANCELLED.getSourceType()).isEqualTo("SCHEDULE");
    }
}
