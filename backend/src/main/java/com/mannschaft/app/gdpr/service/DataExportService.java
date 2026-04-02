package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.DataExportRequest;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPRデータエクスポートサービス。
 * データポータビリティ要求に基づくエクスポートジョブの管理・実行を担う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final com.mannschaft.app.gdpr.repository.DataExportRepository dataExportRepository;

    /**
     * データエクスポートジョブをリクエストする。
     * パスワードユーザーは再認証を行い、OAuthユーザーはOTPを送信して202を返す。
     *
     * @param userId  リクエストユーザーID
     * @param request エクスポートリクエスト
     * @return 作成されたエクスポートエンティティ
     */
    @Transactional
    public DataExportEntity requestExport(Long userId, DataExportRequest request) {
        // TODO: F12.3 足軽4（Service部隊）が実装予定
        // OAuthユーザー判定、パスワード再認証、OTP送信、ジョブ作成
        String categories = (request.getCategories() == null || request.getCategories().isEmpty())
                ? null
                : String.join(",", request.getCategories());
        DataExportEntity entity = DataExportEntity.builder()
                .userId(userId)
                .status("PENDING")
                .categories(categories)
                .build();
        return dataExportRepository.save(entity);
    }

    /**
     * エクスポートジョブを非同期で処理する。
     *
     * @param exportId エクスポートID
     */
    @Async
    @Transactional
    public void processExportAsync(Long exportId) {
        // TODO: F12.3 足軽4（Service部隊）が実装予定
        log.info("データエクスポート処理開始: exportId={}", exportId);
    }

    /**
     * エクスポートジョブの現在ステータスを取得する。
     *
     * @param userId ユーザーID
     * @return エクスポートエンティティ
     */
    @Transactional(readOnly = true)
    public DataExportEntity getExportStatus(Long userId) {
        // TODO: F12.3 足軽4（Service部隊）が実装予定
        return dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.gdpr.GdprErrorCode.GDPR_003));
    }

    /**
     * 完了済みエクスポートのダウンロードURLを返す。
     *
     * @param userId ユーザーID
     * @return 署名付きダウンロードURL
     */
    @Transactional(readOnly = true)
    public String getDownloadUrl(Long userId) {
        // TODO: F12.3 足軽4（Service部隊）が実装予定
        DataExportEntity entity = getExportStatus(userId);
        if (!"COMPLETED".equals(entity.getStatus())) {
            throw new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.gdpr.GdprErrorCode.GDPR_002);
        }
        return "https://placeholder-download-url/" + entity.getId();
    }
}
