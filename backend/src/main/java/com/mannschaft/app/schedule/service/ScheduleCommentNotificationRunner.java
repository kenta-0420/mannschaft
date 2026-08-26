package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.16 予定コメント通知の 1 件送信用 REQUIRES_NEW 実行 Bean。
 *
 * <p>{@code com.mannschaft.app.succession.service.DelinquencyEscalationAdvanceRunner}
 * （Issue #2601・PR #2660）と同形の金型。{@link ScheduleCommentNotifier#notify} が受信者ごとに
 * ループで本 Bean を呼ぶ。
 * バッチ失敗時のリトライ安全性・他受信者への巻き添え防止のため、<b>1 件の通知送信 = 1 独立
 * トランザクション</b>とする必要があり、独立した Bean に切り出し {@link Propagation#REQUIRES_NEW}
 * を付与する（同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。</p>
 *
 * <h2>この Bean が必要になった経緯（#2655 / #2660 / #2664 と同型の欠陥）</h2>
 * <p>是正前は {@link ScheduleCommentNotifier#notify} 全体が単一の {@code @Transactional(REQUIRES_NEW)}
 * であり、受信者ごとの {@code notificationService.createNotification} 呼び出しは既定の
 * {@code REQUIRED} で同一トランザクションに相乗りしていた。この構成では 1 受信者の失敗で
 * トランザクションにロールバックオンリーが立ち、{@code try/catch} で捕捉して次の受信者へ進んでも
 * コミット時に {@code UnexpectedRollbackException} となり、成功済みの他受信者の通知まで
 * 巻き戻っていた（実装しながら実装を検証しないと見逃す・本プロジェクトで3ドメイン独立発見済みの
 * 既知の形）。{@link NotificationService#createNotification} 自体の伝播設定（既定の
 * {@code REQUIRED}）は変更しない（他の呼び出し元に影響させないため）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleCommentNotificationRunner {

    private final NotificationService notificationService;

    /**
     * 1 件の通知を独立トランザクションで送信する。
     *
     * @return 作成された通知エンティティの ID（呼び出し元は使わないため戻り値は将来拡張用）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOne(
            Long userId,
            String notificationType,
            NotificationPriority priority,
            String title,
            String body,
            String sourceType,
            Long sourceId,
            NotificationScopeType scopeType,
            Long scopeId,
            String actionUrl,
            Long actorId) {
        notificationService.createNotification(
                userId, notificationType, priority, title, body,
                sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
    }
}
