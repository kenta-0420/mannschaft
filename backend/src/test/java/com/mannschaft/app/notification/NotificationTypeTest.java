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
    @DisplayName("通知種別が全て定義されている（既存31種別 + OWNERSHIP_TRANSFER_* 2種 + ADMIN_SUCCESSION_FORCED 1種 + JOIN_REQUEST_* 3種 = 37）")
    void 全37種別が定義() {
        // 内訳: 設計書§5 の通知種別（F03.4.5 §6.1 の RESERVATION_WAITLIST_OPENING を含む 25 種別）
        //       ＋ TODO_HANDED_OFF（後付け）＋ F20.3 ベータ特典の BETA_PERK_GRANTED/_REVOKED/_EXTENDED/
        //       _REVIEW_FLAGGED（4 種）＋ F03.4.5 §6.3 の RESERVATION_PENDING_EXPIRED（仮押さえ自動失効）
        //       ＋ F01.2 オーナー委譲承諾型の OWNERSHIP_TRANSFER_OFFERED / _DECLINED（2種）
        //       ＋ 柱①ADMINゼロ根治の ADMIN_SUCCESSION_FORCED（強制承継通知、1種）
        //       ＋ CMP-260901-1538 柱③-A「MEMBER 参加申請」の JOIN_REQUEST_RECEIVED /
        //         _APPROVED / _REJECTED（3種）
        //       = 計 37 種別。
        assertThat(NotificationType.values()).hasSize(37);
        assertThat(NotificationType.values())
                .contains(NotificationType.BETA_PERK_GRANTED, NotificationType.BETA_PERK_REVOKED,
                        NotificationType.BETA_PERK_EXTENDED, NotificationType.BETA_PERK_REVIEW_FLAGGED,
                        NotificationType.RESERVATION_PENDING_EXPIRED,
                        NotificationType.OWNERSHIP_TRANSFER_OFFERED,
                        NotificationType.OWNERSHIP_TRANSFER_DECLINED,
                        NotificationType.ADMIN_SUCCESSION_FORCED,
                        NotificationType.JOIN_REQUEST_RECEIVED,
                        NotificationType.JOIN_REQUEST_APPROVED,
                        NotificationType.JOIN_REQUEST_REJECTED);
    }

    @Test
    @DisplayName("F03.4.5 §6.3: RESERVATION_PENDING_EXPIRED は NORMAL 優先度・sourceType=RESERVATION")
    void 仮押さえ失効通知の優先度とsourceType() {
        // 管理者の不作為による失効であり緊急性は無いため HIGH にしない（設計書 §6.3）。
        assertThat(NotificationType.RESERVATION_PENDING_EXPIRED.getPriority())
                .isEqualTo(NotificationPriority.NORMAL);
        // sourceType は F00 visibility / 受信権の判定キー。予約ドメインの既存種別と揃える。
        assertThat(NotificationType.RESERVATION_PENDING_EXPIRED.getSourceType()).isEqualTo("RESERVATION");
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
