package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F03.11 Phase 5b: ペナルティ再計算バッチ。
 *
 * <p>異議申立 REVOKED 反映などのため、アクティブペナルティの有効性を再判定する。
 * 閾値を下回った場合は DISPUTE_REVOKED として自動解除する。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentPenaltyRecomputeBatch {

    private final RecruitmentUserPenaltyRepository penaltyRepository;
    private final RecruitmentPenaltySettingRepository settingRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;

    /**
     * 毎日 04:00 JST (= 19:00 UTC) に実行。
     */
    @BatchEndpoint(name = "recruitment-penalty-recompute-daily", description = "募集ペナルティの有効性を毎日 04:00 に再判定する")
    @Scheduled(cron = "0 0 19 * * *")
    @SchedulerLock(name = "recruitment-penalty-recompute-batch", lockAtMostFor = "50m", lockAtLeastFor = "5m")
    @Transactional
    public void recomputePenalties() {
        final int CHUNK_SIZE = 500;
        LocalDateTime now = LocalDateTime.now();
        int revoked = 0;

        // アクティブペナルティをチャンク処理で走査（500件ずつページング取得）
        // アクティブ条件: liftedAt IS NULL かつ expiresAt > now
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<RecruitmentUserPenaltyEntity> penaltyPage;
        do {
            penaltyPage = penaltyRepository.findActivePenaltiesPage(now, pageable);
            List<RecruitmentUserPenaltyEntity> toSave = new ArrayList<>();

            for (RecruitmentUserPenaltyEntity penalty : penaltyPage.getContent()) {
                // ペナルティ設定を取得
                RecruitmentPenaltySettingEntity setting = settingRepository
                        .findById(penalty.getTriggeredBySettingId())
                        .orElse(null);
                if (setting == null || !setting.isEnabled()) {
                    // 設定が無効化された場合は解除
                    penalty.lift(null, PenaltyLiftReason.DISPUTE_REVOKED);
                    toSave.add(penalty);
                    revoked++;
                    continue;
                }

                // 集計期間内の有効 NO_SHOW 件数を再計算
                LocalDateTime since = now.minusDays(setting.getThresholdPeriodDays());
                long currentCount = noShowRepository.countConfirmedNoShows(penalty.getUserId(), since);

                if (currentCount < setting.getThresholdCount()) {
                    // 閾値を下回った → ペナルティ解除
                    penalty.lift(null, PenaltyLiftReason.DISPUTE_REVOKED);
                    toSave.add(penalty);
                    revoked++;
                    log.info("F03.11 Phase5b ペナルティ再計算解除: penaltyId={}, userId={}, noShowCount={}",
                            penalty.getId(), penalty.getUserId(), currentCount);
                }
            }

            if (!toSave.isEmpty()) {
                penaltyRepository.saveAll(toSave);
            }
            pageable = pageable.next();
        } while (penaltyPage.hasNext());

        // TODO: F04.9 実装後に解除対象ユーザーへ RECRUITMENT_PENALTY_LIFTED 通知
        log.info("F03.11 Phase5b ペナルティ再計算バッチ完了: revoked={}件", revoked);
    }
}
