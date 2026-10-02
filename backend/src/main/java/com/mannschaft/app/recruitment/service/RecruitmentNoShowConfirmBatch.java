package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository.PenaltySourceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;

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
    private final RecruitmentPenaltyService penaltyService;

    private record PenaltyCandidate(Long userId, RecruitmentScopeType scopeType, Long scopeId) {
    }

    /**
     * 毎時0分に実行。24h 経過した仮マーク NO_SHOW を確定する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_RECRUITMENT_ENABLED",
            reason = "仮マークは DB に残り 24h 経過という時刻条件で再判定できるため、止めても確定が遅れるだけで既存行は失われない")
    @BatchEndpoint(name = "recruitment-no-show-confirm-hourly", description = "24h 経過した NO_SHOW 仮マークを毎時 0 分に確定する")
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "recruitment-no-show-confirm-batch", lockAtMostFor = "2h", lockAtLeastFor = "5m")
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
        noShowRepository.saveAllAndFlush(targets);

        Set<PenaltyCandidate> candidates = new HashSet<>();
        for (RecruitmentNoShowRecordEntity record : targets) {
            if (record.getDisputeResolution() == com.mannschaft.app.recruitment.DisputeResolution.REVOKED) {
                continue;
            }
            PenaltySourceScope source = noShowRepository.findPenaltySourceScope(record.getId()).orElse(null);
            if (source == null) {
                log.warn("NO_SHOW確定の募集スコープを取得できません: recordId={}", record.getId());
                continue;
            }
            RecruitmentScopeType scopeType = RecruitmentScopeType.valueOf(source.getScopeType());
            if (scopeType == RecruitmentScopeType.TEAM || scopeType == RecruitmentScopeType.ORGANIZATION) {
                candidates.add(new PenaltyCandidate(record.getUserId(), scopeType, source.getScopeId()));
            }
        }
        for (PenaltyCandidate candidate : candidates.stream()
                .sorted(Comparator.comparing(PenaltyCandidate::userId)
                        .thenComparing(candidate -> candidate.scopeType().name())
                        .thenComparing(PenaltyCandidate::scopeId))
                .toList()) {
            penaltyService.evaluateAndApplyPenalty(
                    candidate.userId(), candidate.scopeType(), candidate.scopeId());
        }

        log.info("F03.11 Phase5b NO_SHOW確定バッチ: confirmed={}件", confirmed);
    }
}
