package com.mannschaft.app.jobmatching.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.JobNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 求人マッチングの通知配送リスナー（Issue #2997 / CMP-260827-1152 第1陣）。
 *
 * <p>業務トランザクションが commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で
 * 発火する。業務行は既にコミット済みのため、ここでの再読込・文面組み立て・配送は業務トランザクションを
 * 巻き込まない。1 イベント = 受信者 1 名で、失敗は本リスナー（TX 境界の外）で ERROR ログに残す。</p>
 *
 * <p>配送は既存の {@link JobNotificationService}（{@code NotificationHelper#notify}＝
 * {@code createNotification} + {@code dispatch}）をそのまま用いる。受信者・文面・可視性判定は従来と同一。
 * 呼び出し元に TX が無いため、{@code createNotification} は自前の TX で完結し、失敗しても他へ波及しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobNotificationListener {

    private final JobNotificationService notificationService;
    private final JobApplicationRepository applicationRepository;
    private final JobPostingRepository postingRepository;
    private final JobContractRepository contractRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。求人応募・採用・完了報告・チェックインの通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobNotification(JobNotificationEvent event) {
        try {
            switch (event.kind()) {
                case APPLIED -> {
                    JobApplicationEntity app = applicationRepository.findById(event.applicationId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "応募が見つかりません: applicationId=" + event.applicationId()));
                    notificationService.notifyApplied(app, findPosting(event.postingId()));
                }
                case MATCHED -> notificationService.notifyMatched(
                        findContract(event.contractId()), findPosting(event.postingId()));
                case COMPLETION_REPORTED -> notificationService.notifyCompletionReported(
                        findContract(event.contractId()), findPosting(event.postingId()));
                case CHECKED_IN -> notificationService.notifyCheckedIn(event.contractId());
                case CHECKED_OUT -> notificationService.notifyCheckedOut(event.contractId());
                case GEO_ANOMALY -> notificationService.notifyGeoAnomaly(
                        event.contractId(), event.distanceMeters());
            }
        } catch (Exception e) {
            log.error("求人通知の配送に失敗しました: kind={}, applicationId={}, contractId={}, postingId={}",
                    event.kind(), event.applicationId(), event.contractId(), event.postingId(), e);
        }
    }

    private JobPostingEntity findPosting(Long postingId) {
        return postingRepository.findById(postingId)
                .orElseThrow(() -> new IllegalStateException("求人が見つかりません: postingId=" + postingId));
    }

    private JobContractEntity findContract(Long contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalStateException("契約が見つかりません: contractId=" + contractId));
    }
}
