package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 Phase 5b: NO_SHOW 確定バッチ。
 *
 * <p>仮マーク（confirmed=FALSE）から 24 時間経過した記録を confirmed=TRUE に確定する。
 * 誤判定対策のため 24h の猶予期間を設けている。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentNoShowConfirmBatch {

    private final RecruitmentNoShowRecordRepository noShowRepository;

    /**
     * 毎時0分に実行。24h 経過した仮マーク NO_SHOW を確定する。
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "recruitment-no-show-confirm-batch", lockAtMostFor = "55m", lockAtLeastFor = "5m")
    @Transactional
    public void confirmNoShows() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        List<RecruitmentNoShowRecordEntity> targets = noShowRepository.findUnconfirmedBefore(threshold);

        if (targets.isEmpty()) {
            return;
        }

        int confirmed = 0;
        for (RecruitmentNoShowRecordEntity record : targets) {
            record.confirm();
            confirmed++;
        }
        noShowRepository.saveAll(targets);

        log.info("F03.11 Phase5b NO_SHOW確定バッチ: confirmed={}件", confirmed);
    }
}
