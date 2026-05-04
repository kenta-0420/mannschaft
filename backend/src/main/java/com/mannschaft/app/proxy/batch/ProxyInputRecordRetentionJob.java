package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 代理入力記録の保管期限管理バッチ（F14.1 Phase 13-γ）。
 * 毎月1日 04:00 JST に実行し、保管期限（作成日+5年）を超過したレコードを物理削除する。
 * 区分所有法・標準管理規約に基づく法定保管期限の遵守が目的。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyInputRecordRetentionJob {

    /** バッチサイズ（1回の削除件数上限。大量削除によるロック競合を防ぐ）。 */
    private static final int BATCH_SIZE = 500;

    private final ProxyInputRecordRepository recordRepository;

    /**
     * 毎月1日 04:00 JST に実行する。
     * ShedLock により複数インスタンス環境でも1回だけ実行されることを保証する。
     */
    @Scheduled(cron = "0 0 4 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ProxyInputRecordRetentionJob", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void run() {
        LocalDate today = LocalDate.now();
        log.info("ProxyInputRecordRetentionJob 開始: 基準日={}", today);
        int total = deleteExpired(today);
        log.info("ProxyInputRecordRetentionJob 完了: 物理削除 {}件", total);
    }

    /**
     * 保管期限切れレコードをバッチサイズ単位で物理削除する。
     *
     * @param cutoffDate この日以前に期限が切れたレコードを削除する
     * @return 削除した総件数
     */
    @Transactional
    int deleteExpired(LocalDate cutoffDate) {
        List<Long> expiredIds = recordRepository.findExpiredRecordIds(cutoffDate);
        if (expiredIds.isEmpty()) {
            log.info("保管期限切れレコードなし");
            return 0;
        }

        int total = 0;
        // 大量削除時にロックが長時間かかるのを避けるためバッチ分割する
        for (int i = 0; i < expiredIds.size(); i += BATCH_SIZE) {
            List<Long> batch = expiredIds.subList(i, Math.min(i + BATCH_SIZE, expiredIds.size()));
            recordRepository.deleteByIdIn(batch);
            total += batch.size();
            log.debug("削除バッチ完了: {}/{}", total, expiredIds.size());
        }
        return total;
    }
}
