package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
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
import org.springframework.data.domain.PageRequest;
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
 *
 * <h2>ページングの注意</h2>
 * <p>アクティブペナルティの走査は <b>キーセットページング</b>（{@code id > cursor}）で行う。
 * ループ内で解除条件を満たした行は {@code liftedAt} がセットされ、次回の絞り込み
 * （{@code liftedAt IS NULL}）から即座に外れる。母集合が縮んでいくため、OFFSET を
 * 「取得件数ぶん進める」方式（{@code Pageable#next()}）や<b>ページ0固定のドレイン方式</b>
 * にすると、いずれも正しく動作しない。
 * <ul>
 *   <li>OFFSET 前進方式: 縮んだ分だけ後続の行が OFFSET の網から漏れ、解除すべき
 *       ペナルティが解除されないまま取りこぼされる</li>
 *   <li>ページ0固定方式: 解除されない行（閾値を下回らない行）はいつまでも絞り込みに
 *       残り続けるため、無限ループになる（ページ0固定は「処理した行が必ず絞り込みから
 *       外れる」バッチにしか使えない）</li>
 * </ul>
 * <p>カーソルを直前チャンクの最終 {@code id} まで前進させるキーセット方式のみが、
 * 縮む母集合でも取りこぼしなく・無限ループにもならず全件を走査できる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentPenaltyRecomputeBatch {

    private static final int CHUNK_SIZE = 500;

    /** 安全弁: 想定外の滞留でバッチが無限に回り続けることを防ぐループ回数上限。 */
    private static final int MAX_PAGES = 200;

    private final RecruitmentUserPenaltyRepository penaltyRepository;
    private final RecruitmentPenaltySettingRepository settingRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;

    /**
     * 毎日 04:00 JST (= 19:00 UTC) に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_RECRUITMENT_ENABLED",
            reason = "有効性の再判定は元データから何度でもやり直せる冪等処理であり、止めても元のペナルティ行は一切壊れない")
    @BatchEndpoint(name = "recruitment-penalty-recompute-daily", description = "募集ペナルティの有効性を毎日 04:00 に再判定する")
    @Scheduled(cron = "0 0 19 * * *")
    @SchedulerLock(name = "recruitment-penalty-recompute-batch", lockAtMostFor = "50m", lockAtLeastFor = "5m")
    @Transactional
    public void recomputePenalties() {
        LocalDateTime now = LocalDateTime.now();
        int revoked = 0;
        int scanned = 0;
        long cursor = 0L;
        int page = 0;

        for (; page < MAX_PAGES; page++) {
            List<RecruitmentUserPenaltyEntity> chunk =
                    penaltyRepository.findActivePenaltiesAfterId(now, cursor, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            List<RecruitmentUserPenaltyEntity> toSave = new ArrayList<>();
            for (RecruitmentUserPenaltyEntity penalty : chunk) {
                scanned++;

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

            // カーソルを直前チャンクの最終 id まで前進させる（キーセットページング）
            cursor = chunk.get(chunk.size() - 1).getId();

            if (chunk.size() < CHUNK_SIZE) {
                break;
            }
        }

        if (page >= MAX_PAGES) {
            log.warn("F03.11 Phase5b ペナルティ再計算バッチ: MAX_PAGES({})に到達し打ち切り。未走査の行が残っている可能性がある。scanned={}件, revoked={}件",
                    MAX_PAGES, scanned, revoked);
        }

        // TODO: F04.9 実装後に解除対象ユーザーへ RECRUITMENT_PENALTY_LIFTED 通知
        log.info("F03.11 Phase5b ペナルティ再計算バッチ完了: scanned={}件, revoked={}件", scanned, revoked);
    }
}
