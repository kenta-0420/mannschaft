package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.residencestatus.repository.ResidentActivitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 居住者アクティビティ snapshot ローテーション用のバッチ単位 UPDATE 実行 Bean（Issue #2601）。
 *
 * <p>{@link ResidentActivityAggregatorService#deleteOldSnapshots()} からループで呼ばれる。
 * 1 バッチ = 1 トランザクションとするため、独立した Bean に切り出し
 * {@link Propagation#REQUIRES_NEW} を付与する（同一 Bean 内の自己呼び出しでは
 * プロキシを経由せず伝播設定が効かないため）。
 */
@Component
@RequiredArgsConstructor
class ResidentActivitySnapshotBatchDeleter {

    private final ResidentActivitySnapshotRepository snapshotRepo;

    /**
     * cutoff より古い未削除 snapshot を最大 batchSize 件、独立トランザクションで論理削除する。
     *
     * @return 実際に更新された件数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(LocalDate cutoff, int batchSize) {
        return snapshotRepo.softDeleteBatchOlderThan(cutoff, LocalDateTime.now(), batchSize);
    }
}
