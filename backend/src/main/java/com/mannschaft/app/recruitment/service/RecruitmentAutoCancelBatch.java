package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.ParticipantHistoryReason;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F03.11 Phase 3: 自動キャンセルバッチ (§5.4)。
 *
 * <p>自動キャンセル日時 (auto_cancel_at) を過ぎており、かつ最小定員 (min_capacity) を
 * 満たせなかった OPEN/FULL 状態の募集を AUTO_CANCELLED に遷移させる。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentAutoCancelBatch {

    /** 参加者チャンク処理のページサイズ。 */
    private static final int CHUNK_SIZE = 100;

    /** 自動キャンセル対象の参加者ステータス。 */
    private static final List<RecruitmentParticipantStatus> CANCEL_TARGET_STATUSES = List.of(
            RecruitmentParticipantStatus.CONFIRMED,
            RecruitmentParticipantStatus.WAITLISTED,
            RecruitmentParticipantStatus.APPLIED
    );

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentParticipantHistoryRepository historyRepository;
    private final ConfirmableNotificationService confirmableNotificationService;

    /**
     * 5分間隔で自動キャンセル対象の募集を処理する。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @SchedulerLock(name = "recruitment-auto-cancel-batch", lockAtLeastFor = "PT4M", lockAtMostFor = "PT15M")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<RecruitmentListingEntity> candidates = listingRepository.findAutoCancelTargets(now);

        if (candidates.isEmpty()) {
            return;
        }

        log.info("F03.11 自動キャンセルバッチ開始: 候補件数={}", candidates.size());

        int totalCancelled = 0;
        for (RecruitmentListingEntity candidate : candidates) {
            try {
                int result = processSingleListing(candidate.getId(), now);
                totalCancelled += result;
            } catch (Exception e) {
                log.warn("F03.11 自動キャンセルバッチ 個別処理失敗: listingId={}, error={}",
                        candidate.getId(), e.getMessage());
            }
        }
        log.info("F03.11 自動キャンセルバッチ完了: 処理件数={}", totalCancelled);
    }

    /**
     * 1つの募集を自動キャンセルする。トランザクション分離で大量ロック回避。
     *
     * @param listingId 処理対象の募集ID
     * @param now       バッチ実行日時
     * @return キャンセルした参加者数（0はスキップ含む）
     */
    @Transactional
    public int processSingleListing(Long listingId, LocalDateTime now) {
        // PESSIMISTIC_WRITE で行ロックを取得して最新状態を確認
        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalStateException("募集が見つかりません: id=" + listingId));

        // 再確認: OPEN/FULL かつ confirmedCount < minCapacity であること
        if (listing.getStatus() != RecruitmentListingStatus.OPEN
                && listing.getStatus() != RecruitmentListingStatus.FULL) {
            log.debug("F03.11 自動キャンセルスキップ (既にステータス変更済み): listingId={}, status={}",
                    listingId, listing.getStatus());
            return 0;
        }
        if (listing.getConfirmedCount() >= listing.getMinCapacity()) {
            log.debug("F03.11 自動キャンセルスキップ (最小定員達成済み): listingId={}, confirmed={}, min={}",
                    listingId, listing.getConfirmedCount(), listing.getMinCapacity());
            return 0;
        }

        // 募集を AUTO_CANCELLED に遷移
        listing.autoCancel();

        // 参加者を 100件/チャンクでキャンセル処理
        List<Long> affectedUserIds = new ArrayList<>();
        int totalProcessed = 0;
        int pageIndex = 0;

        while (true) {
            Page<RecruitmentParticipantEntity> chunk = participantRepository.findByListingIdAndStatusIn(
                    listingId, CANCEL_TARGET_STATUSES, PageRequest.of(pageIndex, CHUNK_SIZE));

            if (chunk.isEmpty()) {
                break;
            }

            for (RecruitmentParticipantEntity participant : chunk.getContent()) {
                RecruitmentParticipantStatus oldStatus = participant.getStatus();

                // 参加者をシステムキャンセル
                participant.cancelBySystem();
                participantRepository.save(participant);

                // 参加者履歴を作成
                RecruitmentParticipantHistoryEntity history = RecruitmentParticipantHistoryEntity.builder()
                        .participantId(participant.getId())
                        .listingId(listingId)
                        .oldStatus(oldStatus)
                        .newStatus(RecruitmentParticipantStatus.CANCELLED)
                        .changedBy(null) // システム操作のため null
                        .changeReason(ParticipantHistoryReason.AUTO_CANCEL)
                        .build();
                historyRepository.save(history);

                // 通知対象ユーザーIDを収集
                if (participant.getUserId() != null) {
                    affectedUserIds.add(participant.getUserId());
                }
                totalProcessed++;
            }

            if (!chunk.hasNext()) {
                break;
            }
            pageIndex++;
        }

        // 募集を保存
        listingRepository.save(listing);

        log.info("F03.11 自動キャンセル実行: listingId={}, 参加者キャンセル数={}", listingId, totalProcessed);

        // 通知送信（受信者が存在する場合のみ）
        if (!affectedUserIds.isEmpty()) {
            try {
                confirmableNotificationService.send(
                        ScopeType.valueOf(listing.getScopeType().name()),
                        listing.getScopeId(),
                        "募集が自動キャンセルされました",
                        "最小定員を達成できなかったため自動キャンセルされました",
                        ConfirmableNotificationPriority.URGENT,
                        LocalDateTime.now().plusHours(72),
                        null, null, null, null,
                        null,
                        affectedUserIds);
            } catch (Exception e) {
                log.warn("F03.11 自動キャンセル通知送信失敗: listingId={}, error={}", listing.getId(), e.getMessage());
            }
        }

        return totalProcessed;
    }
}
