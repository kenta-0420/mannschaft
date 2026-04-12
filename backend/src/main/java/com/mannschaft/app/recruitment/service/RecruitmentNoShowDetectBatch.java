package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.NoShowReason;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 Phase 5b: NO_SHOW 自動検出バッチ。
 *
 * <p>auto_no_show_detection=TRUE の設定があるスコープについて、
 * 開催終了から 24h 経過した時点でまだ CONFIRMED のままの参加者を自動 NO_SHOW マークする。</p>
 *
 * <p>マークは仮マーク（confirmed=FALSE）として記録され、
 * {@link RecruitmentNoShowConfirmBatch} によって 24h 後に確定される。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentNoShowDetectBatch {

    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;
    private final RecruitmentPenaltySettingRepository settingRepository;

    /**
     * 毎時30分に実行（confirmバッチと時間をずらす）。
     */
    @Scheduled(cron = "0 30 * * * *")
    @SchedulerLock(name = "recruitment-no-show-detect-batch", lockAtMostFor = "55m", lockAtLeastFor = "5m")
    @Transactional
    public void detectNoShows() {
        // 自動検出が有効な設定を取得
        List<RecruitmentPenaltySettingEntity> autoDetectSettings =
                settingRepository.findAll().stream()
                        .filter(RecruitmentPenaltySettingEntity::isAutoNoShowDetection)
                        .toList();

        if (autoDetectSettings.isEmpty()) {
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        int detected = 0;

        for (RecruitmentPenaltySettingEntity setting : autoDetectSettings) {
            // このスコープの終了済み募集で CONFIRMED のままの参加者を抽出
            List<RecruitmentParticipantEntity> targets =
                    participantRepository.findConfirmedInEndedListings(
                            setting.getScopeType(), setting.getScopeId(), threshold);

            for (RecruitmentParticipantEntity participant : targets) {
                // 既に NO_SHOW 記録があればスキップ
                if (noShowRepository.findByParticipantId(participant.getId()).isPresent()) {
                    continue;
                }

                // NO_SHOW マーク（仮マーク = confirmed=false）
                participant.markNoShow();
                participantRepository.save(participant);

                RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                        .participantId(participant.getId())
                        .listingId(participant.getListingId())
                        .userId(participant.getUserId())
                        .reason(NoShowReason.AUTO_DETECTED)
                        .recordedBy(null)
                        .build();
                noShowRepository.save(record);
                detected++;
            }
        }

        if (detected > 0) {
            log.info("F03.11 Phase5b NO_SHOW自動検出バッチ: detected={}件", detected);
        }
    }
}
