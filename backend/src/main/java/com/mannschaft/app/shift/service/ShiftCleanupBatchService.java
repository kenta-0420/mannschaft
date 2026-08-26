package com.mannschaft.app.shift.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * シフトクリーンアップバッチサービス。
 * バッチ#2: PENDING スワップ申請を 48h 後に自動キャンセル（通知付き）。
 * バッチ#3: ARCHIVED から 30 日経過したシフト希望を物理削除。
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット1 による是正</h2>
 * <p>{@code runSwapExpiryCancel} は<b>バッチ全体を 1 つの {@code @Transactional} で包みながら
 * ループ内で 1 件ずつ catch</b> していた。1 件の失敗は握りつぶされたように見えて、実際には
 * rollback-only が残るためコミット時に<b>全件のキャンセルが巻き戻っていた</b>。
 * 非トランザクションのオーケストレータ ＋ 項目ごと {@link ShiftSwapExpiryRunner}
 * （{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code shift_swap_requests.status} → CANCELLED）を更新する</b>。
 * よって確定設計の「バッチで業務状態も更新する」に該当し、非TXループ → 項目ごと REQUIRES_NEW →
 * その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>{@code runRequestCleanup} は是正対象外</h2>
 * <p>こちらは <b>1 本のバルク DELETE のみ</b>でループも通知も持たない。単一のトランザクションで
 * まとめてコミットすることが正しい形であり、Issue #2834 の欠陥（ループ内 catch）は存在しないため
 * {@code @Transactional} をそのまま維持する。</p>
 *
 * <h2>外向き契約</h2>
 * <p>両メソッドとも是正前後で戻り値 {@code void}。{@code @BatchEndpoint} 経由の管理コンソール実行も
 * 戻り値を持たないため、FE / OpenAPI への波及はない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftCleanupBatchService {

    private static final int BATCH_SIZE = 100;

    private final ShiftSwapRequestRepository swapRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftRequestRepository requestRepository;
    private final ShiftSwapExpiryRunner shiftSwapExpiryRunner;

    /**
     * 毎日 AM 3:00（JST）に実行。48h 経過した PENDING スワップ申請を
     * 1 件ずつ独立トランザクションで自動キャンセルする。
     */
    @BatchEndpoint(name = "shift-swap-expiry-cancel-daily", description = "48h 経過した PENDING スワップ申請を毎日 03:00 に自動キャンセルする")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_swap_expiry_cancel", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void runSwapExpiryCancel() {
        log.info("スワップ申請期限切れキャンセルバッチ開始");
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).minusHours(48);
        // 対象抽出はオーケストレータ側（TX 外）。以降の更新はここには参加しない。
        List<Long> targetIds = swapRepository
                .findExpiredPendingBefore(cutoff, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(ShiftSwapRequestEntity::getId)
                .toList();

        int cancelled = 0;
        int skipped = 0;
        int failed = 0;
        Long firstFailedSwapId = null;

        for (Long swapId : targetIds) {
            try {
                if (shiftSwapExpiryRunner.cancelOne(swapId)) {
                    cancelled++;
                    log.info("スワップ申請自動キャンセル: swapId={}", swapId);
                } else {
                    // 抽出後に成立・辞退で PENDING でなくなっていた（再実行時も同じ経路に入る）。
                    skipped++;
                }
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                // 楽観ロック競合。是正前と同じく「スキップして次へ」だが、
                // 今回は本当に当該 1 件だけが巻き戻る（他の申請は既にコミット済み）。
                skipped++;
                log.warn("楽観ロック競合によりスキップ: swapId={}", swapId);
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                failed++;
                if (firstFailedSwapId == null) {
                    firstFailedSwapId = swapId;
                }
                log.error("スワップ申請キャンセル失敗: swapId={}", swapId, e);
            }
        }

        String summary = "スワップ申請期限切れキャンセルバッチ完了: 対象={}, cancelled={}, skipped={}, failed={}, "
                + "firstFailedSwapId={}";
        if (failed > 0) {
            log.error(summary, targetIds.size(), cancelled, skipped, failed, firstFailedSwapId);
        } else {
            log.info(summary, targetIds.size(), cancelled, skipped, failed, firstFailedSwapId);
        }
    }

    /**
     * 毎日 AM 3:05（JST）に実行。ARCHIVED から 30 日経過したシフト希望を物理削除する。
     *
     * <p>ループも通知も持たない単一のバルク DELETE であり、まとめて 1 トランザクションでコミットするのが
     * 正しい形のため {@code @Transactional} を維持する（Issue #2834 の是正対象外）。</p>
     */
    @BatchEndpoint(name = "shift-request-cleanup-daily", description = "ARCHIVED から 30 日経過のシフト希望を毎日 03:05 に物理削除する")
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_request_cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void runRequestCleanup() {
        log.info("シフト希望物理削除バッチ開始");
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).minusDays(30);
        List<Long> scheduleIds = scheduleRepository
                .findArchivedScheduleIdsOlderThan(cutoff, PageRequest.of(0, BATCH_SIZE));

        if (scheduleIds.isEmpty()) {
            log.info("シフト希望物理削除バッチ完了: 対象スケジュールなし");
            return;
        }

        int deleted = requestRepository.deleteByScheduleIds(scheduleIds);
        log.info("シフト希望物理削除バッチ完了: scheduleIds={}, 削除件数={}", scheduleIds.size(), deleted);
    }
}
