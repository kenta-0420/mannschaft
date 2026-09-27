package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** NO_SHOW の業務コミット後に本人通知を独立トランザクションで配送する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentNoShowNotificationListener {

    private final RecruitmentListingRepository listingRepository;
    private final NotificationDeliveryRunner deliveryRunner;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoShowRecorded(RecruitmentNoShowNotificationEvent event) {
        try {
            RecruitmentListingEntity listing = listingRepository.findById(event.listingId())
                    .orElseThrow(() -> new IllegalStateException("NO_SHOW 通知の募集が見つかりません"));
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    event.recipientUserId(), "RECRUITMENT_NO_SHOW_RECORDED", NotificationPriority.HIGH,
                    "欠席が記録されました", "欠席の記録を確認し、必要な場合は異議を申し立ててください。",
                    "RECRUITMENT_LISTING", listing.getId(),
                    NotificationScopeType.valueOf(listing.getScopeType().name()), listing.getScopeId(),
                    "/my/no-shows", null);
            NotificationDeliveryResult result = deliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("NO_SHOW 本人通知を可視性判定で省略: recordId={}, recipientUserId={}",
                        event.recordId(), event.recipientUserId());
            }
        } catch (Exception e) {
            log.error("NO_SHOW 本人通知の配送に失敗: recordId={}, recipientUserId={}",
                    event.recordId(), event.recipientUserId(), e);
        }
    }
}
