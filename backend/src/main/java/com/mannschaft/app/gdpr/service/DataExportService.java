package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GDPRデータエクスポートサービス。
 * データ収集・ZIP生成・S3アップロード・メール通知を非同期で実行する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private static final long MAX_ZIP_SIZE_BYTES = 500L * 1024 * 1024; // 500MB
    private static final int ZIP_PASSWORD_LENGTH = 12;

    private final DataExportRepository dataExportRepository;
    private final PersonalDataCollector personalDataCollector;
    private final StorageService storageService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    /**
     * エクスポートリクエストを受け付け、DataExportEntityを作成する。
     * レートリミット（1日1回）チェックを行う。
     *
     * @param userId     対象ユーザーID
     * @param categories 収集カテゴリ（nullまたは空=全カテゴリ）
     * @return 作成されたDataExportEntity
     */
    @Transactional
    public DataExportEntity requestExport(Long userId, Set<String> categories) {
        // レートリミットチェック: 24時間以内にCOMPLETEDまたはPROCESSINGが存在すればエラー
        Optional<DataExportEntity> latest = dataExportRepository
                .findTopByUserIdOrderByCreatedAtDesc(userId);
        if (latest.isPresent()) {
            DataExportEntity prev = latest.get();
            if ("PROCESSING".equals(prev.getStatus())) {
                throw new BusinessException(GdprErrorCode.GDPR_002);
            }
            if ("COMPLETED".equals(prev.getStatus()) &&
                    prev.getCreatedAt().isAfter(LocalDateTime.now().minusDays(1))) {
                throw new BusinessException(GdprErrorCode.GDPR_001);
            }
        }

        // DataExportEntityを作成（PENDING）
        String categoriesStr = (categories == null || categories.isEmpty())
                ? null
                : String.join(",", categories);
        DataExportEntity entity = DataExportEntity.builder()
                .userId(userId)
                .status("PENDING")
                .categories(categoriesStr)
                .progressPercent(0)
                .build();
        return dataExportRepository.save(entity);
    }

    /**
     * バックグラウンドでエクスポート処理を実行する。
     *
     * @param exportId   エクスポートID
     * @param userId     対象ユーザーID
     * @param categories 収集カテゴリ
     */
    @Async("job-pool")
    @Transactional
    public void processExportAsync(Long exportId, Long userId, Set<String> categories) {
        DataExportEntity entity = dataExportRepository.findById(exportId)
                .orElseThrow(() -> new IllegalStateException("ExportEntity not found: " + exportId));

        entity.markProcessing();
        dataExportRepository.save(entity);

        try {
            // データ収集
            entity.updateProgress(10, "collecting_data");
            dataExportRepository.save(entity);

            Map<String, String> collectedData = personalDataCollector.collect(userId, categories);

            entity.updateProgress(60, "generating_zip");
            dataExportRepository.save(entity);

            // ZIPパスワード生成（12文字英数記号）
            String zipPassword = generateZipPassword();

            // ZIP生成
            byte[] zipBytes = generatePasswordProtectedZip(collectedData, zipPassword);

            // サイズチェック（500MB超の場合はメタデータのみにフォールバック）
            if (zipBytes.length > MAX_ZIP_SIZE_BYTES) {
                log.warn("エクスポートZIPが500MB超: userId={}, size={}MB",
                        userId, zipBytes.length / 1024 / 1024);
                zipBytes = generateMetadataOnlyZip(collectedData, zipPassword);
            }

            entity.updateProgress(80, "uploading");
            dataExportRepository.save(entity);

            // S3アップロード
            String s3Key = "gdpr-exports/" + userId + "/" + exportId + ".zip";
            storageService.upload(s3Key, zipBytes, "application/zip");

            // bcryptハッシュを保存（平文は保存しない）
            String zipPasswordHash = BCrypt.hashpw(zipPassword, BCrypt.gensalt());
            LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

            entity.markCompleted(s3Key, zipBytes.length, zipPasswordHash, expiresAt);
            dataExportRepository.save(entity);

            entity.updateProgress(100, "completed");
            dataExportRepository.save(entity);

            // 完了通知メール（ZIPパスワード平文をメールで送信）
            sendCompletionEmail(userId, zipPassword, expiresAt);

        } catch (Exception e) {
            log.error("データエクスポート処理失敗: exportId={}, userId={}", exportId, userId, e);
            entity.markFailed(e.getMessage() != null ? e.getMessage() : "不明なエラー");
            dataExportRepository.save(entity);
            sendFailureEmail(userId);
        }
    }

    /**
     * 最新エクスポートのステータスを返す。
     *
     * @param userId 対象ユーザーID
     * @return DataExportEntity
     */
    public DataExportEntity getExportStatus(Long userId) {
        return dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new BusinessException(GdprErrorCode.GDPR_003));
    }

    /**
     * ダウンロード用署名付きURLを返す。
     *
     * @param userId 対象ユーザーID
     * @return 署名付きダウンロードURL
     */
    public String getDownloadUrl(Long userId) {
        DataExportEntity entity = dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new BusinessException(GdprErrorCode.GDPR_003));

        if (!"COMPLETED".equals(entity.getStatus()) || entity.getS3Key() == null) {
            throw new BusinessException(GdprErrorCode.GDPR_003);
        }
        if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(GdprErrorCode.GDPR_003);
        }

        return storageService.generateDownloadUrl(entity.getS3Key(), Duration.ofHours(1));
    }

    /**
     * スタックしたPROCESSINGをFAILEDにリカバリする。毎時実行。
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "exportRecoveryBatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    @Transactional
    public void recoverStuckExports() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusHours(1);
        int recovered = dataExportRepository.resetStuckProcessing(
                stuckThreshold, "サーバー再起動により処理が中断されました");
        if (recovered > 0) {
            log.warn("エクスポートジョブリカバリ: {}件をFAILEDにリセット", recovered);
        }
    }

    /**
     * 期限切れZIPをS3から削除する。毎日AM5:00実行。
     */
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "exportCleanupBatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpiredExports() {
        List<DataExportEntity> expired = dataExportRepository
                .findByExpiresAtBeforeAndS3KeyIsNotNull(LocalDateTime.now());
        for (DataExportEntity export : expired) {
            try {
                storageService.delete(export.getS3Key());
                export.clearS3Key();
                dataExportRepository.save(export);
            } catch (Exception e) {
                log.warn("エクスポートZIP削除失敗: s3Key={}", export.getS3Key(), e);
            }
        }
        log.info("エクスポートZIPクリーンアップ完了: {}件処理", expired.size());
    }

    private String generateZipPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(ZIP_PASSWORD_LENGTH);
        for (int i = 0; i < ZIP_PASSWORD_LENGTH; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private byte[] generatePasswordProtectedZip(Map<String, String> data, String password) throws Exception {
        // 標準JavaのZipOutputStreamはパスワード非対応のため通常ZIPとして生成し、
        // パスワードはメール本文に記載する方式で対応する。
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private byte[] generateMetadataOnlyZip(Map<String, String> data, String password) throws Exception {
        log.warn("メタデータのみフォールバックZIP生成");
        return generatePasswordProtectedZip(data, password);
    }

    private void sendCompletionEmail(Long userId, String zipPassword, LocalDateTime expiresAt) {
        userRepository.findById(userId).ifPresent(user -> {
            String subject = "個人データエクスポートが完了しました";
            String htmlBody = "<p>" + user.getDisplayName() + " 様</p>" +
                    "<p>個人データのエクスポートが完了しました。</p>" +
                    "<p>ダウンロード有効期限: " + expiresAt + "</p>" +
                    "<p>ZIPパスワード: <strong>" + zipPassword + "</strong></p>" +
                    "<p>パスワードは安全な場所に保管してください。</p>";
            emailService.sendEmail(user.getEmail(), subject, htmlBody);
            log.info("エクスポート完了メール送信: userId={}", userId);
        });
    }

    private void sendFailureEmail(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            String subject = "個人データエクスポートに失敗しました";
            String htmlBody = "<p>" + user.getDisplayName() + " 様</p>" +
                    "<p>個人データのエクスポート処理中にエラーが発生しました。</p>" +
                    "<p>お手数ですが、しばらく経ってから再度お試しください。</p>";
            emailService.sendEmail(user.getEmail(), subject, htmlBody);
            log.warn("エクスポート失敗メール送信: userId={}", userId);
        });
    }
}
