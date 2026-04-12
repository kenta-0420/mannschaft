package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.11 Phase5a: キャンセル料の決済リトライバッチ。
 * payment_status = 'FAILED' かつリトライ回数3回未満のレコードを最大3回まで再試行する。
 * 仕様書 §11（バッチ一覧）参照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentPaymentRetryBatch {

    private static final int MAX_RETRY_COUNT = 3;
    private static final int CHUNK_SIZE = 50;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;

    /**
     * 1時間ごとに実行。ShedLock で重複実行を防止。
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @SchedulerLock(
            name = "recruitment-payment-retry-batch",
            lockAtLeastFor = "PT50M",
            lockAtMostFor = "PT2H"
    )
    public void run() {
        log.info("F03.11 決済リトライバッチ 開始");
        int page = 0;
        int totalProcessed = 0;
        int totalSuccess = 0;
        int totalFailed = 0;

        while (true) {
            Page<RecruitmentCancellationRecordEntity> chunk =
                    cancellationRecordRepository.findFailedForRetry(
                            MAX_RETRY_COUNT, PageRequest.of(page, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (RecruitmentCancellationRecordEntity record : chunk.getContent()) {
                boolean success = processRetry(record);
                totalProcessed++;
                if (success) totalSuccess++; else totalFailed++;
            }

            if (!chunk.hasNext()) {
                break;
            }
            page++;
        }

        log.info("F03.11 決済リトライバッチ 完了: 処理件数={}, 成功={}, 失敗={}",
                totalProcessed, totalSuccess, totalFailed);
    }

    /**
     * 1件のキャンセル記録に対してリトライを実行する。
     * 決済API統合は F03.4 決済システムとの別途調整が必要なため、
     * 現フェーズではリトライカウントのみインクリメントし、ログに記録する（スタブ）。
     *
     * @return true: 決済成功（将来実装）/ false: 失敗またはスタブ
     */
    @Transactional
    public boolean processRetry(RecruitmentCancellationRecordEntity record) {
        try {
            log.info("F03.11 決済リトライ: recordId={}, retryCount={}/{}",
                    record.getId(), record.getPaymentRetryCount() + 1, MAX_RETRY_COUNT);

            // TODO: F03.4 決済APIとの統合実装
            // 現フェーズではスタブ: リトライカウントをインクリメントして記録
            record.incrementRetryCount();
            cancellationRecordRepository.save(record);

            // MAX到達時は管理者通知（TODO: 通知API統合）
            if (record.getPaymentRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("F03.11 決済リトライ上限到達: recordId={}, userId={}, feeAmount={}",
                        record.getId(), record.getUserId(), record.getFeeAmount());
            }

            return false; // スタブのため常にfalse
        } catch (Exception e) {
            log.error("F03.11 決済リトライ エラー: recordId={}", record.getId(), e);
            return false;
        }
    }
}
