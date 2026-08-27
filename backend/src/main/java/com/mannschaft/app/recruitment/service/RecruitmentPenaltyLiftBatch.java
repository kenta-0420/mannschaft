package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 Phase 5b: ペナルティ自動解除バッチ。
 *
 * <p>期限切れ（expires_at 過去）かつ未解除のペナルティを AUTO_EXPIRED として自動解除する。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentPenaltyLiftBatch {

    private final RecruitmentUserPenaltyRepository penaltyRepository;

    /**
     * 毎日 03:00 JST (= 18:00 UTC) に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_RECRUITMENT_ENABLED",
            reason = "止めるとペナルティ解除が遅れるが、募集機能を閉じている間はペナルティが行使される場面が無く、再開後に期限切れ分をまとめて解除できる")
    @BatchEndpoint(name = "recruitment-penalty-lift-daily", description = "期限切れの募集ペナルティを毎日 03:00 に AUTO_EXPIRED で自動解除する")
    @Scheduled(cron = "0 0 18 * * *")
    @SchedulerLock(name = "recruitment-penalty-lift-batch", lockAtMostFor = "50m", lockAtLeastFor = "5m")
    @Transactional
    public void liftExpiredPenalties() {
        LocalDateTime now = LocalDateTime.now();
        List<RecruitmentUserPenaltyEntity> expired = penaltyRepository.findExpiredPenalties(now);

        if (expired.isEmpty()) {
            return;
        }

        int lifted = 0;
        for (RecruitmentUserPenaltyEntity penalty : expired) {
            penalty.lift(null, PenaltyLiftReason.AUTO_EXPIRED);
            lifted++;
        }
        penaltyRepository.saveAll(expired);

        // TODO: F04.9 実装後に RECRUITMENT_PENALTY_LIFTED 通知を送信
        log.info("F03.11 Phase5b ペナルティ自動解除バッチ: lifted={}件", lifted);
    }
}
