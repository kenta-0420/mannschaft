package com.mannschaft.app.event.entity;

import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.EventCareNotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベントケア通知ログエンティティ。F03.12 見守り通知の送信記録を管理する。
 *
 * <p>冪等チェックおよびリトライ管理に使用する。</p>
 */
@Entity
@Table(name = "event_care_notification_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventCareNotificationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象イベントID。 */
    @Column(nullable = false)
    private Long eventId;

    /** ケア対象者のユーザーID。 */
    @Column(nullable = false)
    private Long careRecipientUserId;

    /** 通知を受け取る見守り者のユーザーID。 */
    @Column(nullable = false)
    private Long watcherUserId;

    /** ケアカテゴリ（MINOR / ELDERLY / DISABILITY_SUPPORT / GENERAL_FAMILY）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CareCategory careCategory;

    /** 通知種別（RSVP_CONFIRMED / CHECKIN / CHECKOUT / NO_CONTACT_CHECK / ABSENT_ALERT / DISMISSAL）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventCareNotificationType notificationType;

    /** 作成した通知エンティティのID。 */
    private Long notificationId;

    /** 送信日時。 */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime sentAt = LocalDateTime.now();

    /** リトライ回数。 */
    @Column(nullable = false)
    @Builder.Default
    private Byte retryCount = 0;

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) this.sentAt = LocalDateTime.now();
    }
}
