package com.mannschaft.app.shift.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * シフトクリーンアップバッチサービス。
 * バッチ#2: PENDING スワップ申請を 48h 後に自動キャンセル（通知付き）。
 * バッチ#3: ARCHIVED から 30 日経過したシフト希望を物理削除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftCleanupBatchService {

    private static final int BATCH_SIZE = 100;

    private final ShiftSwapRequestRepository swapRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftRequestRepository requestRepository;
    private final NotificationHelper notificationHelper;
    /** Issue #2715 ロットB: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    /**
     * 毎日 AM 3:00（JST）に実行。48h 経過した PENDING スワップ申請を自動キャンセルする。
     */
    @BatchEndpoint(name = "shift-swap-expiry-cancel-daily", description = "48h 経過した PENDING スワップ申請を毎日 03:00 に自動キャンセルする")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_swap_expiry_cancel", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void runSwapExpiryCancel() {
        log.info("スワップ申請期限切れキャンセルバッチ開始");
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).minusHours(48);
        List<ShiftSwapRequestEntity> targets = swapRepository
                .findExpiredPendingBefore(cutoff, PageRequest.of(0, BATCH_SIZE));

        int cancelled = 0;
        int skipped = 0;

        for (ShiftSwapRequestEntity swap : targets) {
            try {
                swap.cancel();
                swapRepository.save(swap);

                notifySwapExpired(swap.getRequesterId(), swap);
                if (swap.getTargetUserId() != null) {
                    notifySwapExpired(swap.getTargetUserId(), swap);
                }

                cancelled++;
                log.info("スワップ申請自動キャンセル: swapId={}, requesterId={}", swap.getId(), swap.getRequesterId());

            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                skipped++;
                log.warn("楽観ロック競合によりスキップ: swapId={}", swap.getId());
            } catch (Exception e) {
                skipped++;
                log.error("スワップ申請キャンセル失敗: swapId={}", swap.getId(), e);
            }
        }

        log.info("スワップ申請期限切れキャンセルバッチ完了: cancelled={}, skipped={}, 対象={}", cancelled, skipped, targets.size());
    }

    /**
     * 毎日 AM 3:05（JST）に実行。ARCHIVED から 30 日経過したシフト希望を物理削除する。
     */
    @BatchEndpoint(name = "shift-request-cleanup-daily", description = "ARCHIVED から 30 日経過のシフト希望を毎日 03:05 に物理削除する")
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_request_cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void runRequestCleanup() {
        log.info("シフト希望物理削除バッチ開始");
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).minusDays(30);
        List<Long> scheduleIds = scheduleRepository
                .findArchivedScheduleIdsOlderThan(cutoff, PageRequest.of(0, BATCH_SIZE));

        if (scheduleIds.isEmpty()) {
            log.info("シフト希望物理削除バッチ完了: 対象スケジュールなし");
            return;
        }

        int deleted = requestRepository.deleteByScheduleIds(scheduleIds);
        log.info("シフト希望物理削除バッチ完了: scheduleIds={}, 削除件数={}", scheduleIds.size(), deleted);
    }

    private void notifySwapExpired(Long userId, ShiftSwapRequestEntity swap) {
        try {
            Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(userId));
            String title = messageSource.getMessage(
                    "notification.shift.swapExpired.title", null,
                    "シフト交代申請が期限切れになりました", locale);
            String body = messageSource.getMessage(
                    "notification.shift.swapExpired.body", null,
                    "申請から 48 時間が経過したため、シフト交代申請が自動キャンセルされました。", locale);
            notificationHelper.notify(
                    userId,
                    "SHIFT_SWAP_EXPIRED",
                    title,
                    body,
                    "SHIFT_SWAP_REQUEST", swap.getId(),
                    NotificationScopeType.PERSONAL, userId,
                    "/shifts/swap-requests/" + swap.getId(), null);
        } catch (Exception e) {
            log.warn("スワップ期限切れ通知失敗（継続）: userId={}, swapId={}", userId, swap.getId());
        }
    }
}
