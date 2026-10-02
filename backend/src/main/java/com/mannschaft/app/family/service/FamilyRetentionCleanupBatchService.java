package com.mannschaft.app.family.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ファミリー領域の保持期間超過データ削除バッチ。
 *
 * <h2>なぜ {@link FamilyBatchService} から切り出したのか（Gate 基盤工事④-B 第三陣）</h2>
 * <p>元は通知系バッチと同じクラスに同居していたが、止めてよいかの判定が正反対である。</p>
 * <ul>
 *   <li><b>通知系（帰宅遅延チェック・記念日通知）</b> — 閉栓中に送る意味が無く、
 *       送らなくても既存データは壊れない。よって {@code SKIP_WHEN_DISABLED}。</li>
 *   <li><b>保持期間超過削除（本クラス）</b> — 止めると保持期限を超えた
 *       プレゼンス（所在）履歴・コイントス履歴が残留する。
 *       禁止域の {@code DisclosureAutoDeleteBatchService} と同種であり {@code ALWAYS}。</li>
 * </ul>
 *
 * <p><b>クラスを分けた理由</b>: 番人 {@code BackgroundEntryPolicyDeclarationGuardTest} の
 * 禁止域 {@code FORBIDDEN_TO_STOP} は<b>クラス単位</b>で照合するため、
 * 1 クラス内に {@code SKIP} と {@code ALWAYS} が同居していると
 * そのクラスを禁止域へ登録できない（登録すると {@code SKIP} 側が違反になる）。
 * すなわち「止めてはならぬ」側を番人で守れない。切り出すことで登録可能にし、
 * 将来 {@code SKIP_WHEN_DISABLED} へ書き換えられても CI が止める。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyRetentionCleanupBatchService {

    private static final int PRESENCE_RETENTION_DAYS = 90;
    private static final int COIN_TOSS_RETENTION_DAYS = 30;

    private final PresenceEventRepository presenceEventRepository;
    private final CoinTossResultRepository coinTossResultRepository;

    /**
     * 保持期間を超えたプレゼンス・コイントス履歴を削除する（毎日 04:00 JST）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると保持期間を超えたプレゼンス（所在）履歴とコイントス履歴が削除されず、保持期限を超えた個人データが残留する。DisclosureAutoDeleteBatchService と同種の保持期間超過削除である")
    @BatchEndpoint(name = "family-presence-cleanup-daily",
            description = "プレゼンス・コイントス履歴の保持期間超過を毎日 04:00 にクリーンアップする")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 04:00。保持期間を超えたプレゼンス・コイントス履歴の一括 DELETE のみで最悪ケースでも数分。
    // 初回実行時の積み残しを見込み 30 分を上限とする。
    @SchedulerLock(name = "familyPresenceCleanupDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupOldRecords() {
        LocalDateTime presenceThreshold = LocalDateTime.now().minusDays(PRESENCE_RETENTION_DAYS);
        presenceEventRepository.deleteByCreatedAtBefore(presenceThreshold);
        LocalDateTime coinTossThreshold = LocalDateTime.now().minusDays(COIN_TOSS_RETENTION_DAYS);
        coinTossResultRepository.deleteByCreatedAtBefore(coinTossThreshold);
        log.info("クリーンアップバッチ完了: プレゼンス({}日以前), コイントス({}日以前)",
                PRESENCE_RETENTION_DAYS, COIN_TOSS_RETENTION_DAYS);
    }
}
