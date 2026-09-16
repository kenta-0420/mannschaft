package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.entity.QuickMemoTagLinkEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoAttachmentRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 論理削除されたポイっとメモの物理削除バッチ。
 * 毎日深夜3時に実行。90日以上前に削除されたメモを物理削除する。
 * 重要: S3削除は同期実行（データ漏洩防止のため非同期イベント発行禁止）。
 *
 * <p>タグ {@code usage_count} の減算量は「本実行が実際に削除したリンク行数」から導く。
 * 集計値をそのまま引くと、同じリンクを別実行も数えていた場合に同じ分が二重に引かれ、
 * usage_count が実態より少なくなる（{@code GREATEST(0, ...)} で 0 に張り付くと復元できない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickMemoPhysicalDeleteBatchService {

    private static final int BATCH_LIMIT = 50000;
    private static final int RETENTION_DAYS = 90;

    private final QuickMemoRepository memoRepository;
    private final QuickMemoAttachmentRepository attachmentRepository;
    private final QuickMemoTagLinkRepository tagLinkRepository;
    private final TagRepository tagRepository;
    private final R2StorageService s3StorageService;
    private final AuditLogService auditLogService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。論理削除済みメモの物理削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "quickmemo-physical-delete-daily", description = "90 日以上前に論理削除されたポイっとメモを毎日 03:00 に物理削除する")
    @Scheduled(cron = "0 0 3 * * *")
    // 起動間隔は日次 03:00。1 回の上限は BATCH_LIMIT = 50000 件で、その全件に対し S3 オブジェクト削除が同期で走る。
    // 最悪ケースを 1 件 0.2 秒 × 50000 件 ≒ 2.8 時間と見積もり 3 時間を上限とする。
    @SchedulerLock(name = "quickmemoPhysicalDeleteDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT3H")
    @Transactional
    public void execute() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        log.info("物理削除バッチ開始: threshold={}", threshold);

        List<QuickMemoEntity> expiredMemos = memoRepository
                .findExpiredDeletedMemos(threshold, PageRequest.of(0, BATCH_LIMIT));
        if (expiredMemos.isEmpty()) {
            return;
        }

        List<Long> memoIds = expiredMemos.stream().map(QuickMemoEntity::getId).toList();

        // S3オブジェクトを同期削除（必須: 非同期不可）
        List<String> s3Keys = attachmentRepository.findS3KeysByMemoIdIn(memoIds);
        for (String s3Key : s3Keys) {
            try {
                s3StorageService.delete(s3Key);
            } catch (Exception e) {
                log.error("S3削除失敗: s3Key={}, error={}", s3Key, e.getMessage());
            }
        }

        // タグ usage_count は「本実行が実際に削除したリンク行数」ぶんだけ減らす（claim-then-act）。
        // 集計値をそのまま引くと、同じリンクを別実行も数えていた場合に同じ分が二重に引かれ、
        // usage_count が実態より少なくなる（GREATEST(0, ...) で 0 に張り付くと復元もできない）。
        // 削除件数を減算量にすれば、リンク行の削除が行ロックで直列化される以上、
        // 全実行の減算量の合計は必ず実際のリンク数と一致する。
        Set<Long> tagIds = tagLinkRepository.findByMemoIdIn(memoIds).stream()
                .map(QuickMemoTagLinkEntity::getTagId)
                .collect(Collectors.toSet());
        for (Long tagId : tagIds) {
            int removedLinks = tagLinkRepository.deleteByMemoIdInAndTagId(memoIds, tagId);
            if (removedLinks > 0) {
                tagRepository.decrementUsageCountBy(tagId, removedLinks);
            }
        }

        // メモを物理削除（FK CASCADE で attachments も削除。tag_links は上で明示削除済み）
        memoRepository.deleteAllByIdInBatch(memoIds);

        log.info("物理削除バッチ完了: {}件, S3削除{}件", memoIds.size(), s3Keys.size());
        auditLogService.record("QUICK_MEMO_PHYSICAL_DELETE_BATCH", null, null, null, null, null, null, null,
                "{\"deletedMemos\":" + memoIds.size() + ",\"deletedS3Objects\":" + s3Keys.size() + "}");
    }
}
