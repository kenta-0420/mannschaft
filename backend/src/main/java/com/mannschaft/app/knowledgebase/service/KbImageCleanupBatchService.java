package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.knowledgebase.entity.KbImageUploadEntity;
import com.mannschaft.app.knowledgebase.repository.KbImageUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ナレッジベース孤立画像クリーンアップバッチサービス。
 * ページに紐付かない72時間以上経過した画像をS3から削除する。
 * 毎日午前2時（JST）に実行される。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbImageCleanupBatchService {

    /** 孤立画像の保持時間: 72時間 */
    private static final long ORPHAN_THRESHOLD_HOURS = 72L;

    private final KbImageUploadRepository imageUploadRepository;
    private final StorageService storageService;

    /**
     * 孤立画像クリーンアップを実行する。
     * kb_page_id IS NULL かつ created_at < NOW() - 72時間 の画像を削除する。
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "kb_image_cleanup", lockAtMostFor = "PT15M")
    @Transactional
    public void runCleanup() {
        log.info("KB孤立画像クリーンアップバッチ開始");

        LocalDateTime threshold = LocalDateTime.now().minusHours(ORPHAN_THRESHOLD_HOURS);
        List<KbImageUploadEntity> orphanImages =
                imageUploadRepository.findByKbPageIdIsNullAndCreatedAtBefore(threshold);

        if (orphanImages.isEmpty()) {
            log.info("KB孤立画像クリーンアップバッチ完了: 削除対象なし");
            return;
        }

        int deletedCount = 0;
        int errorCount = 0;

        for (KbImageUploadEntity image : orphanImages) {
            try {
                // S3からオブジェクトを削除
                storageService.delete(image.getS3Key());
                log.debug("S3画像を削除しました: s3Key={}", image.getS3Key());
            } catch (Exception e) {
                log.warn("S3画像の削除に失敗しました: s3Key={}, error={}", image.getS3Key(), e.getMessage());
                errorCount++;
                continue;
            }

            // DBレコードを物理削除
            imageUploadRepository.delete(image);
            deletedCount++;
        }

        log.info("KB孤立画像クリーンアップバッチ完了: 削除件数={}, エラー件数={}", deletedCount, errorCount);
    }
}
