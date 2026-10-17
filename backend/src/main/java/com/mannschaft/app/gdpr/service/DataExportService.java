package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;
import net.lingala.zip4j.model.enums.EncryptionMethod;

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
    private final EmailOutboxService emailOutboxService;
    private final PlatformTransactionManager transactionManager;

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
     * <p>メール送信に必要なユーザー情報（氏名・メアド）は呼び出し元（{@code GdprController}）で
     * 事前に取得して {@link UserEmailInfo} として渡すことで、auth ドメインの
     * {@code UserRepository} への直接参照を避けるドメイン境界設計を実現している。</p>
     *
     * @param exportId エクスポートID
     * @param userInfo メール送信に必要なユーザー情報（userId / email / lastName / firstName）
     * @param categories 収集カテゴリ
     */
    @Async("job-pool")
    // 呼出し元TXを外側の失敗処理へ持ち込まず、内部のREQUIRES_NEWごとに確定する。
    // D-3Tの既存28件は実TX越境が未解消のため、この入口の監視を維持する。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processExportAsync(Long exportId, UserEmailInfo userInfo, Set<String> categories) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicBoolean uploaded = new AtomicBoolean(false);
        try {
            // REQUIREDのoutbox失敗でrollback-onlyになったTXの外へ出てから失敗を確定する。
            transaction.executeWithoutResult(status -> {
                try {
                    processExportInTransaction(exportId, userInfo, categories, uploaded);
                } catch (IOException e) {
                    throw new UncheckedIOException("ZIP生成失敗", e);
                }
            });
        } catch (Exception e) {
            // 例外の本文には収集内容や秘密が含まれ得るため、識別子と原因型だけを記録する。
            log.error("データエクスポート処理失敗: exportId={}, userId={}, causeType={}",
                    exportId, userInfo.userId(), e.getClass().getName());
            if (uploaded.get()) {
                try {
                    storageService.delete("gdpr-exports/" + userInfo.userId() + "/" + exportId + ".zip");
                } catch (Exception cleanupFailure) {
                    log.error("失敗した出力の削除失敗: exportId={}, causeType={}",
                            exportId, cleanupFailure.getClass().getName());
                }
            }
            transaction.executeWithoutResult(status -> {
                // rollbackされたmanaged entityを再利用せず、未完了行を読み直す。
                DataExportEntity failed = dataExportRepository.findById(exportId)
                        .orElseThrow(() -> new IllegalStateException("ExportEntity not found: " + exportId));
                failed.markFailed("エクスポート処理に失敗しました");
                dataExportRepository.save(failed);
            });
            try {
                // 失敗通知自体がenqueueできなくても、確定済みFAILEDを巻き戻さない。
                transaction.executeWithoutResult(status -> sendFailureEmail(userInfo, exportId));
            } catch (Exception notificationFailure) {
                log.error("エクスポート失敗通知の登録失敗: exportId={}, causeType={}",
                        exportId, notificationFailure.getClass().getName());
            }
        }
    }

    /** 暗号化出力の完了とpassword通知の永続化を一つのTXに揃える。 */
    private void processExportInTransaction(Long exportId, UserEmailInfo userInfo, Set<String> categories,
                                            AtomicBoolean uploaded) throws IOException {
        Long userId = userInfo.userId();
        DataExportEntity entity = dataExportRepository.findById(exportId)
                .orElseThrow(() -> new IllegalStateException("ExportEntity not found: " + exportId));

        entity.markProcessing();
        dataExportRepository.save(entity);

        // データ収集
        entity.updateProgress(10, "collecting_data");
        dataExportRepository.save(entity);

        // 他ドメインの一部カテゴリは呼び出し元がこの tx の外で事前取得したもの（userInfo.prefetched()。D-3T）
        Map<String, String> collectedData = personalDataCollector.collect(userId, categories, userInfo.prefetched());

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
        uploaded.set(true);

        // bcryptハッシュを保存（平文は保存しない）
        String zipPasswordHash = BCrypt.hashpw(zipPassword, BCrypt.gensalt());
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

        entity.markCompleted(s3Key, zipBytes.length, zipPasswordHash, expiresAt);
        dataExportRepository.save(entity);

        entity.updateProgress(100, "completed");
        dataExportRepository.save(entity);

        // 完了通知メール（ZIPパスワード平文をメールで送信）
        sendCompletionEmail(userInfo, zipPassword, expiresAt, exportId);

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

        // 「まだ出来ていない」は不在ではなく状態競合（409）。
        if (!"COMPLETED".equals(entity.getStatus()) || entity.getS3Key() == null) {
            throw new BusinessException(GdprErrorCode.GDPR_009);
        }
        // 「かつて存在したが期限で失効した」は 410 GONE（再取得しても復活しないことを伝える）。
        if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(GdprErrorCode.GDPR_010);
        }

        return storageService.generateDownloadUrl(entity.getS3Key(), Duration.ofHours(1));
    }

    /**
     * スタックしたPROCESSINGをFAILEDにリカバリする。毎時実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとスタックしたエクスポートが PROCESSING のまま復旧されず、また期限切れ ZIP が S3 に残り続けて保持期限を超えた個人データが滞留する")
    @BatchEndpoint(name = "gdpr-export-stuck-recovery-hourly", description = "スタックした GDPR エクスポートジョブを毎時 FAILED に戻す")
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "exportRecoveryBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとスタックしたエクスポートが PROCESSING のまま復旧されず、また期限切れ ZIP が S3 に残り続けて保持期限を超えた個人データが滞留する")
    @BatchEndpoint(name = "gdpr-export-expired-cleanup-daily", description = "期限切れ GDPR エクスポート ZIP を毎日 05:00 に R2 から削除する")
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

    private byte[] generatePasswordProtectedZip(Map<String, String> data, String password) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        char[] passwordChars = password.toCharArray();
        try (ZipOutputStream zos = new ZipOutputStream(baos, passwordChars)) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                ZipParameters parameters = new ZipParameters();
                parameters.setFileNameInZip(entry.getKey());
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                parameters.setAesVersion(AesVersion.TWO);
                zos.putNextEntry(parameters);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } finally {
            // BCrypt・既存メールpayloadの不変Stringは消去できないが、writer用可変bufferは残さない。
            Arrays.fill(passwordChars, '\0');
        }
        return baos.toByteArray();
    }

    private byte[] generateMetadataOnlyZip(Map<String, String> data, String password) throws IOException {
        log.warn("メタデータのみフォールバックZIP生成");
        return generatePasswordProtectedZip(data, password);
    }

    /**
     * エクスポート完了通知メールを送信する。
     *
     * <p>メール宛先・氏名は呼び出し元から {@link UserEmailInfo} として受け取ることで、
     * auth ドメインの {@code UserRepository} への直接参照を回避している。</p>
     */
    private void sendCompletionEmail(UserEmailInfo userInfo, String zipPassword,
                                     LocalDateTime expiresAt, Long exportId) {
        String subject = "個人データエクスポートが完了しました";
        String htmlBody = "<p>" + userInfo.lastName() + " " + userInfo.firstName() + " 様</p>" +
                "<p>個人データのエクスポートが完了しました。</p>" +
                "<p>ダウンロード有効期限: " + expiresAt + "</p>" +
                "<p>ZIPパスワード: <strong>" + zipPassword + "</strong></p>" +
                "<p>パスワードは安全な場所に保管してください。</p>";
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "GDPR_EXPORT_READY",
                "ja",
                userInfo.email(),
                Map.of("subject", subject, "body", htmlBody),
                "gdpr",
                "gdpr-export-ready:" + exportId,
                null,
                userInfo.userId(),
                null
        ));
        log.info("エクスポート完了メール enqueue: userId={}, exportId={}", userInfo.userId(), exportId);
    }

    /**
     * エクスポート失敗通知メールを送信する。
     *
     * <p>メール宛先・氏名は呼び出し元から {@link UserEmailInfo} として受け取ることで、
     * auth ドメインの {@code UserRepository} への直接参照を回避している。</p>
     */
    private void sendFailureEmail(UserEmailInfo userInfo, Long exportId) {
        String subject = "個人データエクスポートに失敗しました";
        String htmlBody = "<p>" + userInfo.lastName() + " " + userInfo.firstName() + " 様</p>" +
                "<p>個人データのエクスポート処理中にエラーが発生しました。</p>" +
                "<p>お手数ですが、しばらく経ってから再度お試しください。</p>";
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "GDPR_EXPORT_FAILED",
                "ja",
                userInfo.email(),
                Map.of("subject", subject, "body", htmlBody),
                "gdpr",
                "gdpr-export-failed:" + exportId,
                null,
                userInfo.userId(),
                null
        ));
        log.warn("エクスポート失敗メール enqueue: userId={}, exportId={}", userInfo.userId(), exportId);
    }
}
