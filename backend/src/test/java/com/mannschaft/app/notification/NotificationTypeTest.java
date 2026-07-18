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
    @DisplayName("通知種別が全て定義されている（設計書§5の25種別 + TODO_HANDED_OFF + OWNERSHIP_TRANSFER_OFFERED/DECLINED = 28）")
    void 全28種別が定義() {
        // 内訳: 設計書§5 の通知種別（F03.4.5 §6.1 の RESERVATION_WAITLIST_OPENING を含む 25 種別）
        //       ＋ TODO_HANDED_OFF（後付け）
        //       ＋ F01.2 オーナー委譲（承諾型）の OWNERSHIP_TRANSFER_OFFERED / OWNERSHIP_TRANSFER_DECLINED
        //       = 計 28 種別。新種別追加時はこのリストへの明示追加が必須（数合わせのみの追随を禁止する番人）。
        assertThat(NotificationType.values()).containsExactlyInAnyOrder(
                NotificationType.SCHEDULE_CREATED,
                NotificationType.SCHEDULE_UPDATED,
                NotificationType.SCHEDULE_CANCELLED,
                NotificationType.ATTENDANCE_REMINDER,
                NotificationType.ATTENDANCE_RESPONDED,
                NotificationType.RESERVATION_REMINDER,
                NotificationType.RESERVATION_CONFIRMED,
                NotificationType.RESERVATION_CANCELLED,
                NotificationType.CHAT_MENTION,
                NotificationType.CHAT_DM,
                NotificationType.TIMELINE_MENTION,
                NotificationType.TIMELINE_REPLY,
                NotificationType.BLOG_PUBLISHED,
                NotificationType.ANNOUNCEMENT,
                NotificationType.SURVEY_CREATED,
                NotificationType.SAFETY_CHECK,
                NotificationType.MEMBER_JOINED,
                NotificationType.MODULE_AVAILABLE,
                NotificationType.SYSTEM_NOTICE,
                NotificationType.RESERVATION_RECEIVED,
                NotificationType.RESERVATION_PENDING_APPROVAL,
                NotificationType.RESERVATION_CANCELLED_BY_MEMBER,
                NotificationType.RESERVATION_WAITLIST_OPENING,
                NotificationType.INQUIRY_RECEIVED,
                NotificationType.DAILY_DIGEST,
                NotificationType.TODO_HANDED_OFF,
                NotificationType.OWNERSHIP_TRANSFER_OFFERED,
                NotificationType.OWNERSHIP_TRANSFER_DECLINED);
        assertThat(NotificationType.values()).hasSize(28);
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
