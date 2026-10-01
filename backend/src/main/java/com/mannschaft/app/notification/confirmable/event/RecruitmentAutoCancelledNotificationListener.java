package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.event.RecruitmentAutoCancelledNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * CMP-260930-1932: 自動キャンセルされた募集の参加者へ、システム発の確認通知を送る
 * （{@link RecruitmentPenaltyAppliedNotificationListener} と同型。AFTER_COMMIT + {@code @Async("event-pool")}）。
 *
 * <p><b>試練（red）段階の骨格であり、何もしない。</b>出陣で以下を実装する（AC-5/AC-6）:</p>
 * <ul>
 *   <li>{@code createdBy = SystemUsers.SYSTEM_USER_ID}、{@code sourceType = "RECRUITMENT_AUTO_CANCEL"}、
 *       {@code sourceId = listingId} で {@code sendFromSource} を呼ぶ（PERSONAL → PLATFORM）。</li>
 *   <li>同一 listing で2回（並行を含む）発火しても確認通知は1件（冪等）。</li>
 *   <li>送信失敗は catch して ERROR ログ（listingId 入り）。自動キャンセル本体は commit 済みのまま。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentAutoCancelledNotificationListener {

    static final String SOURCE_TYPE = "RECRUITMENT_AUTO_CANCEL";

    private final ConfirmableNotificationService confirmableNotificationService;
    private final ConfirmableNotificationRepository confirmableNotificationRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定した自動キャンセルを参加者へ知らせるため、募集機能のgate状態にかかわらず配送する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAutoCancelled(RecruitmentAutoCancelledNotificationEvent event) {
        // 試練の骨格: 出陣で実装する（現状は何もしない＝AC-4/5/6 の red の原因）。
    }
}
