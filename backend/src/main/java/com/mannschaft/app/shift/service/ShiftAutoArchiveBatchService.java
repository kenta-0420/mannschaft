package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * シフト自動アーカイブバッチサービス。
 * 終了日から 7 日経過した PUBLISHED スケジュールを毎日 AM 3:00 に ARCHIVED へ遷移する。
 * ARCHIVED 遷移時に OPEN 状態の変更依頼を自動 WITHDRAWN にする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftAutoArchiveBatchService {

    private static final int BATCH_SIZE = 100;
    private static final int ARCHIVE_AFTER_DAYS = 7;

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftChangeRequestRepository changeRequestRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 毎日 AM 3:00（JST）に実行。終了から 7 日超過した PUBLISHED スケジュールをアーカイブする。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SHIFT_ENABLED",
            reason = "止めても PUBLISHED のまま滞留するだけで既存行は壊れず、再開後の同一バッチが終了 7 日超過という同じ条件で取りこぼしなく拾い直す")
    @BatchEndpoint(name = "shift-auto-archive-daily", description = "終了 7 日経過の PUBLISHED シフトを毎日 03:00 に ARCHIVED へ遷移する")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_auto_archive", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void runArchive() {
        log.info("シフト自動アーカイブバッチ開始");
        LocalDate cutoff = LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(ARCHIVE_AFTER_DAYS);
        List<ShiftScheduleEntity> targets = scheduleRepository
                .findPublishedExpiredBefore(cutoff, PageRequest.of(0, BATCH_SIZE));

        int archived = 0;
        int skipped = 0;

        for (ShiftScheduleEntity schedule : targets) {
            try {
                schedule.archive();
                scheduleRepository.save(schedule);

                // ARCHIVED 遷移時に OPEN 変更依頼を自動 WITHDRAWN 化
                int withdrawn = changeRequestRepository.withdrawOpenRequestsByScheduleId(
                        schedule.getId(), LocalDateTime.now());
                if (withdrawn > 0) {
                    log.info("OPEN 変更依頼を自動 WITHDRAWN: scheduleId={}, 件数={}", schedule.getId(), withdrawn);
                }

                // Phase 4-γ: ShiftArchivedEvent を発行して紐づく Todo を自動 CANCELLED にする
                eventPublisher.publishEvent(new ShiftArchivedEvent(
                        schedule.getId(), schedule.getTeamId(), null));
                archived++;
                log.info("シフト自動アーカイブ: scheduleId={}, endDate={}", schedule.getId(), schedule.getEndDate());

            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                // 楽観ロック競合はスキップして次回バッチで再処理
                skipped++;
                log.warn("楽観ロック競合によりスキップ: scheduleId={}", schedule.getId());
            } catch (Exception e) {
                skipped++;
                log.error("自動アーカイブ失敗: scheduleId={}", schedule.getId(), e);
            }
        }

        log.info("シフト自動アーカイブバッチ完了: archived={}, skipped={}, 対象={}", archived, skipped, targets.size());
    }
}
