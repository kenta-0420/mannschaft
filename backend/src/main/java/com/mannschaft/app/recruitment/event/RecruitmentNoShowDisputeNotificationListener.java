package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 確定した異議申立を、裁定できる主催者へアプリ内で知らせる。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentNoShowDisputeNotificationListener {

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定した異議申立の裁定待ち通知は、募集 gate の停止後も配送する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisputeRaised(RecruitmentNoShowDisputeNotificationEvent event) {
        try {
            RecruitmentListingEntity listing = listingRepository.findById(event.listingId()).orElse(null);
            if (listing == null || !noShowRepository.findById(event.recordId())
                    .filter(record -> record.isDisputed() && record.getDisputeResolution() == null)
                    .isPresent()) {
                return;
            }
            List<Long> recipients = switch (listing.getScopeType()) {
                case PERSONAL -> listing.getCreatedBy().equals(listing.getScopeId())
                        && userRepository.existsActiveById(listing.getCreatedBy())
                        ? List.of(listing.getCreatedBy()) : List.of();
                case TEAM -> Stream.concat(
                        userRoleRepository.findAdminUserIdsByTeamId(listing.getScopeId()).stream(),
                        userRoleRepository.findAllDeputyAdminUserIdsByTeamId(listing.getScopeId()).stream())
                        .distinct().toList();
                case ORGANIZATION -> userRoleRepository
                        .findAdminUserIdsByOrganizationId(listing.getScopeId()).stream().distinct().toList();
            };
            NotificationScopeType notificationScope = NotificationScopeType.valueOf(listing.getScopeType().name());
            String actionUrl = "/scopes/" + listing.getScopeType().name().toLowerCase(Locale.ROOT)
                    + "/" + listing.getScopeId() + "/no-shows";
            for (Long recipientId : recipients) {
                try {
                    // 宛先はスコープの裁定権で選定済み。募集の公開可視性は裁定権と別なので再判定しない。
                    notificationService.createNotificationPreAuthorized(
                            recipientId, "RECRUITMENT_NO_SHOW_DISPUTE_RAISED", NotificationPriority.NORMAL,
                            "NO_SHOWへの異議申立を受領しました",
                            "募集枠 #" + listing.getId() + " の NO_SHOW 記録に異議申立があります。内容を確認して裁定してください。",
                            "RECRUITMENT_LISTING", listing.getId(), notificationScope, listing.getScopeId(),
                            actionUrl, event.actorUserId());
                } catch (Exception deliveryFailure) {
                    log.error("NO_SHOW 異議申立通知の作成に失敗: recordId={}, recipientUserId={}",
                            event.recordId(), recipientId, deliveryFailure);
                }
            }
        } catch (Exception failure) {
            log.error("NO_SHOW 異議申立通知の配送に失敗: recordId={}", event.recordId(), failure);
        }
    }
}
