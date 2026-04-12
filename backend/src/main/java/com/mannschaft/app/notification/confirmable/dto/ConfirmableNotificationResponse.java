package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知一覧用レスポンスDTO（軽量版）。
 *
 * <p>confirmedCount は MapStruct 変換後に Controller 側でセットする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationResponse {

    private Long id;
    private ScopeType scopeType;
    private Long scopeId;
    private String title;
    private ConfirmableNotificationPriority priority;
    private ConfirmableNotificationStatus status;
    private LocalDateTime deadlineAt;

    /** 送信時点の受信者総数 */
    private Integer totalRecipientCount;

    /** 確認済み受信者数（Repository のカウントメソッドで取得） */
    private Long confirmedCount;

    private LocalDateTime createdAt;
}
