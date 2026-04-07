package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.S3StorageService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoAttachmentRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 論理削除されたポイっとメモの物理削除バッチ。
 * 毎日深夜3時に実行。90日以上前に削除されたメモを物理削除する。
 * 重要: S3削除は同期実行（データ漏洩防止のため非同期イベント発行禁止）。
 * usage_count は集計後に一度の UPDATE（NHH4対応）。
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
    private final S3StorageService s3StorageService;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "0 0 3 * * *")
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

        // タグ usage_count を集計後に一括デクリメント（NHH4: 複数メモが同じタグを参照する場合の正確さ）
        Map<Long, Long> tagDecrements = tagLinkRepository.findByMemoIdIn(memoIds).stream()
                .collect(Collectors.groupingBy(link -> link.getTagId(), Collectors.counting()));

        for (Map.Entry<Long, Long> entry : tagDecrements.entrySet()) {
            tagRepository.decrementUsageCountBy(entry.getKey(), entry.getValue().intValue());
        }

        // メモを物理削除（FK CASCADE で attachments・tag_links も削除）
        memoRepository.deleteAllById(memoIds);

        log.info("物理削除バッチ完了: {}件, S3削除{}件", memoIds.size(), s3Keys.size());
        auditLogService.record("QUICK_MEMO_PHYSICAL_DELETE_BATCH", null, null, null, null, null, null, null,
                "{\"deletedMemos\":" + memoIds.size() + ",\"deletedS3Objects\":" + s3Keys.size() + "}");
    }
}
