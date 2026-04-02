package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F12.3 データエクスポートサービス。
 * GDPRデータポータビリティ要求に基づくエクスポートジョブを管理する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DataExportService {

    private final DataExportRepository dataExportRepository;
    private final StorageService storageService;

    /** レートリミット: 24時間以内に完了したエクスポートが存在する場合はリクエスト不可 */
    private static final long RATE_LIMIT_HOURS = 24;

    /** スタック判定: 1時間以上PROCESSINGのままの場合はFAILEDにリセット */
    private static final long STUCK_THRESHOLD_HOURS = 1;

    /**
     * データエクスポートをリクエストする。
     *
     * @param userId     ユーザーID
     * @param categories エクスポートカテゴリ（nullで全カテゴリ）
     * @return 作成されたDataExportEntity
     * @throws BusinessException GDPR_001: 24時間以内にCOMPLETEDが存在する
     * @throws BusinessException GDPR_002: PROCESSINGが存在する
     */
    @Transactional
    public DataExportEntity requestExport(Long userId, String categories) {
        Optional<DataExportEntity> latestOpt = dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(userId);

        if (latestOpt.isPresent()) {
            DataExportEntity latest = latestOpt.get();

            // PROCESSING中のジョブが存在する場合はGDPR_002
            if ("PROCESSING".equals(latest.getStatus())) {
                throw new BusinessException(GdprErrorCode.GDPR_002);
            }

            // 24時間以内にCOMPLETEDが存在する場合はGDPR_001
            if ("COMPLETED".equals(latest.getStatus()) && latest.getCompletedAt() != null
                    && latest.getCompletedAt().isAfter(LocalDateTime.now().minusHours(RATE_LIMIT_HOURS))) {
                throw new BusinessException(GdprErrorCode.GDPR_001);
            }
        }

        DataExportEntity entity = DataExportEntity.builder()
                .userId(userId)
                .status("PENDING")
                .categories(categories)
                .build();

        return dataExportRepository.save(entity);
    }

    /**
     * 最新のエクスポートステータスを取得する。
     *
     * @param userId ユーザーID
     * @return 最新のDataExportEntity
     * @throws BusinessException GDPR_003: エクスポートが存在しない
     */
    public DataExportEntity getExportStatus(Long userId) {
        return dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new BusinessException(GdprErrorCode.GDPR_003));
    }

    /**
     * スタックしたPROCESSINGジョブをFAILEDにリセットする（スケジューラから呼ばれる）。
     */
    @Transactional
    public int recoverStuckExports() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(STUCK_THRESHOLD_HOURS);
        int count = dataExportRepository.resetStuckProcessing(threshold, "タイムアウトによる自動リカバリー");
        if (count > 0) {
            log.warn("スタックしたエクスポートをリセット: {}件", count);
        }
        return count;
    }

    /**
     * 期限切れZIPをS3から削除する（スケジューラから呼ばれる）。
     */
    @Transactional
    public void cleanupExpiredExports() {
        List<DataExportEntity> expired = dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(LocalDateTime.now());
        for (DataExportEntity entity : expired) {
            try {
                storageService.delete(entity.getS3Key());
                entity.clearS3Key();
                dataExportRepository.save(entity);
                log.info("期限切れエクスポートのS3キーを削除: id={}", entity.getId());
            } catch (Exception e) {
                log.error("S3削除失敗: id={}", entity.getId(), e);
            }
        }
    }
}
